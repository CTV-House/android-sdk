package com.ctvhouse.sdk.manual

import android.content.Context
import android.graphics.Bitmap
import com.ctvhouse.sdk.core.media.BitmapLoader
import com.ctvhouse.sdk.core.net.Client
import com.ctvhouse.sdk.core.player.FakeVideoPlayer
import com.ctvhouse.sdk.core.runtime.Clock
import com.ctvhouse.sdk.core.runtime.QueueMainPoster
import com.ctvhouse.sdk.core.ui.Ui
import com.ctvhouse.sdk.format.TriggerRoll
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.check
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong

/**
 * The switch format is opened by something the viewer did in the app, so what matters is that one
 * action produces one show, that a second action while the ad is up is ignored, and that leaving
 * the screen takes it down without pretending the creative played out.
 */
class SwitchRollAdTest {

    private lateinit var ui: Ui
    private lateinit var client: Client
    private lateinit var bitmaps: BitmapLoader
    private lateinit var clock: FakeClock
    private lateinit var videoPlayer: FakeVideoPlayer
    private val io = Executor { it.run() }
    private val lifecycle = mutableListOf<String>()
    private val tracking = mutableListOf<String>()
    private val errors = mutableListOf<String>()

    @Before
    fun setUp() {
        lifecycle.clear()
        tracking.clear()
        errors.clear()
        ui = mock()
        whenever(ui.videoSurface()).thenReturn(mock())
        client = mock()
        bitmaps = mock()
        whenever(bitmaps.load(any(), any(), any())).thenReturn(mock<Bitmap>())
        clock = FakeClock(1_000L)
        videoPlayer = FakeVideoPlayer()
    }

    @Test
    fun pickingACardShowsTheCreative() {
        respondWith("vast/pauseroll_video_only.xml")

        val ad = createAd().setTagUrl("https://tag.example/vast").attach()
        ad.show("card")

        assertEquals(listOf("open"), lifecycle)
        assertTrue(tracking.contains("impression"))
        verify(ui).showVideo(any())
        ad.detach()
    }

    /** The action reaches the tag fetch, so a host can tell its placements apart in the logs. */
    @Test
    fun theActionIsCarriedIntoTheRequest() {
        respondWith("vast/pauseroll_video_only.xml")

        createAd()
            .setTagUrl("https://tag.example/vast?slot=\${APPBUNDLE}")
            .attach()
            .show("channel_switch")

        verify(client).fetchText(check { url -> assertFalse(url.contains("\${")) }, any(), any())
    }

    @Test
    fun aSecondActionWhileTheAdIsUpIsIgnored() {
        respondWith("vast/pauseroll_video_only.xml")

        val ad = createAd().setTagUrl("https://tag.example/vast").attach()
        ad.show("card")
        ad.show("card")

        verify(client, times(1)).fetchText(any(), any(), any())
        assertEquals(listOf("open"), lifecycle)
    }

    /** Once the ad is gone, the next card the viewer picks is a new opportunity. */
    @Test
    fun theNextActionAfterASkipGetsItsOwnAd() {
        respondWith("vast/pauseroll_video_only.xml")

        val ad = createAd()
            .setTagUrl("https://tag.example/vast")
            .setSkipOffsetSeconds(5)
            .attach()
        ad.show("card")
        clock.now += 5_000L
        ad.onSkipFromUi()
        assertTrue(tracking.contains("skip"))

        ad.show("card")

        verify(client, times(2)).fetchText(any(), any(), any())
        assertEquals(2, lifecycle.count { it == "open" })
    }

    @Test
    fun dismissTakesTheOverlayDownAndDoesNotClaimACompletedCreative() {
        respondWith("vast/pauseroll_video_only.xml")

        val ad = createAd().setTagUrl("https://tag.example/vast").attach()
        ad.show("card")
        ad.dismiss()

        assertTrue(lifecycle.contains("closed"))
        assertFalse(tracking.contains("complete"))
        assertTrue(videoPlayer.releases >= 1)
    }

    @Test
    fun aBannerActionIsServedAsAStill() {
        respondWith("vast/pauseroll_no_media.xml")

        val ad = createAd().setTagUrl("https://tag.example/vast").attach()
        ad.show("card")

        verify(ui).showImage(anyOrNull(), any())
        verify(ui, never()).showVideo(any())
        ad.detach()
    }

    @Test
    fun withoutATagTheHostGetsAnError() {
        createAd().attach().show("card")

        assertTrue(errors.first().contains("tagUrl"))
        assertTrue(lifecycle.isEmpty())
    }

    @Test
    fun detachReleasesTheOverlayAndThePlayer() {
        respondWith("vast/pauseroll_video_only.xml")
        val ad = createAd().setTagUrl("https://tag.example/vast").attach()
        ad.show("card")

        ad.detach()

        verify(ui).detach()
        verify(ui).release()
        assertTrue(videoPlayer.releases >= 1)
    }

    private fun respondWith(fixture: String) {
        whenever(client.fetchText(any(), any(), any())).thenReturn(resource(fixture))
    }

    private fun createAd(): SwitchRollAd {
        val ad = SwitchRollAd(
            appContext = mock<Context>(),
            ui = ui,
            client = client,
            bitmapLoader = bitmaps,
            videoPlayer = videoPlayer,
            clock = clock,
            mainPoster = QueueMainPoster(),
            io = io,
            track = io,
        )
        ad.setListener(object : TriggerRoll.Listener {
            override fun onOpen() {
                lifecycle.add("open")
            }

            override fun onClose() {
                lifecycle.add("closed")
            }

            override fun onNoAd() {
                lifecycle.add("no_ad")
            }

            override fun onError(message: String) {
                errors.add(message)
            }

            override fun onImpression(urls: List<String>) {
                tracking.add("impression")
            }

            override fun onSkip(urls: List<String>) {
                tracking.add("skip")
            }

            override fun onComplete(urls: List<String>) {
                tracking.add("complete")
            }
        })
        return ad
    }

    private fun resource(path: String): String =
        requireNotNull(javaClass.classLoader!!.getResourceAsStream(path))
            .bufferedReader()
            .use { it.readText() }

    private class FakeClock(start: Long) : Clock {
        private val value = AtomicLong(start)
        var now: Long
            get() = value.get()
            set(v) {
                value.set(v)
            }

        override fun currentTimeMillis(): Long = value.get()
    }
}
