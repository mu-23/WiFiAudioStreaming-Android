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
 * Single gate for everything that reaches the app from outside the process.
 *
 * Automation has to stay usable by tools that cannot declare a custom
 * permission - Tasker, MacroDroid, NFC tags, `adb shell am broadcast` - so
 * holding a token takes the place of holding a permission. The token lives
 * only in the app's private encrypted storage, and a command that does not
 * carry it is refused.
 *
 * Internal callers (the "Run" button, tiles, widgets, shortcuts) never come
 * through here: they build the command object in memory and it never travels
 * on a public Intent.
 */
object AutomationGate {

    const val EXTRA_HANDOFF = "com.cuscus.wifiaudiostreaming.extra.HANDOFF"

    enum class Verdict { ALLOWED, DISABLED, BAD_TOKEN, THROTTLED }

    private const val TOKEN_BYTES = 32
    // The token is long enough that guessing is not the concern, but repeated
    // attempts still cost battery: after a few failures the gate stops answering
    // for the rest of the window.
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
     * Opening the encrypted file goes through the Keystore, so reading the token
     * has to happen off the main thread.
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
        // A blank expected token authorises nothing: the default is always deny.
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

    /** What may travel in a handoff: actions that are already authorised. */
    sealed interface TrustedAction {
        data class Command(val command: ScriptCommand) : TrustedAction
        data class ConnectClient(val ip: String) : TrustedAction
        /** Server start from a tile, widget or shortcut. */
        object StartServer : TrustedAction
        /** Stop from a tile, widget or shortcut. */
        object StopStreaming : TrustedAction
    }

    /**
     * Some actions have to bounce to MainActivity: the receiver because
     * capturing internal audio needs MediaProjection consent, which only an
     * Activity can ask for; tiles and widgets because the trampoline is the only
     * non-exported entry point. Putting the token back into an Intent would
     * expose it to the system logs, so the already-authorised action stays in
     * memory and only a single-use nonce travels, which expires on its own.
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

    // Compared on the digests: constant time, and nothing observable about the
    // length of the expected token.
    private fun constantTimeEquals(expected: String, provided: String): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        val a = digest.digest(expected.toByteArray(Charsets.UTF_8))
        digest.reset()
        val b = digest.digest(provided.toByteArray(Charsets.UTF_8))
        return MessageDigest.isEqual(a, b)
    }

    // Without a notice a refused command would vanish silently and the user
    // would have no idea why their automation stopped working.
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
