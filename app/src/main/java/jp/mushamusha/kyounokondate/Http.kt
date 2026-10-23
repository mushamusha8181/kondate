package jp.mushamusha.kyounokondate

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

class HttpStatusException(val code: Int) : IllegalStateException("HTTP $code")

object Http {
    private const val MAX_JSON_BYTES = 4 * 1024 * 1024
    private val jsonConnections = ConcurrentHashMap.newKeySet<HttpURLConnection>()
    private val binaryConnections = ConcurrentHashMap.newKeySet<HttpURLConnection>()

    fun postJson(url: String, body: JSONObject, bearer: String, allowedHost: String): JSONObject {
        ensureNotInterrupted()
        val parsed = URI(url).toURL()
        require(parsed.protocol == "https" && parsed.host == allowedHost) { "Blocked host" }
        val conn = (parsed.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 12_000
            readTimeout = 30_000
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer $bearer")
        }
        jsonConnections += conn
        return try {
            ensureNotInterrupted()
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            ensureNotInterrupted()
            readJson(conn)
        } finally {
            jsonConnections -= conn
            conn.disconnect()
        }
    }

    fun getBytes(url: String, allowedDomain: String, maxBytes: Int = 4 * 1024 * 1024): ByteArray {
        ensureNotInterrupted()
        val parsed = URI(url).toURL()
        val host = parsed.host.lowercase()
        val allowed = host == allowedDomain || host.endsWith(".$allowedDomain")
        require(parsed.protocol == "https" && allowed) { "Blocked host" }
        val conn = (parsed.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 10_000
            instanceFollowRedirects = false
        }
        binaryConnections += conn
        return try {
            val code = conn.responseCode
            if (code !in 200..299) throw HttpStatusException(code)
            readLimited(conn.inputStream, maxBytes)
        } finally {
            binaryConnections -= conn
            conn.disconnect()
        }
    }

    fun cancelJsonRequests() {
        jsonConnections.toList().forEach { it.disconnect() }
    }

    fun cancelBinaryRequests() {
        binaryConnections.toList().forEach { it.disconnect() }
    }

    private fun readJson(conn: HttpURLConnection): JSONObject {
        ensureNotInterrupted()
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.let { String(readLimited(it, MAX_JSON_BYTES), Charsets.UTF_8) }.orEmpty()
        ensureNotInterrupted()
        if (code !in 200..299) throw HttpStatusException(code)
        return JSONObject(text)
    }

    private fun readLimited(input: InputStream, maxBytes: Int): ByteArray {
        BufferedInputStream(input).use { stream ->
            val out = ByteArrayOutputStream(minOf(maxBytes, 32 * 1024))
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                ensureNotInterrupted()
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxBytes) throw IllegalStateException("Response too large")
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }
    }

    private fun ensureNotInterrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Cancelled")
    }
}
