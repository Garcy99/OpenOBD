package com.openobd.core.specs

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** java.net implementation of [Http] (no extra dependencies). */
class UrlHttp(private val userAgent: String = "OpenOBD (open-source OBD-II app)") : Http {
    private suspend fun call(url: String, method: String, body: String?, contentType: String?, headers: Map<String, String>): String =
        withContext(Dispatchers.IO) {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.requestMethod = method
                c.connectTimeout = 15_000
                c.readTimeout = 30_000
                c.setRequestProperty("User-Agent", userAgent)
                headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
                if (body != null) {
                    c.doOutput = true
                    contentType?.let { c.setRequestProperty("Content-Type", it) }
                    c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val code = c.responseCode
                val stream = if (code in 200..299) c.inputStream else c.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                // Error pages (HTML from gateways and firewalls) are not shown to people; a short JSON / text reason is.
                if (code !in 200..299) throw HttpException(code, "HTTP $code" +
                    text.trim().takeIf { it.isNotEmpty() && !it.startsWith("<") }?.let { ": " + it.take(160) }.orEmpty())
                text
            } catch (e: java.io.IOException) {
                throw SpecsException("No internet connection or the service is unavailable (${e.javaClass.simpleName})")
            } finally {
                c.disconnect()
            }
        }

    override suspend fun get(url: String, headers: Map<String, String>) = call(url, "GET", null, null, headers)
    override suspend fun post(url: String, body: String, contentType: String, headers: Map<String, String>) = call(url, "POST", body, contentType, headers)
}
