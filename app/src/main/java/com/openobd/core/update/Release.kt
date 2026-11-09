package com.openobd.core.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** A published version of the app on GitHub, with the APK to install and the release notes. */
data class Release(
    val version: String,
    val apkUrl: String,
    val apkSize: Long,
    val notes: String,
    val page: String,
) {
    /** True when this release is newer than [current] (e.g. "1.16.1" vs "1.16.0"). */
    fun isNewerThan(current: String): Boolean = compareVersions(version, current) > 0

    /**
     * The notes in the user's language when the release has them (it carries "What's new" in English and
     * "Novedades" in Spanish), without the install instructions.
     */
    fun notesFor(language: String): String {
        val sections = notes.split(Regex("(?m)^## ")).filter { it.isNotBlank() }.associate { s ->
            val title = s.substringBefore('\n').trim(); title to s.substringAfter('\n').trim()
        }
        val wanted = if (language == "es") sections.entries.firstOrNull { it.key.startsWith("Novedades") }
            else sections.entries.firstOrNull { it.key.startsWith("What's new") }
        return (wanted ?: sections.entries.firstOrNull())?.value?.trim().orEmpty()
    }

    companion object {
        /** The latest release of the repository (GitHub API "releases/latest"), or null if it has no APK. */
        fun parse(text: String): Release? {
            val o = Json.parseToJsonElement(text).jsonObject
            if ((o["prerelease"] as? JsonPrimitive)?.content == "true" || (o["draft"] as? JsonPrimitive)?.content == "true") return null
            val tag = (o["tag_name"] as? JsonPrimitive)?.content ?: return null
            val apk = (o["assets"] as? JsonArray).orEmpty().map { it.jsonObject }.firstOrNull { a ->
                (a["name"] as? JsonPrimitive)?.content?.let { it.startsWith("OpenOBD") && it.endsWith(".apk") } == true
            } ?: return null
            return Release(
                version = tag.removePrefix("v"),
                apkUrl = (apk["browser_download_url"] as? JsonPrimitive)?.content ?: return null,
                apkSize = (apk["size"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0,
                notes = (o["body"] as? JsonPrimitive)?.content.orEmpty(),
                page = (o["html_url"] as? JsonPrimitive)?.content.orEmpty(),
            )
        }

        /** Numeric comparison of dotted versions; anything after "-" (e.g. "-debug") is ignored. */
        fun compareVersions(a: String, b: String): Int {
            fun parts(v: String) = v.trim().removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
            val x = parts(a); val y = parts(b)
            for (i in 0 until maxOf(x.size, y.size)) {
                val d = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
                if (d != 0) return d
            }
            return 0
        }
    }
}
