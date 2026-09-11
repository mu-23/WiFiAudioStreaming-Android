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
 * SnapcastClientService.kt
 *
 * Tiene viva la riproduzione Snapcast quando l'app non e' in primo piano.
 *
 * Senza un servizio in primo piano Android spegne il processo appena la
 * schermata se ne va, e la musica di una stanza si fermerebbe perche' qualcuno
 * ha bloccato il telefono. In un impianto multiroom e' il difetto peggiore
 * possibile: si sente subito, e non si capisce da cosa dipenda.
 *
 * La notifica racconta lo stato vero — collegamento, sincronizzazione,
 * riproduzione — invece di una scritta fissa: e' l'unico posto da cui si
 * capisce cosa sta succedendo mentre l'app e' chiusa.
 */

package com.cuscus.wifiaudiostreaming

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import com.cuscus.wifiaudiostreaming.snapcast.SnapStreamState
import com.cuscus.wifiaudiostreaming.snapcast.SnapcastReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class SnapcastClientService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        /*
         * startForeground() prima di ogni altra cosa, senza condizioni.
         *
         * Chi chiama startForegroundService() firma un contratto: entro pochi
         * secondi il servizio deve dichiararsi in primo piano, altrimenti
         * Android non lo ferma — ammazza l'intero processo con
         * ForegroundServiceDidNotStartInTimeException. Uscire prima perche'
         * manca il permesso delle notifiche, com'era scritto qui, voleva dire
         * far cadere l'app invece di rinunciare a una notifica.
         *
         * Il permesso non serve comunque: la notifica di un servizio in primo
         * piano la mostra il sistema di suo. Se manca, saranno i post
         * successivi a non arrivare, e non e' un problema.
         */
        val entered = runCatching {
            NotificationCenter.ensureChannels(this)
            startForeground(
                NotificationCenter.ID_SNAPCAST,
                NotificationCenter.snapcastNotification(this, getString(R.string.snap_state_connecting))
            )
        }.isSuccess

        if (!entered) {
            // Android puo' rifiutare l'avvio (restrizioni sui servizi in primo
            // piano). Allora si molla tutto invece di suonare da un processo
            // che il sistema fermera' comunque appena l'app va in secondo piano.
            runCatching { SnapcastReceiver.disconnect() }
            stopSelf()
            return START_NOT_STICKY
        }

        // La rete Wi-Fi in sospensione perde pacchetti a raffica, e in Snapcast
        // un pacchetto perso e' un buco udibile in tutte le stanze tranne
        // questa: il confronto lo fa l'orecchio dell'utente, non un contatore.
        acquireWakeLock()

        serviceScope.launch {
            SnapcastReceiver.streamStatus.collectLatest { status ->
                if (!SnapcastReceiver.active) {
                    stopSelf()
                    return@collectLatest
                }
                NotificationCenter.post(
                    this@SnapcastClientService,
                    NotificationCenter.ID_SNAPCAST,
                    NotificationCenter.snapcastNotification(
                        this@SnapcastClientService,
                        describe(status.state, status.codec, status.sampleRate, status.channels)
                    )
                )
            }
        }

        return START_STICKY
    }

    private fun describe(state: SnapStreamState, codec: String, rate: Int, channels: Int): String {
        val format = if (rate > 0) "  ·  $codec $rate Hz, $channels ch" else ""
        return when (state) {
            SnapStreamState.CONNECTING -> getString(R.string.snap_state_connecting)
            SnapStreamState.BUFFERING  -> getString(R.string.snap_state_buffering) + format
            SnapStreamState.PLAYING    -> getString(R.string.snap_state_playing) + format
            SnapStreamState.ERROR      -> getString(R.string.snap_state_error)
            SnapStreamState.IDLE       -> getString(R.string.snap_state_idle)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        wakeLock = runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wfas:snapcast").apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        }.getOrNull()
    }

    override fun onDestroy() {
        // Il servizio puo' morire senza che nessuno abbia premuto Esci: sistema
        // a corto di memoria, app tolta dai recenti. Chiudere qui le connessioni
        // e' idempotente — chi ha premuto Esci le ha gia' chiuse — e evita di
        // lasciare socket aperti in un processo che sta finendo.
        runCatching { SnapcastReceiver.disconnect() }
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        NotificationCenter.cancel(this, NotificationCenter.ID_SNAPCAST)
        super.onDestroy()
    }

    private companion object {
        /**
         * Un tetto c'e' sempre: se qualcosa va storto e il servizio non viene
         * fermato, il telefono non deve restare sveglio per sempre. Otto ore
         * bastano a coprire una serata di musica.
         */
        const val WAKE_LOCK_TIMEOUT_MS = 8L * 60L * 60L * 1000L
    }
}
