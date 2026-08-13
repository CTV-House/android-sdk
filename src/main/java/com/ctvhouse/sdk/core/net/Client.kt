package com.ctvhouse.sdk.core.net

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** Largest ad markup body accepted from a tag or Wrapper hop. */
internal const val MAX_MARKUP_BYTES: Int = 2 * 1024 * 1024

/** Largest creative body (still image) accepted. */
internal const val MAX_CREATIVE_BYTES: Int = 8 * 1024 * 1024

/**
 * Network client for ad markup, creatives, and tracker pings.
 * HTTP is the default transport ([UrlConnectionClient]); other transports can implement [Client].
 *
 * Every method is blocking, returns null instead of throwing, and refuses to buffer
 * more than the caller allows — a hostile or broken response cannot exhaust host memory.
 */
internal interface Client {
    fun fetchText(url: String, timeoutMs: Int, userAgent: String): String?

    fun fetchBytes(
        url: String,
        timeoutMs: Int,
        userAgent: String,
        maxBytes: Int = MAX_CREATIVE_BYTES,
    ): ByteArray?

    fun fireTrackers(urls: List<String>, userAgent: String)
}

/** Default [Client] over `HttpURLConnection`. */
internal class UrlConnectionClient : Client {

    override fun fetchText(url: String, timeoutMs: Int, userAgent: String): String? {
        val bytes = fetchBytes(url, timeoutMs, userAgent, MAX_MARKUP_BYTES) ?: return null
        return bytes.toString(Charsets.UTF_8)
    }

    override fun fetchBytes(
        url: String,
        timeoutMs: Int,
        userAgent: String,
        maxBytes: Int,
    ): ByteArray? {
        var conn: HttpURLConnection? = null
        return try {
            conn = openFollowing(url, timeoutMs, userAgent, accept = "*/*") ?: return null
            if (conn.contentLength > maxBytes) return null
            conn.inputStream?.use { it.readBounded(maxBytes) }
        } catch (_: Throwable) {
            null
        } finally {
            conn?.disconnectQuietly()
        }
    }

    override fun fireTrackers(urls: List<String>, userAgent: String) {
        urls.forEach { url ->
            var conn: HttpURLConnection? = null
            try {
                // Tracking pixel: the status line is enough, the body is never read.
                conn = openFollowing(url, TRACKER_TIMEOUT_MS, userAgent, accept = null)
            } catch (_: Throwable) {
            } finally {
                conn?.disconnectQuietly()
            }
        }
    }

    /**
     * Opens [url] and walks redirects by hand, returning a connection with a 2xx status.
     *
     * `HttpURLConnection` drops a redirect that changes scheme, and ad tags and trackers hop
     * between http and https all the time — following them here is what keeps those hops alive.
     */
    private fun openFollowing(
        url: String,
        timeoutMs: Int,
        userAgent: String,
        accept: String?,
    ): HttpURLConnection? {
        var target = url
        repeat(MAX_REDIRECTS + 1) {
            if (!isWebUrl(target)) return null
            val conn = open(target, timeoutMs, userAgent, accept)
            val next = try {
                val code = conn.responseCode
                if (code !in REDIRECT_CODES) {
                    if (code in 200..299) return conn
                    null
                } else {
                    // A relative Location is legal; resolve it against the hop that sent it.
                    conn.getHeaderField("Location")
                        ?.takeIf { it.isNotBlank() }
                        ?.let { URL(URL(target), it).toString() }
                }
            } catch (_: Throwable) {
                null
            }
            conn.disconnectQuietly()
            target = next ?: return null
        }
        return null
    }

    private fun open(
        url: String,
        timeoutMs: Int,
        userAgent: String,
        accept: String?,
    ): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = false
            useCaches = false
            setRequestProperty("User-Agent", userAgent)
            if (accept != null) setRequestProperty("Accept", accept)
        }

    private fun HttpURLConnection.disconnectQuietly() {
        try {
            disconnect()
        } catch (_: Throwable) {
        }
    }

    private fun isWebUrl(url: String): Boolean {
        if (url.isBlank()) return false
        val scheme = url.substringBefore(':', missingDelimiterValue = "").lowercase()
        return scheme == "http" || scheme == "https"
    }

    /** Reads at most [max] bytes; a longer body is treated as unusable rather than truncated. */
    private fun InputStream.readBounded(max: Int): ByteArray? {
        val out = ByteArrayOutputStream(INITIAL_BUFFER_BYTES.coerceAtMost(max))
        val chunk = ByteArray(CHUNK_BYTES)
        var total = 0
        while (true) {
            val read = read(chunk)
            if (read < 0) break
            total += read
            if (total > max) return null
            out.write(chunk, 0, read)
        }
        return out.toByteArray()
    }

    private companion object {
        const val TRACKER_TIMEOUT_MS = 5_000
        const val CHUNK_BYTES = 16 * 1024
        const val INITIAL_BUFFER_BYTES = 32 * 1024
        const val MAX_REDIRECTS = 5
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}
