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

package com.cuscus.wifiaudiostreaming.scripting

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.cuscus.wifiaudiostreaming.MainActivity
import com.cuscus.wifiaudiostreaming.NetworkManager
import com.cuscus.wifiaudiostreaming.data.AppSettings
import com.cuscus.wifiaudiostreaming.data.SettingsDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The only broadcast entry point for commands. It has to be `exported` for
 * Tasker, MacroDroid and `adb shell am broadcast` to reach it, so nothing is
 * dispatched until [AutomationGate] has validated the token.
 */
class ScriptCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val command = ScriptCommand.fromIntent(intent) ?: return
        val appContext = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val store = SettingsDataStore(appContext)
                val settings = store.settingsFlow.first()
                if (!AutomationGate.authorize(appContext, settings, command)) return@launch
                // The token has done its job: it must not travel any further.
                dispatch(appContext, store, settings, command.withToken(null))
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun dispatch(
        context: Context,
        store: SettingsDataStore,
        settings: AppSettings,
        command: ScriptCommand
    ) {
        when (command.action) {
            ScriptActionType.STOP -> stopStreaming(context)

            ScriptActionType.SET -> ScriptExecutor.applySet(context, command)

            ScriptActionType.CONNECT -> ScriptExecutor.connect(context, command)

            ScriptActionType.USB -> ScriptExecutor.applyUsbAction(context, command)

            ScriptActionType.TOGGLE -> {
                if (NetworkManager.isStreamingCurrent.value) stopStreaming(context)
                else startServer(context, store, settings, command)
            }

            ScriptActionType.START_SERVER -> startServer(context, store, settings, command)
        }
    }

    // Stopping touches AudioTrack and services, so it stays on the main thread,
    // as it was when it ran directly inside onReceive.
    private suspend fun stopStreaming(context: Context) = withContext(Dispatchers.Main) {
        ScriptExecutor.stop(context)
    }

    private suspend fun startServer(
        context: Context,
        store: SettingsDataStore,
        settings: AppSettings,
        command: ScriptCommand
    ) {
        ScriptExecutor.persistSecurityIfPresent(store, settings, command)
        ScriptExecutor.applyLinkOverrides(settings, command, context)
        val resolved = ScriptExecutor.resolveServerParams(settings, command)
        if (!resolved.streamInternal && resolved.streamMic) {
            ScriptExecutor.startServerMicOnly(context, resolved)
        } else {
            launchActivityForProjection(context, command)
        }
    }

    // Internal audio needs MediaProjection consent, which only an Activity can
    // ask for. The command is already authorised, so it travels as a single-use
    // nonce rather than a URI: that keeps the token out of the system logs and
    // out of any Intent.
    private fun launchActivityForProjection(context: Context, command: ScriptCommand) {
        val handoff = AutomationGate.issueHandoff(AutomationGate.TrustedAction.Command(command))
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(AutomationGate.EXTRA_HANDOFF, handoff)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        context.startActivity(intent)
    }
}
