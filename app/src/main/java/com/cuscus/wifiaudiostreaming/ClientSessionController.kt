/*
 * Copyright (c) 2026 Marco Morosi and contributors
 *
 * Licensed under the EUPL, Version 1.2 or – as soon they will be approved by
 * the European Commission - subsequent versions of the EUPL.
 */

package com.cuscus.wifiaudiostreaming

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.cuscus.wifiaudiostreaming.data.SettingsDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Owns the user's intent to stay connected to one WFAS server.
 *
 * A transport/session timeout is not treated as "the user disconnected".
 * Until [userDisconnect] is called (or a non-recoverable configuration/auth
 * error is hit), this controller keeps the target and rebuilds the client
 * session after transient failures.
 *
 * The scope is process-wide rather than ViewModel-scoped, so recreating the UI
 * does not cancel the reconnect loop.
 */
object ClientSessionController {
    private const val TAG = "WFAS_SESSION"
    private const val RUNTIME_PREFS = "wfas_client_runtime"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val reconnectDelaysMs = longArrayOf(1_000L, 2_000L, 3_000L, 5_000L, 10_000L)

    @Volatile
    private var desiredConnected = false
    @Volatile
    private var desiredTarget: ServerInfo? = null
    @Volatile
    private var appContext: Context? = null

    private var generation = 0L
    private var reconnectAttempt = 0
    private var reconnectJob: Job? = null
    private var statusJob: Job? = null
    private var restoreJob: Job? = null
    private var attemptInFlight = false

    fun wantsConnection(): Boolean = desiredConnected

    @SuppressLint("MissingPermission")
    fun connect(context: Context, serverInfo: ServerInfo, presharedKey: String? = null) {
        val app = context.applicationContext
        appContext = app

        generation += 1
        val token = generation
        desiredConnected = true
        desiredTarget = serverInfo
        reconnectAttempt = 0
        attemptInFlight = false

        reconnectJob?.cancel()
        reconnectJob = null
        statusJob?.cancel()

        NetworkManager.clientPresharedKey = presharedKey ?: ""
        NetworkManager.clientKeyFromInvite = presharedKey != null
        NetworkManager.clearInviteRejected()
        if (presharedKey == null) NetworkManager.expectedMcastEpoch = null

        ensureClientService(app)

        installStatusObserver(app, token)
        startAttempt(app, serverInfo, token)
    }

    /**
     * Called by ClientService when Android restarts/re-delivers the service.
     * It does not create a new user intent; it only resumes the existing one.
     */
    @SuppressLint("MissingPermission")
    fun resumeIfNeeded(context: Context) {
        val app = context.applicationContext
        appContext = app

        val target = desiredTarget
        if (desiredConnected && target != null) {
            if (!attemptInFlight && reconnectJob?.isActive != true) {
                startAttempt(app, target, generation)
            }
            return
        }

        if (restoreJob?.isActive == true) return
        restoreJob = scope.launch {
            val currentSettings = SettingsDataStore(app).settingsFlow.first()
            if (!currentSettings.clientPersistentConnection) {
                clearDesiredTarget(app)
                return@launch
            }

            val restored = restoreDesiredTarget(app) ?: return@launch
            if (desiredConnected || attemptInFlight || reconnectJob?.isActive == true) return@launch

            generation += 1
            val token = generation
            desiredConnected = true
            desiredTarget = restored
            reconnectAttempt = 0
            attemptInFlight = false
            NetworkManager.clientPresharedKey = ""
            NetworkManager.clientKeyFromInvite = false
            NetworkManager.clearInviteRejected()
            NetworkManager.expectedMcastEpoch = null
            installStatusObserver(app, token)
            Log.i(TAG, "restored persistent client target " + restored.ip + ":" + restored.port)
            startAttempt(app, restored, token)
        }
    }

    /**
     * Explicit UI/user stop. This is the operation that ends the logical
     * connection intent; transport timeouts do not call this.
     */
    fun userDisconnect(context: Context? = null) {
        val clearContext = context?.applicationContext ?: appContext
        desiredConnected = false
        desiredTarget = null
        generation += 1
        reconnectAttempt = 0
        attemptInFlight = false
        reconnectJob?.cancel()
        reconnectJob = null
        statusJob?.cancel()
        statusJob = null
        restoreJob?.cancel()
        restoreJob = null
        clearContext?.let(::clearDesiredTarget)
        Log.i(TAG, "logical client session ended by explicit user stop")
    }

    @SuppressLint("MissingPermission")
    private fun startAttempt(context: Context, serverInfo: ServerInfo, token: Long) {
        if (!desiredConnected || token != generation || attemptInFlight) return

        attemptInFlight = true
        scope.launch {
            val currentSettings = SettingsDataStore(context).settingsFlow.first()
            if (!desiredConnected || token != generation) {
                attemptInFlight = false
                return@launch
            }

            if (currentSettings.clientPersistentConnection) {
                saveDesiredTarget(context, serverInfo)
            } else {
                clearDesiredTarget(context)
            }

            NetworkManager.configureSecurity(
                currentSettings.securityMode,
                currentSettings.authKey,
                currentSettings.encryptionEnabled
            )

            Log.i(
                TAG,
                "starting client attempt target=${serverInfo.ip}:${serverInfo.port} token=$token"
            )

            NetworkManager.startClient(
                context = context,
                serverInfo = serverInfo,
                sampleRate = currentSettings.sampleRate,
                channelConfig = currentSettings.channelConfig,
                bufferSize = currentSettings.bufferSize,
                sendMicrophone = currentSettings.sendClientMicrophone,
                micPort = currentSettings.micPort,
                networkInterfaceName = currentSettings.networkInterface,
                connectionSoundEnabled = currentSettings.connectionSoundEnabled,
                disconnectionSoundEnabled = currentSettings.disconnectionSoundEnabled,
                onServerDisconnected = disconnected@{
                    attemptInFlight = false

                    if (!desiredConnected || token != generation) {
                        return@disconnected
                    }

                    if (isNonRecoverable(context)) {
                        Log.w(
                            TAG,
                            "client session paused by non-recoverable state: " +
                                NetworkManager.connectionStatus.value
                        )
                        return@disconnected
                    }

                    scope.launch {
                        val latestSettings = SettingsDataStore(context).settingsFlow.first()
                        if (!desiredConnected || token != generation) return@launch

                        if (!latestSettings.clientPersistentConnection) {
                            desiredConnected = false
                            desiredTarget = null
                            generation += 1
                            reconnectAttempt = 0
                            reconnectJob?.cancel()
                            reconnectJob = null
                            statusJob?.cancel()
                            statusJob = null
                            clearDesiredTarget(context)
                            context.stopService(Intent(context, ClientService::class.java))
                            Log.i(TAG, "transport session ended; keep-connected preference is off")
                            return@launch
                        }

                        val refreshedTarget = refreshReconnectTarget(serverInfo)
                        desiredTarget = refreshedTarget
                        saveDesiredTarget(context, refreshedTarget)
                        scheduleReconnect(context, refreshedTarget, token)
                    }
                }
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun scheduleReconnect(context: Context, serverInfo: ServerInfo, token: Long) {
        if (!desiredConnected || token != generation || reconnectJob?.isActive == true) return

        val delayMs = reconnectDelaysMs[
            reconnectAttempt.coerceAtMost(reconnectDelaysMs.lastIndex)
        ]
        reconnectAttempt += 1

        reconnectJob = scope.launch {
            NetworkManager.connectionStatus.value =
                context.getString(R.string.status_link_lost_waiting)

            Log.i(
                TAG,
                "transport session lost; keeping desired connection and retrying in ${delayMs}ms"
            )

            delay(delayMs)
            if (!desiredConnected || token != generation) return@launch

            reconnectJob = null
            val refreshedTarget = refreshReconnectTarget(serverInfo)
            desiredTarget = refreshedTarget
            saveDesiredTarget(context, refreshedTarget)
            startAttempt(context, refreshedTarget, token)
        }
    }

    private fun installStatusObserver(context: Context, token: Long) {
        statusJob?.cancel()
        statusJob = scope.launch {
            NetworkManager.connectionStatus.collect { status ->
                if (!desiredConnected || token != generation) return@collect
                if (status == context.getString(R.string.status_streaming)) {
                    reconnectAttempt = 0
                }
            }
        }
    }

    private fun refreshReconnectTarget(previous: ServerInfo): ServerInfo {
        val discovered = NetworkManager.discoveredDevices.value.values
        discovered.firstOrNull { it.ip == previous.ip }?.let { return it }
        if (previous.hostname.isNotBlank()) {
            discovered.firstOrNull {
                it.hostname.isNotBlank() && it.hostname == previous.hostname
            }?.let {
                Log.i(
                    TAG,
                    "sender moved " + previous.ip + ":" + previous.port +
                        " -> " + it.ip + ":" + it.port + "; following discovery"
                )
                return it
            }
        }
        return previous
    }

    private fun saveDesiredTarget(context: Context, target: ServerInfo) {
        val editor = context.getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("desired", true)
            .putString("ip", target.ip)
            .putBoolean("multicast", target.isMulticast)
            .putInt("port", target.port)
            .putString("hostname", target.hostname)
            .putString("security_mode", target.securityMode)
            .putBoolean("encrypted", target.encrypted)
            .putBoolean("server_sends_mic", target.serverSendsMic)
            .putBoolean("server_wants_mic", target.serverWantsMic)
            .putBoolean("from_beacon", target.fromBeacon)
            .putBoolean("via_usb", target.viaUsb)

        val format = target.audioFormat
        editor.putBoolean("has_audio_format", format != null)
        if (format != null) {
            editor.putInt("audio_sample_rate", format.sampleRate)
            editor.putInt("audio_channels", format.channels)
            editor.putInt("audio_bit_depth", format.bitDepth)
        } else {
            editor.remove("audio_sample_rate")
            editor.remove("audio_channels")
            editor.remove("audio_bit_depth")
        }
        editor.apply()
    }

    private fun restoreDesiredTarget(context: Context): ServerInfo? {
        val prefs = context.getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean("desired", false)) return null
        val ip = prefs.getString("ip", "").orEmpty()
        val port = prefs.getInt("port", 0)
        if (ip.isBlank() || port !in 1..65535) return null

        val audioFormat = if (prefs.getBoolean("has_audio_format", false)) {
            StreamAudioFormat(
                sampleRate = prefs.getInt("audio_sample_rate", 48_000),
                channels = prefs.getInt("audio_channels", 2),
                bitDepth = prefs.getInt("audio_bit_depth", 16)
            )
        } else {
            null
        }

        return ServerInfo(
            ip = ip,
            isMulticast = prefs.getBoolean("multicast", false),
            port = port,
            hostname = prefs.getString("hostname", "").orEmpty(),
            securityMode = prefs.getString("security_mode", null),
            encrypted = prefs.getBoolean("encrypted", false),
            serverSendsMic = prefs.getBoolean("server_sends_mic", false),
            serverWantsMic = prefs.getBoolean("server_wants_mic", false),
            fromBeacon = prefs.getBoolean("from_beacon", false),
            audioFormat = audioFormat,
            viaUsb = prefs.getBoolean("via_usb", false)
        )
    }

    private fun clearDesiredTarget(context: Context) {
        context.getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    /**
     * Errors that require user input or a different binary/configuration do not
     * spin forever. Ordinary timeouts, BYE, Wi-Fi loss, server restart, socket
     * errors and BUSY remain recoverable and keep retrying.
     */
    private fun isNonRecoverable(context: Context): Boolean {
        val status = NetworkManager.connectionStatus.value
        val unsupportedPrefix = context
            .getString(R.string.status_unsupported_format, "__FORMAT__")
            .substringBefore("__FORMAT__")

        return status == context.getString(R.string.status_protocol_incompatible) ||
            status == context.getString(R.string.status_unauthorized) ||
            status == context.getString(R.string.status_key_required) ||
            (unsupportedPrefix.isNotBlank() && status.startsWith(unsupportedPrefix))
    }

    private fun ensureClientService(context: Context) {
        val intent = Intent(context, ClientService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }
}
