/*
 * Copyright (c) 2026 Marco Morosi
 *
 * Licensed under the EUPL, Version 1.2 or – as soon they will be approved by
 * the European Commission - subsequent versions of the EUPL (the "Licence");
 * You may not use this work except in compliance with the Licence.
 * You may obtain a copy of the Licence at:
 *
 * https://joinup.ec.europa.eu/software/page/eupl
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the Licence is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Licence for the specific language governing permissions and
 * limitations under the Licence.
 */

package com.cuscus.wifiaudiostreaming

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.widget.Toast
import com.cuscus.wifiaudiostreaming.NetworkManager.updateWidgetState
import kotlinx.coroutines.*

class ClientService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!NotificationCenter.canPost(this)) {
            Toast.makeText(this, "Notifications permission missing", Toast.LENGTH_SHORT).show()
            stopSelf()
            return START_NOT_STICKY
        }
        serviceScope.launch { updateWidgetState(this@ClientService, true, false) }

        NotificationCenter.ensureChannels(this)
        startForeground(
            NotificationCenter.ID_CLIENT,
            NotificationCenter.clientNotification(this, getString(R.string.notif_connecting))
        )

        // Keep the CPU awake while WFAS is actively receiving, matching the
        // RTP and Snapcast client services. Without this, some Android devices
        // throttle the receiver aggressively when the screen turns off.
        acquireWakeLock()

        serviceScope.launch {
            NetworkManager.connectionStatus
                .collect { status ->
                    NotificationCenter.post(
                        this@ClientService,
                        NotificationCenter.ID_CLIENT,
                        NotificationCenter.clientNotification(
                            this@ClientService,
                            status.ifBlank { getString(R.string.notif_connecting) }
                        )
                    )
                    delay(UPDATE_THROTTLE_MS)
                }
        }

        return START_STICKY
    }

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        wakeLock = runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wfas:client").apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        }.getOrNull()
    }

    override fun onDestroy() {
        CoroutineScope(Dispatchers.IO).launch {
            updateWidgetState(this@ClientService, false, false)
        }
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        NotificationCenter.cancel(this, NotificationCenter.ID_CLIENT)
        super.onDestroy()
    }

    private companion object {
        const val UPDATE_THROTTLE_MS = 350L
        const val WAKE_LOCK_TIMEOUT_MS = 8L * 60L * 60L * 1000L
    }
}