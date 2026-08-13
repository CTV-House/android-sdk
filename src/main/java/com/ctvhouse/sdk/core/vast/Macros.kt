package com.ctvhouse.sdk.core.vast

import com.ctvhouse.sdk.core.identity.Device
import java.net.URLEncoder
import kotlin.random.Random

/**
 * Macro substitution for every URL the library sends out: the tag, tracking pixels, the creative,
 * and the ClickThrough behind the QR.
 *
 * Both spellings an ad server may use are accepted — `${IFA}` and the VAST `[IFA]` — along with
 * the aliases the VAST catalog defines for a value, so a tracker written against the standard and
 * a tag written by an ad server resolve without the host mapping anything by hand.
 *
 * Values are URL-encoded. A known macro with no value collapses to nothing, so a leftover
 * `${IFA}` never reaches the server. An unknown name is left as it was: it belongs to whoever
 * wrote the URL, not to us.
 */
internal object Macros {

    /**
     * Scanned by hand rather than with a `Regex`: the engine behind `java.util.regex` differs
     * between the JVM the tests run on and Android, and a URL is walked once per pixel anyway.
     */
    fun expand(
        url: String,
        device: Device,
        timestampMs: Long,
        random: String = cacheBuster(),
    ): String {
        if (url.isEmpty() || (!url.contains("\${") && !url.contains('['))) return url
        val out = StringBuilder(url.length + PADDING)
        var i = 0
        while (i < url.length) {
            val char = url[i]
            val opening = when {
                char == '$' && i + 1 < url.length && url[i + 1] == '{' -> 2
                char == '[' -> 1
                else -> 0
            }
            val name = if (opening == 0) {
                null
            } else {
                macroName(url, from = i + opening, closing = if (opening == 2) '}' else ']')
            }
            val value = name?.let { valueOf(it.uppercase(), device, timestampMs, random) }
            if (value == null) {
                // Not a macro, or a name that belongs to whoever wrote the URL: copy it as it is.
                out.append(char)
                i++
            } else {
                out.append(encode(value))
                i += opening + name.length + 1
            }
        }
        return out.toString()
    }

    /** The token between the delimiters, or null when it is not shaped like a macro name. */
    private fun macroName(url: String, from: Int, closing: Char): String? {
        var i = from
        while (i < url.length) {
            val char = url[i]
            when {
                char == closing -> return if (i > from) url.substring(from, i) else null
                char == '_' || char in 'A'..'Z' || char in 'a'..'z' -> i++
                else -> return null
            }
        }
        return null
    }

    fun cacheBuster(random: Random = Random.Default): String =
        random.nextInt(100_000_000).toString().padStart(8, '0')

    private fun valueOf(
        name: String,
        device: Device,
        timestampMs: Long,
        random: String,
    ): String? = when (name) {
        "IFA" -> device.ifa
        "IFATYPE" -> device.ifaType
        "LIMITADTRACKING", "LMT", "DNT" -> if (device.limitAdTracking) "1" else "0"
        "USER_AGENT", "DEVICEUA", "CLIENTUA" -> device.userAgent
        "IP", "DEVICEIP" -> device.ip
        "RANDOM", "CACHEBUSTING", "CACHEBUSTER" -> random
        "TIMESTAMP" -> timestampMs.toString()
        "APPBUNDLE" -> device.appBundle
        "APPNAME" -> device.appName
        "APPVERSION" -> device.appVersion
        "DEVICEMAKE" -> device.make
        "DEVICEMODEL" -> device.model
        "OS" -> "android"
        "OSVERSION" -> device.osVersion
        "DEVICETYPE" -> device.type.rtbCode.toString()
        "CONNECTIONTYPE" -> device.connection.rtbCode.toString()
        "LANGUAGE" -> device.language
        "DEVICEW" -> device.widthPx.positiveOrEmpty()
        "DEVICEH" -> device.heightPx.positiveOrEmpty()
        // VAST spells the pair width,height.
        "PLAYERSIZE" -> if (device.widthPx > 0 && device.heightPx > 0) {
            "${device.widthPx},${device.heightPx}"
        } else {
            ""
        }
        else -> null
    }

    private fun Int.positiveOrEmpty(): String = if (this > 0) toString() else ""

    private fun encode(value: String): String =
        if (value.isEmpty()) {
            ""
        } else {
            URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
        }

    private const val PADDING = 32
}
