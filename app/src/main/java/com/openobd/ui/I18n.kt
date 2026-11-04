package com.openobd.ui

import android.content.Context
import kotlinx.serialization.json.Json
import java.util.Locale

/**
 * Lightweight translation layer keyed by the English text (gettext style). Tables live in
 * assets/i18n/<lang>.json; a missing entry falls back to English. Templates use %1$s, %2$s … so
 * translations can reorder arguments.
 */
object I18n {
    val languages = listOf(
        "system" to "System default", "en" to "English", "es" to "Español", "de" to "Deutsch", "fr" to "Français",
        "it" to "Italiano", "pt" to "Português (Brasil)", "nl" to "Nederlands", "pl" to "Polski", "tr" to "Türkçe",
        "ru" to "Русский", "zh" to "中文（简体）",
    )

    /** English name of a language, for prompts to the AI assistant. */
    fun englishName(code: String): String = mapOf(
        "en" to "English", "es" to "Spanish (Spain)", "de" to "German", "fr" to "French", "it" to "Italian", "pt" to "Brazilian Portuguese",
        "nl" to "Dutch", "pl" to "Polish", "tr" to "Turkish", "ru" to "Russian", "zh" to "Simplified Chinese",
    )[code] ?: "the user's language"

    /** Locale for speech and formatting of an app language. */
    fun localeOf(code: String): Locale = when (code) {
        "es" -> Locale("es", "ES"); "pt" -> Locale("pt", "BR"); "zh" -> Locale.SIMPLIFIED_CHINESE; "en" -> Locale.UK
        "system" -> Locale.getDefault()
        else -> Locale(code)
    }

    @Volatile var language: String = "en"
        private set
    @Volatile private var table: Map<String, String> = emptyMap()

    /** "system" follows the phone; a phone language the app does not have falls back to English. */
    fun resolve(setting: String): String {
        val lang = if (setting == "system") Locale.getDefault().language else setting
        return if (languages.any { it.first == lang }) lang else "en"
    }

    fun load(context: Context, setting: String) {
        val lang = resolve(setting)
        language = lang
        table = if (lang == "en") emptyMap() else runCatching {
            context.assets.open("i18n/$lang.json").bufferedReader().use { Json.decodeFromString<Map<String, String>>(it.readText()) }
        }.getOrDefault(emptyMap())
    }

    fun has(text: String) = table.containsKey(text)

    fun translate(text: String, args: Array<out Any?>): String {
        val t = table[text] ?: text
        return if (args.isEmpty()) t else runCatching { String.format(t, *args) }.getOrElse { String.format(text, *args) }
    }
}

/** Translates [text] (English source) to the current language, formatting %1$s-style arguments. */
fun tr(text: String, vararg args: Any?): String = I18n.translate(text, args)

/** Translates a code category such as "Manufacturer-specific code. Powertrain - Ignition system or misfire". */
fun trCategory(category: String): String {
    var rest = category
    val prefix = StringBuilder()
    Regex("^Manufacturer-specific \\((.+?)\\)\\. ").find(rest)?.let { m -> prefix.append(tr("Manufacturer-specific (%1\$s). ", m.groupValues[1])); rest = rest.substring(m.range.last + 1) }
    if (rest.startsWith("Manufacturer-specific code. ")) { prefix.append(tr("Manufacturer-specific code. ")); rest = rest.removePrefix("Manufacturer-specific code. ") }
    return prefix.toString() + rest.split(" - ").joinToString(" - ") { tr(it) }
}
