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
import com.cuscus.wifiaudiostreaming.NetworkManager
import com.cuscus.wifiaudiostreaming.R
import com.cuscus.wifiaudiostreaming.StreamAudioFormat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import rikka.shizuku.Shizuku

object ShizukuAudioBridgeManager {
    private const val TAG = "WFAS_SHIZUKU_APP"
    private const val REQUEST_CODE_PERMISSION = 0x5746
    private const val USER_SERVICE_VERSION = 1

    data class Config(
        val port: Int,
        val sampleRate: Int = 48_000,
        val channels: Int = 2,
        val packetBytes: Int = 512,
        val keepPlayingOnDevice: Boolean = true,
        val networkInterfaceName: String = "Auto"
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

    private var appContext: Context? = null
    private var listenersInstalled = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = IShizukuAudioBridge.Stub.asInterface(binder)
            bindingInProgress = false
            bound = true
            Log.i(TAG, "UserService connected: $name")
            val context = appContext ?: return
            val config = pendingConfig
            if (desiredRunning && config != null) {
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
                _state.value = State.WaitingForShizuku
                NetworkManager.connectionStatus.value = "Shizuku audio bridge disconnected; waiting for Shizuku"
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
                fail(context, "Shizuku permission denied")
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
            _state.value = State.WaitingForShizuku
            NetworkManager.connectionStatus.value =
                "Shizuku stopped; connection intent kept. Restart Shizuku to resume."
        }
    }

    fun start(context: Context, config: Config) {
        val app = context.applicationContext
        appContext = app
        desiredRunning = true
        pendingConfig = config
        ensureListeners()
        begin(app, config)
    }

    fun stop(context: Context, removeUserService: Boolean = true) {
        val app = context.applicationContext
        val oldConfig = pendingConfig
        desiredRunning = false
        pendingConfig = null

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
        ensureListeners()
        if (!isBinderReady()) return
        if (bound || bindingInProgress) return

        runCatching {
            val version = Shizuku.peekUserService(userServiceArgs(app), serviceConnection)
            if (version >= 0) {
                bindingInProgress = true
                Log.i(TAG, "reattaching to existing UserService version=$version")
            }
        }.onFailure {
            Log.w(TAG, "peekUserService failed", it)
        }
    }

    fun isActive(): Boolean =
        desiredRunning || _state.value is State.Running

    private fun begin(context: Context, config: Config) {
        if (!isBinderReady()) {
            val detail = "Shizuku is not running. Start Shizuku, then return to WFAS."
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
            fail(context, "Shizuku v13+ is required for the AudioPolicy bridge")
            return
        }

        val permission = runCatching { Shizuku.checkSelfPermission() }
            .getOrDefault(PackageManager.PERMISSION_DENIED)
        if (permission == PackageManager.PERMISSION_GRANTED) {
            bindAndStart(context, config)
            return
        }

        if (runCatching { Shizuku.shouldShowRequestPermissionRationale() }.getOrDefault(false)) {
            fail(context, "Shizuku permission was denied. Grant WFAS access in Shizuku.")
            return
        }

        _state.value = State.WaitingForPermission
        NetworkManager.connectionStatus.value = "Waiting for Shizuku permission"
        Toast.makeText(
            context,
            "Please allow WFAS in the Shizuku permission dialog.",
            Toast.LENGTH_LONG
        ).show()
        runCatching { Shizuku.requestPermission(REQUEST_CODE_PERMISSION) }
            .onFailure { fail(context, "Cannot request Shizuku permission: ${it.message}") }
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
        NetworkManager.connectionStatus.value = "Starting Shizuku audio bridge"
        bindingInProgress = true
        runCatching {
            Shizuku.bindUserService(userServiceArgs(context), serviceConnection)
        }.onFailure {
            bindingInProgress = false
            bound = false
            fail(context, "Cannot bind Shizuku UserService: ${it.message}")
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
                config.keepPlayingOnDevice
            )
        } catch (e: RemoteException) {
            fail(context, "Shizuku UserService call failed: ${e.message}")
            return
        } catch (t: Throwable) {
            fail(context, "Shizuku bridge start failed: ${t.message}")
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

    private fun fail(context: Context, detail: String) {
        Log.e(TAG, detail)
        Toast.makeText(context, detail, Toast.LENGTH_LONG).show()
        NetworkManager.stopBroadcastingPresence()
        NetworkManager.isServerStreaming = false
        NetworkManager.isStreamingCurrent.value = false
        NetworkManager.connectionStatus.value = detail
        _state.value = State.Error(detail)
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
