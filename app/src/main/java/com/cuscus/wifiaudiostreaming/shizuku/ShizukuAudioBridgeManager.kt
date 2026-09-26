/*
 * Experimental Shizuku bridge controller for the audio-bridge-lab branch.
 *
 * Copyright (c) 2026 Marco Morosi and contributors
 * Licensed under the EUPL, Version 1.2 or later versions approved by the EC.
 */

package com.cuscus.wifiaudiostreaming.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.cuscus.wifiaudiostreaming.BuildConfig
import com.cuscus.wifiaudiostreaming.NetworkManager
import com.cuscus.wifiaudiostreaming.R
import com.cuscus.wifiaudiostreaming.StreamAudioFormat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import rikka.shizuku.Shizuku

object ShizukuAudioBridgeManager {
    private const val TAG = "WFAS_SHIZUKU_APP"
    private const val REQUEST_CODE_PERMISSION = 0x5746
    private val USER_SERVICE_VERSION: Int = BuildConfig.VERSION_CODE
    private const val RUNTIME_PREFS = "wfas_shizuku_runtime"

    data class Config(
        val port: Int,
        val sampleRate: Int = 48_000,
        val channels: Int = 2,
        val packetBytes: Int = 512,
        val keepPlayingOnDevice: Boolean = true,
        val networkInterfaceName: String = "Auto",
        val persistAfterClient: Boolean = false
    )

    sealed class State {
        data object Idle : State()
        data object WaitingForShizuku : State()
        data object WaitingForPermission : State()
        data object Binding : State()
        data class Running(val detail: String) : State()
        data class Error(val detail: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    @Volatile
    private var desiredRunning = false
    @Volatile
    private var pendingConfig: Config? = null
    @Volatile
    private var service: IShizukuAudioBridge? = null
    @Volatile
    private var bound = false
    @Volatile
    private var bindingInProgress = false
    @Volatile
    private var reattachingExisting = false

    private var appContext: Context? = null
    private var listenersInstalled = false

    private val serviceConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = IShizukuAudioBridge.Stub.asInterface(binder)
            bindingInProgress = false
            bound = true
            reconnectHandler.removeCallbacks(rebindRunnable)
            Log.i(TAG, "UserService connected: $name")
            val context = appContext ?: return

            val remoteBuild = runCatching { service?.getBuildVersion() ?: -1 }
                .getOrDefault(-1)
            if (remoteBuild != BuildConfig.VERSION_CODE) {
                Log.w(
                    TAG,
                    "stale UserService build=$remoteBuild expected=" +
                        BuildConfig.VERSION_CODE + "; replacing it"
                )
                val configToRestart = pendingConfig
                service = null
                bound = false
                bindingInProgress = false
                reattachingExisting = false
                runCatching {
                    Shizuku.unbindUserService(userServiceArgs(context), serviceConnection, true)
                }.onFailure {
                    Log.w(TAG, "could not remove stale connected UserService", it)
                }
                if (desiredRunning && configToRestart != null) {
                    reconnectHandler.postDelayed(
                        { bindAndStart(context, configToRestart) },
                        250L
                    )
                }
                return
            }

            val config = pendingConfig
            if (reattachingExisting) {
                reattachingExisting = false
                val detail = runCatching { service?.getStatus() }.getOrNull().orEmpty()
                if (detail.startsWith("running") && config != null) {
                    restoreRunningState(context, config, detail)
                } else if (desiredRunning && config != null) {
                    startRemote(context, config)
                } else {
                    _state.value = State.Idle
                }
            } else if (desiredRunning && config != null) {
                startRemote(context, config)
            } else {
                runCatching { service?.getStatus() }
                    .getOrNull()
                    ?.let { detail ->
                        _state.value = if (detail.startsWith("running")) {
                            NetworkManager.isServerStreaming = true
                            NetworkManager.isStreamingCurrent.value = true
                            NetworkManager.connectionStatus.value = detail
                            State.Running(detail)
                        } else {
                            State.Idle
                        }
                    }
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            bindingInProgress = false
            bound = false
            Log.w(TAG, "UserService disconnected: $name")
            if (desiredRunning) {
                val context = appContext
                val detail = context?.getString(R.string.shizuku_status_disconnected_waiting)
                    ?: "Shizuku audio bridge disconnected; waiting for Shizuku"
                markBridgeUnavailable(context, detail)
                _state.value = State.WaitingForShizuku
                scheduleUserServiceReconnect()
            }
        }
    }

    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode != REQUEST_CODE_PERMISSION) return@OnRequestPermissionResultListener
            val context = appContext ?: return@OnRequestPermissionResultListener
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                pendingConfig?.takeIf { desiredRunning }?.let {
                    bindAndStart(context, it)
                }
            } else {
                fail(context, context.getString(R.string.shizuku_status_permission_denied))
            }
        }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        val context = appContext ?: return@OnBinderReceivedListener
        if (desiredRunning) {
            pendingConfig?.let { begin(context, it) }
        } else {
            rebindExisting(context)
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        service = null
        bindingInProgress = false
        bound = false
        val context = appContext
        if (context != null) {
            NetworkManager.stopBroadcastingPresence()
            NetworkManager.announceServerGone(
                context,
                pendingConfig?.networkInterfaceName ?: "Auto"
            )
        }
        if (desiredRunning) {
            val detail = context?.getString(R.string.shizuku_status_stopped_resume)
                ?: "Shizuku stopped; connection intent kept. Restart Shizuku to resume."
            markBridgeUnavailable(context, detail)
            _state.value = State.WaitingForShizuku
        }
    }

    fun start(context: Context, config: Config) {
        val app = context.applicationContext
        appContext = app
        desiredRunning = true
        pendingConfig = config
        saveDesiredConfig(app, config)
        ensureListeners()
        begin(app, config)
    }

    fun stop(context: Context, removeUserService: Boolean = true) {
        val app = context.applicationContext
        val oldConfig = pendingConfig
        desiredRunning = false
        pendingConfig = null
        reattachingExisting = false
        clearDesiredConfig(app)
        reconnectHandler.removeCallbacks(rebindRunnable)

        NetworkManager.stopBroadcastingPresence()
        NetworkManager.announceServerGone(
            app,
            oldConfig?.networkInterfaceName ?: "Auto"
        )

        runCatching { service?.stopBridge() }
        service = null

        if (bound) {
            runCatching {
                Shizuku.unbindUserService(userServiceArgs(app), serviceConnection, removeUserService)
            }
        }
        bindingInProgress = false
        bound = false

        app.stopService(Intent(app, ShizukuBridgeHostService::class.java))
        NetworkManager.isServerStreaming = false
        NetworkManager.isStreamingCurrent.value = false
        NetworkManager.connectionStatus.value = app.getString(R.string.status_idle)
        _state.value = State.Idle
    }

    /**
     * Reattach after the normal app process/foreground host is recreated while
     * the daemon UserService is still alive.
     */
    fun rebindExisting(context: Context) {
        val app = context.applicationContext
        appContext = app
        if (!desiredRunning || pendingConfig == null) {
            restoreDesiredConfig(app)?.let { restored ->
                desiredRunning = true
                pendingConfig = restored
                Log.i(TAG, "restored Shizuku bridge intent after app-process recreation")
            }
        }
        ensureListeners()
        if (!isBinderReady()) return
        if (bound || bindingInProgress) return

        runCatching {
            reattachingExisting = true
            val args = userServiceArgs(app)
            val version = Shizuku.peekUserService(args, serviceConnection)
            when {
                version == USER_SERVICE_VERSION -> {
                    bindingInProgress = true
                    Log.i(TAG, "reattaching to existing UserService version=$version")
                }
                version >= 0 -> {
                    // Never reconnect to code from an older APK. Shizuku UserService
                    // is a daemon and may survive an app update until it is explicitly
                    // removed or a new UserService version is requested.
                    reattachingExisting = false
                    bindingInProgress = false
                    Log.w(
                        TAG,
                        "stale UserService version=$version expected=$USER_SERVICE_VERSION; replacing it"
                    )
                    runCatching { Shizuku.unbindUserService(args, null, true) }
                        .onFailure { Log.w(TAG, "could not remove stale UserService", it) }
                    pendingConfig?.takeIf { desiredRunning }?.let { bindAndStart(app, it) }
                }
                else -> {
                    reattachingExisting = false
                    pendingConfig?.takeIf { desiredRunning }?.let { begin(app, it) }
                }
            }
        }.onFailure {
            reattachingExisting = false
            Log.w(TAG, "peekUserService failed", it)
            pendingConfig?.takeIf { desiredRunning }?.let { begin(app, it) }
        }
    }

    fun isActive(): Boolean =
        desiredRunning || _state.value is State.Running

    fun refreshRemoteState(context: Context): Boolean {
        val remote = service ?: return isActive()
        val detail = runCatching { remote.getStatus() }.getOrNull() ?: return isActive()

        if (detail == "idle" && _state.value is State.Running) {
            val app = context.applicationContext
            val oldConfig = pendingConfig
            desiredRunning = false
            pendingConfig = null
            reattachingExisting = false
            clearDesiredConfig(app)
            NetworkManager.stopBroadcastingPresence()
            NetworkManager.announceServerGone(
                app,
                oldConfig?.networkInterfaceName ?: "Auto"
            )
            NetworkManager.isServerStreaming = false
            NetworkManager.isStreamingCurrent.value = false
            NetworkManager.connectionStatus.value = app.getString(R.string.status_idle)
            _state.value = State.Idle
            runCatching { service?.stopBridge() }
            if (bound) {
                runCatching {
                    Shizuku.unbindUserService(userServiceArgs(app), serviceConnection, true)
                }
            }
            service = null
            bound = false
            bindingInProgress = false
            Log.i(TAG, "remote bridge became idle; cleared logical server state")
            return false
        }

        if (detail.startsWith("error:", ignoreCase = true) && _state.value is State.Running) {
            fail(context.applicationContext, detail)
            return false
        }

        return isActive()
    }

    private fun begin(context: Context, config: Config) {
        if (!isBinderReady()) {
            val detail = context.getString(R.string.shizuku_status_not_running)
            _state.value = State.WaitingForShizuku
            NetworkManager.connectionStatus.value = detail
            Toast.makeText(context, detail, Toast.LENGTH_LONG).show()

            // Make the Start button actionable: if Shizuku is installed, open it
            // so the user can start the service immediately. No PC is required on
            // Android 11+ when Shizuku is started through wireless debugging.
            runCatching {
                context.packageManager
                    .getLaunchIntentForPackage("moe.shizuku.privileged.api")
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ?.let(context::startActivity)
            }
            return
        }

        val version = runCatching { Shizuku.getVersion() }.getOrDefault(0)
        if (version < 13) {
            fail(context, context.getString(R.string.shizuku_status_version_required))
            return
        }

        val permission = runCatching { Shizuku.checkSelfPermission() }
            .getOrDefault(PackageManager.PERMISSION_DENIED)
        if (permission == PackageManager.PERMISSION_GRANTED) {
            bindAndStart(context, config)
            return
        }

        if (runCatching { Shizuku.shouldShowRequestPermissionRationale() }.getOrDefault(false)) {
            fail(context, context.getString(R.string.shizuku_status_permission_denied))
            return
        }

        _state.value = State.WaitingForPermission
        NetworkManager.connectionStatus.value = context.getString(R.string.shizuku_status_waiting_permission)
        Toast.makeText(
            context,
            context.getString(R.string.shizuku_prompt_allow_permission),
            Toast.LENGTH_LONG
        ).show()
        runCatching { Shizuku.requestPermission(REQUEST_CODE_PERMISSION) }
            .onFailure { fail(context, context.getString(R.string.shizuku_error_request_permission, it.message ?: "unknown")) }
    }

    private fun bindAndStart(context: Context, config: Config) {
        if (!desiredRunning) return
        pendingConfig = config

        if (bound && service != null) {
            startRemote(context, config)
            return
        }
        if (bindingInProgress) return

        _state.value = State.Binding
        NetworkManager.connectionStatus.value =
            context.getString(R.string.shizuku_status_starting_bridge) +
                " · service v" + USER_SERVICE_VERSION
        bindingInProgress = true
        runCatching {
            Shizuku.bindUserService(userServiceArgs(context), serviceConnection)
        }.onFailure {
            bindingInProgress = false
            bound = false
            fail(context, context.getString(R.string.shizuku_error_bind_service, it.message ?: "unknown"))
        }
    }

    private fun startRemote(context: Context, config: Config) {
        if (!desiredRunning) return
        val remote = service ?: return

        val detail = try {
            remote.startBridge(
                config.port,
                config.sampleRate,
                config.channels,
                config.packetBytes,
                config.keepPlayingOnDevice,
                config.persistAfterClient
            )
        } catch (e: RemoteException) {
            fail(context, context.getString(R.string.shizuku_error_remote_call, e.message ?: "unknown"))
            return
        } catch (t: Throwable) {
            fail(context, context.getString(R.string.shizuku_error_bridge_start, t.message ?: "unknown"))
            return
        }

        if (detail.startsWith("error:", ignoreCase = true)) {
            fail(context, detail)
            return
        }

        // The privileged process owns capture + UDP. Discovery stays in the
        // ordinary app process so receivers can find this sender automatically.
        NetworkManager.configureSecurity("OFF", "", false)
        NetworkManager.serverStreamsMic = false
        NetworkManager.startBroadcastingPresence(
            context = context,
            isMulticast = false,
            streamingPort = config.port,
            networkInterfaceName = config.networkInterfaceName,
            rtpEnabled = false,
            audioFormat = StreamAudioFormat(
                sampleRate = config.sampleRate,
                channels = config.channels,
                bitDepth = 16
            )
        )

        NetworkManager.isServerStreaming = true
        NetworkManager.isStreamingCurrent.value = true
        NetworkManager.connectionStatus.value = detail
        _state.value = State.Running(detail)

        val hostIntent = Intent(context, ShizukuBridgeHostService::class.java)
        ContextCompat.startForegroundService(context, hostIntent)
        Log.i(TAG, "bridge started: $detail")
    }

    private fun restoreRunningState(context: Context, config: Config, detail: String) {
        NetworkManager.configureSecurity("OFF", "", false)
        NetworkManager.serverStreamsMic = false
        NetworkManager.startBroadcastingPresence(
            context = context,
            isMulticast = false,
            streamingPort = config.port,
            networkInterfaceName = config.networkInterfaceName,
            rtpEnabled = false,
            audioFormat = StreamAudioFormat(
                sampleRate = config.sampleRate,
                channels = config.channels,
                bitDepth = 16
            )
        )
        NetworkManager.isServerStreaming = true
        NetworkManager.isStreamingCurrent.value = true
        NetworkManager.connectionStatus.value = detail
        _state.value = State.Running(detail)
        Log.i(TAG, "reattached to running bridge without restarting capture: $detail")
    }

    private fun saveDesiredConfig(context: Context, config: Config) {
        context.getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("desired", true)
            .putInt("port", config.port)
            .putInt("sample_rate", config.sampleRate)
            .putInt("channels", config.channels)
            .putInt("packet_bytes", config.packetBytes)
            .putBoolean("keep_playing", config.keepPlayingOnDevice)
            .putString("network_interface", config.networkInterfaceName)
            .putBoolean("persist_after_client", config.persistAfterClient)
            .apply()
    }

    private fun restoreDesiredConfig(context: Context): Config? {
        val prefs = context.getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean("desired", false)) return null
        val port = prefs.getInt("port", 0)
        if (port !in 1024..65535) return null
        return Config(
            port = port,
            sampleRate = prefs.getInt("sample_rate", 48_000),
            channels = prefs.getInt("channels", 2).coerceIn(1, 2),
            packetBytes = prefs.getInt("packet_bytes", 512),
            keepPlayingOnDevice = prefs.getBoolean("keep_playing", true),
            networkInterfaceName = prefs.getString("network_interface", "Auto") ?: "Auto",
            persistAfterClient = prefs.getBoolean("persist_after_client", false)
        )
    }

    private fun clearDesiredConfig(context: Context) {
        context.getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    private val reconnectHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val rebindRunnable = Runnable {
        val context = appContext ?: return@Runnable
        if (!desiredRunning || pendingConfig == null || bound || bindingInProgress || !isBinderReady()) {
            return@Runnable
        }
        rebindExisting(context)
    }

    private fun scheduleUserServiceReconnect() {
        reconnectHandler.removeCallbacks(rebindRunnable)
        reconnectHandler.postDelayed(rebindRunnable, 1_000L)
    }

    private fun markBridgeUnavailable(context: Context?, detail: String) {
        NetworkManager.stopBroadcastingPresence()
        if (context != null) {
            NetworkManager.announceServerGone(
                context,
                pendingConfig?.networkInterfaceName ?: "Auto"
            )
        }
        // Keep the logical server session alive while we wait for Shizuku.
        // Discovery is withdrawn above, so receivers do not see a fake online
        // sender; keeping the session active leaves a visible Stop action in UI.
        NetworkManager.connectionStatus.value = detail
    }

    private fun fail(context: Context, detail: String) {
        Log.e(TAG, detail)
        Toast.makeText(context, detail, Toast.LENGTH_LONG).show()

        desiredRunning = false
        pendingConfig = null
        reattachingExisting = false
        reconnectHandler.removeCallbacks(rebindRunnable)
        clearDesiredConfig(context)

        NetworkManager.stopBroadcastingPresence()
        NetworkManager.isServerStreaming = false
        NetworkManager.isStreamingCurrent.value = false
        NetworkManager.connectionStatus.value = detail
        _state.value = State.Error(detail)

        runCatching { service?.stopBridge() }
        if (bound) {
            runCatching {
                Shizuku.unbindUserService(userServiceArgs(context), serviceConnection, true)
            }
        }
        service = null
        bound = false
        bindingInProgress = false
        context.stopService(Intent(context, ShizukuBridgeHostService::class.java))
    }

    private fun userServiceArgs(context: Context): Shizuku.UserServiceArgs =
        Shizuku.UserServiceArgs(
            ComponentName(
                context.packageName,
                ShizukuAudioBridgeService::class.java.name
            )
        )
            .daemon(true)
            .processNameSuffix("audio_bridge")
            .tag("wfas-audio-bridge")
            .debuggable(true)
            .version(USER_SERVICE_VERSION)

    private fun isBinderReady(): Boolean =
        runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    @Synchronized
    private fun ensureListeners() {
        if (listenersInstalled) return
        listenersInstalled = true
        Shizuku.addRequestPermissionResultListener(permissionListener)
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
    }
}
