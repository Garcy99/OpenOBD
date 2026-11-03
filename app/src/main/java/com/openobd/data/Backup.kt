package com.openobd.data

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * One-file backup of everything the user created: settings (without API keys), garage (cars, maintenance, fuel,
 * trips, health), scan history, GPS tracks and CSV logs. Written to / read from any stream, so the Android picker
 * can put it on the phone, an SD card or Google Drive.
 */
object Backup {
    const val MANIFEST = "openobd-backup.json"
    private const val SETTINGS = "settings.json"
    private val topFiles = listOf("garage.json", "scan_history.json")
    private val dirs = mapOf("tracks" to "json", "logs" to "csv")
    private val safeName = Regex("^[A-Za-z0-9_.-]{1,120}$")

    class Contents(val settingsJson: String?, val files: Int)

    fun write(out: OutputStream, filesDir: File, settingsJson: String, appVersion: String, now: Long = System.currentTimeMillis()): Int {
        var count = 0
        ZipOutputStream(out).use { zip ->
            fun put(name: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry(); count++ }
            put(MANIFEST, """{"format":1,"app":"OpenOBD","version":"$appVersion","created":$now}""".toByteArray())
            put(SETTINGS, settingsJson.toByteArray())
            for (name in topFiles) File(filesDir, name).takeIf { it.isFile }?.let { put(name, it.readBytes()) }
            for ((dir, ext) in dirs) File(filesDir, dir).listFiles { f -> f.isFile && f.extension == ext }?.forEach { put("$dir/${it.name}", it.readBytes()) }
        }
        return count
    }

    /**
     * Restores a backup into [filesDir], replacing garage, history, tracks and logs. Returns the settings JSON for the
     * caller to apply. Throws if the stream is not an OpenOBD backup. Entry names are checked, so a crafted file
     * cannot write outside the app's folders.
     */
    fun read(input: InputStream, filesDir: File): Contents {
        val staged = linkedMapOf<String, ByteArray>()
        var manifest = false
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.isDirectory) continue
                val bytes = zip.readBytes()
                if (bytes.size > 50 * 1024 * 1024) throw IllegalArgumentException("File too large in backup: ${e.name}")
                staged[e.name] = bytes
                if (e.name == MANIFEST) manifest = String(bytes).contains("\"app\":\"OpenOBD\"")
            }
        }
        if (!manifest) throw IllegalArgumentException("This file is not an OpenOBD backup.")
        var files = 0
        for ((name, bytes) in staged) {
            val parts = name.split('/')
            val target = when {
                parts.size == 1 && name in topFiles -> File(filesDir, name)
                parts.size == 2 && parts[0] in dirs && safeName.matches(parts[1]) && parts[1].endsWith("." + dirs.getValue(parts[0])) ->
                    File(File(filesDir, parts[0]).apply { mkdirs() }, parts[1])
                else -> null
            } ?: continue
            val tmp = File(target.parentFile, target.name + ".restoring")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
            files++
        }
        return Contents(staged[SETTINGS]?.let { String(it) }, files)
    }
}
