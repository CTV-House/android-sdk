package com.ctvhouse.sdk.core.runtime

import android.util.Log as AndroidLog

/**
 * Diagnostic logger.
 *
 * Warnings and failures always reach logcat: the library swallows its own exceptions, so the
 * log line is the only trace an integrator has. [enabled] adds the play-by-play on top.
 */
internal object Log {
    @Volatile
    var enabled: Boolean = false

    fun i(tag: String, message: String) {
        if (enabled) AndroidLog.i(prefix(tag), message)
    }

    fun w(tag: String, message: String) {
        AndroidLog.w(prefix(tag), message)
    }

    fun e(tag: String, message: String, t: Throwable? = null) {
        val full = prefix(tag)
        if (t != null) AndroidLog.e(full, message, t) else AndroidLog.e(full, message)
    }

    /** Keeps library output greppable and distinct from host tags, within the 23-char limit. */
    private fun prefix(tag: String): String = "CtvSdk/$tag".take(MAX_TAG_LENGTH)

    private const val MAX_TAG_LENGTH = 23
}
