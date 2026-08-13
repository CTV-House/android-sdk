package com.ctvhouse.sdk.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Downsampling is what keeps an oversized creative from exhausting host memory. */
class BitmapSampleSizeTest {

    @Test
    fun smallImage_isDecodedAsIs() {
        assertEquals(1, sampleSize(1920, 1080))
    }

    @Test
    fun exactlyAtBudget_isDecodedAsIs() {
        assertEquals(1, sampleSize(2000, 2000))
    }

    @Test
    fun oversizedImage_isHalvedUntilItFits() {
        assertEquals(2, sampleSize(4000, 2000))
        assertEquals(4, sampleSize(8000, 6000))
        assertEquals(16, sampleSize(30_000, 30_000))
    }

    @Test
    fun sampledPixelsAlwaysFitTheBudget() {
        val sizes = listOf(1, 999, 4096, 12_000, 65_536)
        for (width in sizes) {
            for (height in sizes) {
                val sample = sampleSize(width, height)
                val pixels = (width.toLong() / sample) * (height.toLong() / sample)
                assertTrue("$width x $height -> $sample", pixels <= BUDGET)
            }
        }
    }

    @Test
    fun unknownBounds_fallBackToNoSampling() {
        assertEquals(1, sampleSize(-1, -1))
        assertEquals(1, sampleSize(0, 0))
    }

    private fun sampleSize(width: Int, height: Int): Int =
        UrlBitmapLoader.sampleSize(width, height, BUDGET)

    private companion object {
        const val BUDGET = UrlBitmapLoader.MAX_PIXELS
    }
}
