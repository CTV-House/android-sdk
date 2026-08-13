package com.ctvhouse.sdk.core.runtime

import com.ctvhouse.sdk.format.Overlay
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackPoolTest {

    @Test
    fun trackPoolSize_clampedForCtv() {
        val size = Overlay.trackPoolSize()
        assertTrue("size=$size", size in 2..6)
    }

    @Test
    fun queueMainPoster_cancelDelayed_dropsPending() {
        val poster = QueueMainPoster()
        var ran = false
        poster.postDelayed(1L) { ran = true }
        poster.cancelDelayed()
        poster.runDelayed()
        assertTrue(!ran)
    }
}
