package com.cuscus.wifiaudiostreaming

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

object AppLanguage {
    const val SYSTEM = "SYSTEM"
    const val ENGLISH = "en"
    const val CHINESE_SIMPLIFIED = "zh-CN"

    val OPTIONS = listOf(SYSTEM, CHINESE_SIMPLIFIED, ENGLISH)

    fun current(): String {
        val tags = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        if (tags.isBlank()) return SYSTEM
        return when {
            tags.startsWith("zh", ignoreCase = true) -> CHINESE_SIMPLIFIED
            tags.startsWith("en", ignoreCase = true) -> ENGLISH
            else -> SYSTEM
        }
    }

    fun apply(value: String) {
        val locales = when (value) {
            CHINESE_SIMPLIFIED -> LocaleListCompat.forLanguageTags(CHINESE_SIMPLIFIED)
            ENGLISH -> LocaleListCompat.forLanguageTags(ENGLISH)
            else -> LocaleListCompat.getEmptyLocaleList()
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }
}
