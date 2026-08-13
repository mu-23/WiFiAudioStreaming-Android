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

import android.content.Context
import android.os.SystemClock
import android.util.Base64
import com.cuscus.wifiaudiostreaming.NotificationCenter
import com.cuscus.wifiaudiostreaming.data.AppSettings
import com.cuscus.wifiaudiostreaming.data.SecretStore
import com.cuscus.wifiaudiostreaming.data.SettingsDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Filtro unico per tutto cio' che arriva da fuori dal processo.
 *
 * Il receiver e' `exported` e la MainActivity ha un intent-filter `wifiaudio://`:
 * entrambi sono raggiungibili da qualsiasi app installata, e un permesso custom
 * non e' una via percorribile perche' Tasker, MacroDroid e i tag NFC non possono
 * dichiararlo. Al posto del permesso ogni comando esterno deve portare un token
 * che vive solo nello storage privato dell'app, quindi un'app terza non lo puo'
 * leggere e non puo' costruire un comando valido.
 *
 * Le chiamate interne (pulsante "Esegui", tile, widget, shortcut) non passano
 * di qui: costruiscono l'oggetto comando in memoria senza mai attraversare un
 * Intent pubblico.
 */
object AutomationGate {

    const val EXTRA_HANDOFF = "com.cuscus.wifiaudiostreaming.extra.HANDOFF"

    enum class Verdict { ALLOWED, DISABLED, BAD_TOKEN, THROTTLED }

    private const val TOKEN_BYTES = 32
    // Il token e' abbastanza lungo da rendere il bruteforce impossibile, ma un
    // tentativo a raffica costa comunque batteria: dopo qualche errore si smette
    // di rispondere per il resto della finestra.
    internal const val FAILURE_WINDOW_MS = 60_000L
    internal const val MAX_FAILURES_PER_WINDOW = 8
    private const val NOTICE_INTERVAL_MS = 30_000L
    private const val HANDOFF_TTL_MS = 60_000L

    private val lock = Any()
    private var windowStartedAt = 0L
    private var failures = 0
    private var lastNoticeAt = 0L

    fun newToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    suspend fun authorize(context: Context, command: ScriptCommand): Boolean {
        val settings = SettingsDataStore(context.applicationContext).settingsFlow.first()
        return authorize(context, settings, command)
    }

    /**
     * Aprire il file cifrato passa dal Keystore, quindi la lettura del token va
     * fatta fuori dal main thread.
     */
    suspend fun authorize(context: Context, settings: AppSettings, command: ScriptCommand): Boolean {
        val appContext = context.applicationContext
        val expected = withContext(Dispatchers.IO) {
            runCatching { SecretStore.get(appContext).ensureAutomationToken() }.getOrDefault("")
        }
        val verdict = verify(settings.automationEnabled, expected, command.token)
        if (verdict != Verdict.ALLOWED) notifyBlocked(appContext, verdict)
        return verdict == Verdict.ALLOWED
    }

    fun verify(enabled: Boolean, expected: String, provided: String?): Verdict =
        verifyAt(SystemClock.elapsedRealtime(), enabled, expected, provided)

    internal fun verifyAt(
        nowMs: Long,
        enabled: Boolean,
        expected: String,
        provided: String?
    ): Verdict = synchronized(lock) {
        if (!enabled) return Verdict.DISABLED
        if (nowMs - windowStartedAt > FAILURE_WINDOW_MS) {
            windowStartedAt = nowMs
            failures = 0
        }
        if (failures >= MAX_FAILURES_PER_WINDOW) return Verdict.THROTTLED
        // Un token atteso vuoto non autorizza nulla: il default e' sempre "nego".
        if (expected.isBlank() || provided.isNullOrBlank() || !constantTimeEquals(expected, provided)) {
            failures++
            return Verdict.BAD_TOKEN
        }
        failures = 0
        windowStartedAt = nowMs
        return Verdict.ALLOWED
    }

    internal fun resetThrottle() = synchronized(lock) {
        failures = 0
        windowStartedAt = 0L
        lastNoticeAt = 0L
    }

    /** Cio' che puo' viaggiare in un handoff: comandi gia' autorizzati. */
    sealed interface TrustedAction {
        data class Command(val command: ScriptCommand) : TrustedAction
        data class ConnectClient(val ip: String) : TrustedAction
    }

    /**
     * Alcune azioni devono rimbalzare verso la MainActivity: il receiver perche'
     * la cattura dell'audio interno richiede il consenso MediaProjection, che
     * solo un'Activity puo' chiedere; tile e widget perche' l'unico ingresso non
     * esportato e' la trampoline. Rimettere il token nell'Intent lo esporrebbe
     * ai log di sistema, quindi l'azione gia' autorizzata resta in memoria e
     * viaggia come nonce monouso: un'app terza non lo puo' indovinare e comunque
     * scade.
     */
    fun issueHandoff(action: TrustedAction): String {
        purgeExpiredHandoffs()
        val id = UUID.randomUUID().toString()
        handoffs[id] = Handoff(action, SystemClock.elapsedRealtime() + HANDOFF_TTL_MS)
        return id
    }

    fun consumeHandoff(id: String?): TrustedAction? {
        if (id.isNullOrBlank()) return null
        purgeExpiredHandoffs()
        val handoff = handoffs.remove(id) ?: return null
        return if (handoff.expiresAt >= SystemClock.elapsedRealtime()) handoff.action else null
    }

    private class Handoff(val action: TrustedAction, val expiresAt: Long)

    private val handoffs = ConcurrentHashMap<String, Handoff>()

    private fun purgeExpiredHandoffs() {
        val now = SystemClock.elapsedRealtime()
        handoffs.entries.removeAll { it.value.expiresAt < now }
    }

    // Confronto sui digest: tempo costante e nessuna informazione sulla
    // lunghezza del token atteso.
    private fun constantTimeEquals(expected: String, provided: String): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        val a = digest.digest(expected.toByteArray(Charsets.UTF_8))
        digest.reset()
        val b = digest.digest(provided.toByteArray(Charsets.UTF_8))
        return MessageDigest.isEqual(a, b)
    }

    // Senza un avviso il comando rifiutato sparirebbe in silenzio e l'utente non
    // capirebbe perche' la sua automazione non funziona piu'.
    private fun notifyBlocked(context: Context, verdict: Verdict) {
        if (verdict == Verdict.THROTTLED) return
        synchronized(lock) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastNoticeAt < NOTICE_INTERVAL_MS) return
            lastNoticeAt = now
        }
        runCatching {
            NotificationCenter.ensureChannels(context)
            NotificationCenter.post(
                context,
                NotificationCenter.ID_AUTOMATION_BLOCKED,
                NotificationCenter.automationBlockedNotification(
                    context,
                    disabled = verdict == Verdict.DISABLED
                )
            )
        }
    }
}
