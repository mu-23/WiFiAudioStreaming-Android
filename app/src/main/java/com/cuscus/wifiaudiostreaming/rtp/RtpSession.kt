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
 * RtpSession.kt
 *
 * L'ascolto RTP visto da fuori: quello che la UI e il servizio chiamano.
 *
 * Un ricevitore alla volta, come per Snapcast: un telefono suona in una stanza
 * sola. Sta in un object e non in un ViewModel perche' la sessione vive piu' a
 * lungo della schermata — girare il telefono non deve interrompere la musica.
 *
 * A differenza di Snapcast qui non c'e' niente da scoprire in rete: RTP non si
 * annuncia, e' un flusso che qualcuno sta gia' mandando a un indirizzo e a una
 * porta. Tutto quello che serve sta nell'SDP, o nei quattro campi che l'SDP
 * conterrebbe.
 */

package com.cuscus.wifiaudiostreaming.rtp

import android.content.Context
import com.cuscus.wifiaudiostreaming.WfasMulticastLock
import com.cuscus.wifiaudiostreaming.openPcmPlaybackSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object RtpSession {

    private const val LOCK_OWNER = "rtp-multicast"

    private var receiver: RtpReceiver? = null
    private var scope: CoroutineScope? = null
    private var lockHeld = false

    private val _status = MutableStateFlow(RtpStatus())
    val status: StateFlow<RtpStatus> = _status.asStateFlow()

    private val _source = MutableStateFlow<RtpSource?>(null)
    val source: StateFlow<RtpSource?> = _source.asStateFlow()

    val active: Boolean get() = receiver != null

    /**
     * Mette in ascolto una sorgente.
     *
     * [latencyMs] e' il riempimento della linea di uscita, e qui — al
     * contrario di Snapcast — lo decide l'utente: in RTP non c'e' nessun
     * server che detti quando suonare, quindi il compromesso fra ritardo e
     * robustezza resta una scelta di chi ascolta.
     */
    fun start(context: Context, src: RtpSource, latencyMs: Int, preferredInterface: String) {
        stop()
        val app = context.applicationContext
        val s = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope = s
        _source.value = src

        // Un gruppo multicast senza lucchetto e' una porta aperta su cui non
        // arriva mai niente. Per l'unicast non serve e non si prende.
        if (src.isMulticast) {
            WfasMulticastLock.acquire(app, LOCK_OWNER)
            lockHeld = true
        }

        receiver = RtpReceiver(
            source = src,
            onStatus = { _status.value = it },
            onPcm = null,
            openPlayer = { rate, ch -> openPcmPlaybackSink(rate, ch, latencyMs, "RTP") },
            preferredInterface = preferredInterface
        ).also { it.start(s) }
    }

    fun stop() {
        receiver?.stop()
        receiver = null
        scope?.cancel()
        scope = null
        if (lockHeld) {
            WfasMulticastLock.release(LOCK_OWNER)
            lockHeld = false
        }
        _status.value = RtpStatus()
        _source.value = null
    }
}
