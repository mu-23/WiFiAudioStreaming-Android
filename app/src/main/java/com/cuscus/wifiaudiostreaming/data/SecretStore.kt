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

package com.cuscus.wifiaudiostreaming.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.cuscus.wifiaudiostreaming.scripting.AutomationGate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore

/**
 * The app's secrets, deliberately kept out of the DataStore.
 *
 * The DataStore is fine for settings, but its file is included in Auto Backup
 * and in device-to-device transfer, and a token in cleartext inside a backup is
 * a token that has left the phone. Here the value is encrypted with a key that
 * lives in the Keystore and never leaves the TEE, so a copy of the file is
 * unreadable anywhere else. That ties the token to the device that generated
 * it, which for an automation token is the property we want rather than a side
 * effect.
 *
 * It is no defence against root, which becomes our own UID and can ask the
 * Keystore to decrypt: the point is offline extraction of the data partition,
 * and backups. The exclusion rule in `data_extraction_rules.xml` covers the
 * rest.
 */
class SecretStore private constructor(private val prefs: SharedPreferences) {

    private val _automationToken = MutableStateFlow(prefs.getString(KEY_AUTOMATION_TOKEN, "").orEmpty())
    val automationToken: StateFlow<String> = _automationToken.asStateFlow()

    /** Generates the token on first access and reuses it from then on. */
    @Synchronized
    fun ensureAutomationToken(): String {
        val current = prefs.getString(KEY_AUTOMATION_TOKEN, "").orEmpty()
        if (current.isNotBlank()) {
            _automationToken.value = current
            return current
        }
        return regenerateAutomationToken()
    }

    @Synchronized
    fun regenerateAutomationToken(): String {
        val token = AutomationGate.newToken()
        prefs.edit().putString(KEY_AUTOMATION_TOKEN, token).commit()
        _automationToken.value = token
        return token
    }

    // Per-entry auto-connect keys. Stored here — encrypted, Keystore-backed, out
    // of backup — rather than in the settings DataStore, so an unattended
    // connection to a KEY-mode server has its key without prompting, and the key
    // is never persisted in cleartext. Keyed by the entry's opaque `keyRef`.
    @Synchronized
    fun putAutoConnectKey(ref: String, key: String) {
        if (ref.isBlank()) return
        prefs.edit().putString(AUTO_CONNECT_KEY_PREFIX + ref, key).commit()
    }

    fun getAutoConnectKey(ref: String): String? {
        if (ref.isBlank()) return null
        return prefs.getString(AUTO_CONNECT_KEY_PREFIX + ref, null)?.takeIf { it.isNotBlank() }
    }

    @Synchronized
    fun clearAutoConnectKey(ref: String) {
        if (ref.isBlank()) return
        prefs.edit().remove(AUTO_CONNECT_KEY_PREFIX + ref).commit()
    }

    companion object {
        private const val FILE_NAME = "wfas_secrets"
        private const val MASTER_KEY_ALIAS = "wfas_secrets_master_key"
        private const val KEY_AUTOMATION_TOKEN = "automation_token"
        private const val AUTO_CONNECT_KEY_PREFIX = "ac_key_"

        @Volatile
        private var instance: SecretStore? = null

        /**
         * Building the encrypted file touches the Keystore, so this must be
         * called off the main thread. The instance is shared: MainActivity, the
         * receiver and the trampoline all live in the same process.
         */
        fun get(context: Context): SecretStore {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: SecretStore(openPrefs(context.applicationContext)).also { instance = it }
            }
        }

        // A backup restored elsewhere, or a Keystore invalidated by the system,
        // leaves a file that no longer opens. That is not an error worth
        // propagating: throw it all away and start over with a fresh token, which
        // is exactly what should happen when the data came from another phone.
        private fun openPrefs(context: Context): SharedPreferences =
            runCatching { createPrefs(context) }.getOrElse {
                wipe(context)
                createPrefs(context)
            }

        private fun createPrefs(context: Context): SharedPreferences {
            val masterKey = MasterKey.Builder(context, MASTER_KEY_ALIAS)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context,
                FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }

        private fun wipe(context: Context) {
            runCatching { context.deleteSharedPreferences(FILE_NAME) }
            runCatching {
                KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
                    .deleteEntry(MASTER_KEY_ALIAS)
            }
        }

        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    }
}
