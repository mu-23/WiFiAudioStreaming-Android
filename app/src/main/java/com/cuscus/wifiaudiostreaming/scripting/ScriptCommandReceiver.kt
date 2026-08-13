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
 * Unico ingresso per i comandi via broadcast. E' `exported` perche' Tasker,
 * MacroDroid e `adb shell am broadcast` devono poterlo raggiungere, quindi
 * qualsiasi app installata puo' bussare: nessun comando viene eseguito prima che
 * [AutomationGate] abbia validato il token.
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
                // Da qui in poi il token non serve piu' e non deve circolare.
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

    // Lo stop tocca AudioTrack e servizi: resta sul main thread come quando
    // veniva eseguito direttamente in onReceive.
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

    // L'audio interno richiede il consenso MediaProjection, che solo un'Activity
    // puo' chiedere. Il comando e' gia' autorizzato: viaggia come nonce monouso
    // invece che come URI, cosi' il token non finisce nei log di sistema ne' in
    // un Intent che un'altra app potrebbe imitare.
    private fun launchActivityForProjection(context: Context, command: ScriptCommand) {
        val handoff = AutomationGate.issueHandoff(AutomationGate.TrustedAction.Command(command))
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(AutomationGate.EXTRA_HANDOFF, handoff)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        context.startActivity(intent)
    }
}
