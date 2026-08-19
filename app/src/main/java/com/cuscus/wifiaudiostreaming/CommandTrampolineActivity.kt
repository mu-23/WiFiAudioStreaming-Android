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
 * Private entry point for tiles, widgets and shortcuts.
 *
 * MainActivity has to stay exported for the launcher and the deep links, so
 * anything it acts on directly is part of the app's public surface. The
 * requests that matter therefore do not go to it: they come here first. This
 * Activity is `exported="false"`, so only our own process can reach it, and
 * what it hands to MainActivity is a single-use nonce rather than a bare
 * action.
 *
 * Connecting to a given address is the most sensitive of the three, since it
 * points the audio path, microphone included, at whatever host it is handed.
 * Start and stop use the same door: every caller we have - tiles, widgets,
 * shortcuts - lives inside the app, so none of them needs a public one.
 */
class CommandTrampolineActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val ip = intent?.getStringExtra(EXTRA_CLIENT_IP)?.trim()
        val trusted = when (intent?.action) {
            ACTION_CONNECT_CLIENT ->
                if (ip.isNullOrBlank()) null
                else AutomationGate.TrustedAction.ConnectClient(ip)
            ACTION_START_SERVER   -> AutomationGate.TrustedAction.StartServer
            ACTION_STOP_STREAMING -> AutomationGate.TrustedAction.StopStreaming
            else -> null
        }

        val forward = Intent(this, MainActivity::class.java)
        if (trusted == null) {
            forward.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            forward.putExtra(AutomationGate.EXTRA_HANDOFF, AutomationGate.issueHandoff(trusted))
            forward.addFlags(
                if (trusted is AutomationGate.TrustedAction.ConnectClient) {
                    // Connect starts from a clean screen, as it did before.
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                } else {
                    // Start and stop are toggles: if the app is already open we
                    // bring it forward through onNewIntent instead of recreating
                    // it, so tapping the widget does not reset the user's screen.
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
            )
        }
        startActivity(forward)

        finish()
    }

    companion object {
        const val ACTION_CONNECT_CLIENT = "com.cuscus.wifiaudiostreaming.internal.CONNECT_CLIENT"
        const val ACTION_START_SERVER   = "com.cuscus.wifiaudiostreaming.internal.START_SERVER"
        const val ACTION_STOP_STREAMING = "com.cuscus.wifiaudiostreaming.internal.STOP_STREAMING"
        const val EXTRA_CLIENT_IP = "CONNECT_CLIENT_IP"
    }
}
