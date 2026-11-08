package com.openobd.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** A file in the app's private Drive folder; [version] changes on every upload (to notice another device's). */
data class RemoteFile(val id: String, val name: String, val version: Long, val size: Long = 0)

/** The app's own hidden folder in the user's Google Drive (appDataFolder): only OpenOBD can see it. */
interface DriveApi {
    suspend fun list(): List<RemoteFile>
    suspend fun download(id: String): ByteArray
    suspend fun create(name: String, bytes: ByteArray): RemoteFile
    suspend fun update(id: String, bytes: ByteArray): RemoteFile
    suspend fun delete(id: String)
    /** The signed-in account's e-mail address. */
    suspend fun account(): String?
}

class DriveAuthException(message: String) : IOException(message)

/** Drive REST v3 over HTTPS with an OAuth access token for the drive.appdata scope. */
class HttpDriveApi(private val token: String) : DriveApi {
    private val json = Json { ignoreUnknownKeys = true }
    private val api = "https://www.googleapis.com/drive/v3"
    private val upload = "https://www.googleapis.com/upload/drive/v3"
    private val fields = "id,name,version,size"

    private suspend fun call(method: String, url: String, body: ByteArray? = null, contentType: String? = null): ByteArray = withContext(Dispatchers.IO) {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000; c.readTimeout = 60_000
            // HttpURLConnection has no PATCH: Google APIs accept POST with this override.
            if (method == "PATCH") { c.requestMethod = "POST"; c.setRequestProperty("X-HTTP-Method-Override", "PATCH") } else c.requestMethod = method
            c.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                c.doOutput = true
                contentType?.let { c.setRequestProperty("Content-Type", it) }
                c.outputStream.use { it.write(body) }
            }
            val code = c.responseCode
            if (code == 401 || code == 403) throw DriveAuthException("Google Drive refused access ($code)")
            if (code !in 200..299) throw IOException("Google Drive error $code: " + (c.errorStream?.use { String(it.readBytes()) }?.take(300) ?: ""))
            c.inputStream.use { it.readBytes() }
        } finally { c.disconnect() }
    }

    private fun file(o: JsonObject) = RemoteFile(
        (o["id"] as JsonPrimitive).content, (o["name"] as? JsonPrimitive)?.content.orEmpty(),
        (o["version"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0, (o["size"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0,
    )

    override suspend fun list(): List<RemoteFile> {
        val out = mutableListOf<RemoteFile>()
        var page: String? = null
        do {
            val url = "$api/files?spaces=appDataFolder&pageSize=1000&fields=" + URLEncoder.encode("nextPageToken,files($fields)", "UTF-8") +
                (page?.let { "&pageToken=" + URLEncoder.encode(it, "UTF-8") } ?: "")
            val o = json.parseToJsonElement(String(call("GET", url))).jsonObject
            (o["files"] as? JsonArray)?.forEach { out += file(it.jsonObject) }
            page = (o["nextPageToken"] as? JsonPrimitive)?.content
        } while (page != null)
        return out
    }

    override suspend fun download(id: String): ByteArray = call("GET", "$api/files/$id?alt=media")

    override suspend fun create(name: String, bytes: ByteArray): RemoteFile {
        val boundary = "openobd" + System.nanoTime()
        val meta = buildJsonObject { put("name", name); put("parents", buildJsonArray { add(JsonPrimitive("appDataFolder")) }) }.toString()
        val body = ("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n--$boundary\r\nContent-Type: application/json\r\n\r\n").toByteArray() +
            bytes + "\r\n--$boundary--\r\n".toByteArray()
        return file(json.parseToJsonElement(String(call("POST", "$upload/files?uploadType=multipart&fields=$fields", body, "multipart/related; boundary=$boundary"))).jsonObject)
    }

    override suspend fun update(id: String, bytes: ByteArray): RemoteFile =
        file(json.parseToJsonElement(String(call("PATCH", "$upload/files/$id?uploadType=media&fields=$fields", bytes, "application/json"))).jsonObject)

    override suspend fun delete(id: String) { call("DELETE", "$api/files/$id") }

    override suspend fun account(): String? = runCatching {
        val o = json.parseToJsonElement(String(call("GET", "$api/about?fields=user(emailAddress)"))).jsonObject
        ((o["user"] as? JsonObject)?.get("emailAddress") as? JsonPrimitive)?.content
    }.getOrNull()
}

/** An in-memory Drive for tests and the demo. */
class MemoryDriveApi : DriveApi {
    val files = LinkedHashMap<String, Pair<RemoteFile, ByteArray>>()
    private var next = 0
    var email: String? = "driver@example.com"

    override suspend fun list() = files.values.map { it.first }
    override suspend fun download(id: String) = files[id]?.second ?: throw IOException("No file $id")
    override suspend fun create(name: String, bytes: ByteArray): RemoteFile =
        RemoteFile("f${next++}", name, 1, bytes.size.toLong()).also { files[it.id] = it to bytes }
    override suspend fun update(id: String, bytes: ByteArray): RemoteFile {
        val old = files[id]?.first ?: throw IOException("No file $id")
        return old.copy(version = old.version + 1, size = bytes.size.toLong()).also { files[id] = it to bytes }
    }
    override suspend fun delete(id: String) { files.remove(id) }
    override suspend fun account() = email
}
