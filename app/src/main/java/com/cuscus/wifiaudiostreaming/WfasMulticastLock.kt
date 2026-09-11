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
 * WfasMulticastLock.kt
 *
 * Il lucchetto multicast del Wi-Fi, tenuto da chi serve e per quanto serve.
 *
 * Senza, Android scarta i pacchetti multicast prima che arrivino all'app: la
 * ricerca mDNS non trova niente e un flusso RTP su un gruppo multicast resta
 * muto, entrambi pur essendo scritti bene. E' il difetto piu' sgradevole da
 * diagnosticare, perche' non produce nessun errore — solo silenzio.
 *
 * Contato per chiave e non per profondita': la ricerca Snapcast e un ascolto
 * RTP possono essere attivi insieme, e chi finisce per primo non deve spegnere
 * la luce all'altro. Ogni chiamante usa la sua etichetta, e il lucchetto si
 * molla quando l'ultima se ne va.
 */

package com.cuscus.wifiaudiostreaming

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log

object WfasMulticastLock {

    private const val TAG = "MCAST-LOCK"

    private val guard = Any()
    private var lock: WifiManager.MulticastLock? = null
    private val holders = mutableSetOf<String>()

    fun acquire(context: Context, owner: String) {
        synchronized(guard) {
            if (lock == null) {
                lock = runCatching {
                    val wifi = context.applicationContext
                        .getSystemService(Context.WIFI_SERVICE) as WifiManager
                    wifi.createMulticastLock("wfas_multicast").apply { setReferenceCounted(false) }
                }.getOrNull()
            }
            holders += owner
            val held = runCatching { lock?.takeIf { !it.isHeld }?.acquire() }.isSuccess
            if (held) Log.d(TAG, "preso da $owner (in tutto: ${holders.size})")
        }
    }

    fun release(owner: String) {
        synchronized(guard) {
            holders -= owner
            if (holders.isNotEmpty()) return
            runCatching { lock?.takeIf { it.isHeld }?.release() }
            Log.d(TAG, "mollato: nessuno lo tiene piu'")
        }
    }
}
