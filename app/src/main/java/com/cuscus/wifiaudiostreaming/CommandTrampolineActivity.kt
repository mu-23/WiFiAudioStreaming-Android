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

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.cuscus.wifiaudiostreaming.scripting.AutomationGate

/**
 * Ingresso privato per tile e widget.
 *
 * La MainActivity e' esportata (le serve per il launcher e per i deep link),
 * quindi qualsiasi app potrebbe inviarle un Intent con l'azione giusta: un
 * "connetti al client" arrivato da fuori collegherebbe il telefono a un IP
 * scelto dall'attaccante, microfono incluso, senza mostrare nulla. Questa
 * Activity e' `exported="false"`, quindi la puo' raggiungere solo il nostro
 * processo, e passa alla MainActivity un nonce monouso invece dell'azione nuda.
 */
class CommandTrampolineActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val ip = intent?.getStringExtra(EXTRA_CLIENT_IP)?.trim()
        if (intent?.action == ACTION_CONNECT_CLIENT && !ip.isNullOrBlank()) {
            val handoff = AutomationGate.issueHandoff(AutomationGate.TrustedAction.ConnectClient(ip))
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    putExtra(AutomationGate.EXTRA_HANDOFF, handoff)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
            )
        } else {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }

        finish()
    }

    companion object {
        const val ACTION_CONNECT_CLIENT = "com.cuscus.wifiaudiostreaming.internal.CONNECT_CLIENT"
        const val EXTRA_CLIENT_IP = "CONNECT_CLIENT_IP"
    }
}
