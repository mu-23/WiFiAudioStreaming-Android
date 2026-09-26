package com.cuscus.wifiaudiostreaming

/**
 * Selects how internal/system audio is captured on the sender.
 *
 * SHIZUKU is the lab default and never falls back silently.
 * MEDIA_PROJECTION is kept only as an explicit compatibility option.
 */
object InternalAudioBackend {
    const val SHIZUKU = "SHIZUKU"
    const val MEDIA_PROJECTION = "MEDIA_PROJECTION"

    fun normalize(value: String?): String = when (value?.uppercase()) {
        MEDIA_PROJECTION -> MEDIA_PROJECTION
        else -> SHIZUKU
    }
}
