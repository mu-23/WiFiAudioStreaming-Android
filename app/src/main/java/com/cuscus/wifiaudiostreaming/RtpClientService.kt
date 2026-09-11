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
 *
 * --------------------------------------------------------------------------
 * RtpClientService.kt
 *
 * Tiene viva la ricezione RTP quando l'app non e' in primo piano.
 *
 * Stessa forma del servizio Snapcast, e stessa lezione imparata: startForeground()
 * per primo e senza condizioni, o Android non ferma il servizio — ammazza il
 * processo.
 */

package com.cuscus.wifiaudiostreaming

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import com.cuscus.wifiaudiostreaming.rtp.RtpSession
import com.cuscus.wifiaudiostreaming.rtp.RtpState
import com.cuscus.wifiaudiostreaming.rtp.RtpStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class RtpClientService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val entered = runCatching {
            NotificationCenter.ensureChannels(this)
            startForeground(
                NotificationCenter.ID_RTP,
                NotificationCenter.rtpNotification(this, getString(R.string.rtp_state_waiting))
            )
        }.isSuccess

        if (!entered) {
            runCatching { RtpSession.stop() }
            stopSelf()
            return START_NOT_STICKY
        }

        acquireWakeLock()

        serviceScope.launch {
            RtpSession.status.collectLatest { status ->
                if (!RtpSession.active) {
                    stopSelf()
                    return@collectLatest
                }
                NotificationCenter.post(
                    this@RtpClientService,
                    NotificationCenter.ID_RTP,
                    NotificationCenter.rtpNotification(this@RtpClientService, describe(status))
                )
            }
        }

        return START_STICKY
    }

    /**
     * La notifica racconta lo stato vero.
     *
     * "In attesa" e "in riproduzione" sono due cose diverse e vanno distinte:
     * con RTP non c'e' nessuna stretta di mano, quindi una porta aperta su cui
     * non arriva niente e un flusso che suona hanno lo stesso aspetto da fuori.
     * A schermo spento questa riga e' l'unico modo di saperlo.
     */
    private fun describe(status: RtpStatus): String {
        val src = status.source
        val where = when {
            src == null -> ""
            src.address.isBlank() -> "  ·  :${src.port}"
            else -> "  ·  ${src.address}:${src.port}"
        }
        return when (status.state) {
            RtpState.WAITING -> getString(R.string.rtp_state_waiting) + where
            RtpState.PLAYING -> getString(R.string.rtp_state_playing) + where
            RtpState.ERROR -> getString(R.string.rtp_state_error)
            RtpState.IDLE -> getString(R.string.rtp_state_idle)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        wakeLock = runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wfas:rtp").apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        }.getOrNull()
    }

    override fun onDestroy() {
        runCatching { RtpSession.stop() }
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        NotificationCenter.cancel(this, NotificationCenter.ID_RTP)
        super.onDestroy()
    }

    private companion object {
        const val WAKE_LOCK_TIMEOUT_MS = 8L * 60L * 60L * 1000L
    }
}
