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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val reconnectDelaysMs = longArrayOf(1_000L, 2_000L, 3_000L, 5_000L, 10_000L)

    @Volatile
    private var desiredConnected = false
    @Volatile
    private var desiredTarget: ServerInfo? = null

    private var generation = 0L
    private var reconnectAttempt = 0
    private var reconnectJob: Job? = null
    private var statusJob: Job? = null
    private var attemptInFlight = false

    fun wantsConnection(): Boolean = desiredConnected

    @SuppressLint("MissingPermission")
    fun connect(context: Context, serverInfo: ServerInfo, presharedKey: String? = null) {
        val app = context.applicationContext

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

        // A completed handshake resets the backoff even if the UI has been
        // recreated. The next dropout should again reconnect quickly.
        statusJob = scope.launch {
            NetworkManager.connectionStatus.collect { status ->
                if (!desiredConnected || token != generation) return@collect
                if (status == app.getString(R.string.status_streaming)) {
                    reconnectAttempt = 0
                }
            }
        }

        startAttempt(app, serverInfo, token)
    }

    /**
     * Called by ClientService when Android restarts/re-delivers the service.
     * It does not create a new user intent; it only resumes the existing one.
     */
    @SuppressLint("MissingPermission")
    fun resumeIfNeeded(context: Context) {
        val target = desiredTarget ?: return
        if (!desiredConnected || attemptInFlight || reconnectJob?.isActive == true) return
        startAttempt(context.applicationContext, target, generation)
    }

    /**
     * Explicit UI/user stop. This is the operation that ends the logical
     * connection intent; transport timeouts do not call this.
     */
    fun userDisconnect() {
        desiredConnected = false
        desiredTarget = null
        generation += 1
        reconnectAttempt = 0
        attemptInFlight = false
        reconnectJob?.cancel()
        reconnectJob = null
        statusJob?.cancel()
        statusJob = null
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

                    if (!currentSettings.clientPersistentConnection) {
                        desiredConnected = false
                        desiredTarget = null
                        generation += 1
                        reconnectAttempt = 0
                        reconnectJob?.cancel()
                        reconnectJob = null
                        statusJob?.cancel()
                        statusJob = null
                        context.stopService(Intent(context, ClientService::class.java))
                        Log.i(TAG, "transport session ended; keep-connected preference is off")
                        return@disconnected
                    }

                    scheduleReconnect(context, serverInfo, token)
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
            startAttempt(context, serverInfo, token)
        }
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
