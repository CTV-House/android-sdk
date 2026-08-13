package com.ctvhouse.sdk.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class HandlerMainPosterTest {

    private val poster = HandlerMainPoster()

    @Test
    fun post_runsOnMainLooper() {
        var ran = false
        poster.post { ran = true }
        assertFalse("work must be queued, not run inline", ran)

        ShadowLooper.idleMainLooper()
        assertTrue(ran)
    }

    @Test
    fun postDelayed_waitsForItsDelay() {
        var ran = false
        poster.postDelayed(250L) { ran = true }

        ShadowLooper.idleMainLooper()
        assertFalse(ran)

        ShadowLooper.idleMainLooper(250L, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertTrue(ran)
    }

    @Test
    fun cancelDelayed_dropsPendingTicks() {
        var ticks = 0
        poster.postDelayed(250L) { ticks++ }
        poster.postDelayed(500L) { ticks++ }

        poster.cancelDelayed()
        ShadowLooper.idleMainLooper(1_000L, java.util.concurrent.TimeUnit.MILLISECONDS)

        assertEquals(0, ticks)
    }

    @Test
    fun cancelAll_dropsImmediateWorkToo() {
        var ran = 0
        poster.post { ran++ }
        poster.postDelayed(250L) { ran++ }

        poster.cancelAll()
        ShadowLooper.idleMainLooper(1_000L, java.util.concurrent.TimeUnit.MILLISECONDS)

        assertEquals(0, ran)
    }

    @Test
    fun failingTask_doesNotReachTheHost() {
        var afterFailure = false
        poster.post { error("boom") }
        poster.post { afterFailure = true }

        ShadowLooper.idleMainLooper()

        assertTrue("the looper must survive a library defect", afterFailure)
    }
}
