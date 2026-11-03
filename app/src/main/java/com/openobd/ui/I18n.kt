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
    val languages = listOf("system" to "System default", "en" to "English", "es" to "Español")

    @Volatile var language: String = "en"
        private set
    @Volatile private var table: Map<String, String> = emptyMap()

    fun resolve(setting: String): String = if (setting == "system") Locale.getDefault().language else setting

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
