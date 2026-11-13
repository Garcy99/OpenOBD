package com.openobd.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.OutputStream

/**
 * A backup of everything (cars, trips with their routes, fuel log, maintenance, values found, settings) written by
 * itself once a week to a folder the user chose (on the phone, or a cloud folder such as Google Drive), keeping the
 * last few. It lives apart from the settings so a restore does not bring another phone's folder along.
 */
class AutoBackup(
    private val prefsSource: android.content.SharedPreferences,
    /** Writes a full backup; returns the number of files in it. */
    private val write: suspend (OutputStream) -> Int,
    /** The folder for [Uri] (Android's storage access framework; tests use a plain directory). */
    private val folderOf: (String) -> Folder,
) {
    /** Where backups go: create a file, list ours, delete one. */
    interface Folder {
        fun create(name: String): OutputStream
        fun names(): List<String>
        fun delete(name: String)
    }

    data class Status(val enabled: Boolean, val folder: String?, val lastAt: Long, val lastFiles: Int, val lastError: String?)

    private val prefs = prefsSource
    private val mutex = Mutex()
    private val _status = MutableStateFlow(read())
    val status: StateFlow<Status> = _status

    private fun read() = Status(prefs.getBoolean("on", false), prefs.getString("folder", null), prefs.getLong("at", 0),
        prefs.getInt("files", 0), prefs.getString("error", null))

    fun enable(folder: String) {
        prefs.edit().putBoolean("on", true).putString("folder", folder).remove("error").apply()
        _status.value = read()
    }

    fun disable() { prefs.edit().putBoolean("on", false).apply(); _status.value = read() }

    /** Writes a backup now when it is on and the last one is a week old (or there is none). */
    suspend fun runIfDue(now: Long = System.currentTimeMillis()): Boolean {
        val s = _status.value
        if (!s.enabled || s.folder == null || now - s.lastAt < WEEK_MS) return false
        return runNow(now).isSuccess
    }

    suspend fun runNow(now: Long = System.currentTimeMillis()): Result<Int> = mutex.withLock {
        val folder = _status.value.folder ?: return@withLock Result.failure(IllegalStateException("No folder chosen"))
        val r = runCatching {
            withContext(Dispatchers.IO) {
                val f = folderOf(folder)
                val name = PREFIX + java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date(now)) + ".zip"
                val files = f.create(name).use { write(it) }
                // The last few are kept; older automatic ones go (backups saved by hand are never touched).
                f.names().filter { it.startsWith(PREFIX) }.sortedDescending().drop(KEEP).forEach { runCatching { f.delete(it) } }
                files
            }
        }
        r.onSuccess { n -> prefs.edit().putLong("at", now).putInt("files", n).remove("error").apply() }
            .onFailure { e -> prefs.edit().putString("error", e.message ?: e.javaClass.simpleName).apply() }
        _status.value = read()
        r
    }

    companion object {
        const val WEEK_MS = 7 * 86_400_000L
        const val KEEP = 4
        const val PREFIX = "openobd-auto-"

        fun create(context: Context, write: suspend (OutputStream) -> Int) =
            AutoBackup(context.getSharedPreferences("auto_backup", Context.MODE_PRIVATE), write) { SafFolder(context, Uri.parse(it)) }

        /** Keeps access to a folder the user picked across restarts. */
        fun keepAccess(context: Context, tree: Uri) {
            runCatching { context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        }

        /** A short name for a picked folder ("Download/OpenOBD", "Drive"). */
        fun label(folder: String?): String? = folder?.let { Uri.parse(it).lastPathSegment?.substringAfter(':')?.ifBlank { null } ?: Uri.parse(it).lastPathSegment }

        val texts = listOf(
            "Automatic backup every week", "Choose a folder (on the phone, or Google Drive) and a backup is saved there every week; the last 4 are kept.",
            "Folder: %1\$s", "Last backup: %1\$s", "No backup yet.", "Back up now", "Change folder", "Automatic backup failed: %1\$s",
        )
    }
}

/** A folder picked with the system file picker (it can be in Google Drive or another cloud app). */
class SafFolder(private val context: Context, private val tree: Uri) : AutoBackup.Folder {
    private val resolver get() = context.contentResolver
    private val root: Uri get() = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    override fun create(name: String): OutputStream {
        val doc = DocumentsContract.createDocument(resolver, root, "application/zip", name) ?: error("Cannot create a file in the folder")
        return resolver.openOutputStream(doc) ?: error("Cannot write in the folder")
    }

    private fun children(): List<Pair<String, String>> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val out = ArrayList<Pair<String, String>>()
        resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext()) out += c.getString(0) to c.getString(1)
        }
        return out
    }

    override fun names(): List<String> = children().map { it.second }

    override fun delete(name: String) {
        children().filter { it.second == name }.forEach { (id, _) -> DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, id)) }
    }
}
