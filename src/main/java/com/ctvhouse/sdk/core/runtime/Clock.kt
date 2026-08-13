package com.ctvhouse.sdk.core.runtime

/** Time source (testable). */
internal fun interface Clock {
    fun currentTimeMillis(): Long
}

internal object SystemClock : Clock {
    override fun currentTimeMillis(): Long = System.currentTimeMillis()
}
