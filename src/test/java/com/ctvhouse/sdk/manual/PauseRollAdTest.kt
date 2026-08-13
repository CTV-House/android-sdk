package com.ctvhouse.sdk.manual

import android.content.Context
import android.graphics.Bitmap
import com.ctvhouse.sdk.core.media.BitmapLoader
import com.ctvhouse.sdk.core.net.Client
import com.ctvhouse.sdk.core.player.FakeVideoPlayer
import com.ctvhouse.sdk.core.runtime.Clock
import com.ctvhouse.sdk.core.runtime.QueueMainPoster
import com.ctvhouse.sdk.core.ui.Chrome
import com.ctvhouse.sdk.core.ui.Ui
import com.ctvhouse.sdk.format.TriggerRoll
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.check
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong

class PauseRollAdTest {

    private lateinit var ui: Ui
    private lateinit var client: Client
    private lateinit var bitmaps: BitmapLoader
    private lateinit var clock: FakeClock
    private lateinit var main: QueueMainPoster
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
        whenever(ui.videoSurface()).thenReturn(null)
        client = mock()
        bitmaps = mock()
        whenever(bitmaps.load(any(), any(), any())).thenReturn(mock<Bitmap>())
        clock = FakeClock(1_000L)
        main = QueueMainPoster()
        videoPlayer = FakeVideoPlayer()
    }

    @Test
    fun pause_fillWithBothCreatives_showsCompanionNotVideo() {
        whenever(client.fetchText(any(), any(), any()))
            .thenReturn(resource("vast/pauseroll_fill.xml"))
        whenever(ui.videoSurface()).thenReturn(mock())

        val ad = createAd().setTagUrl("https://tag.example/vast").attach().trigger()

        assertEquals(listOf("open"), lifecycle)
        verify(ui).showImage(anyOrNull(), any())
        verify(ui).revealChrome()
        verify(ui, never()).showVideo(any())
        assertNull("video MediaFile must not play under a still", videoPlayer.playedUrl)
        ad.detach()
    }

    @Test
    fun companionWithAudioFile_playsSoundtrackBehindTheStill() {
        whenever(client.fetchText(any(), any(), any()))
            .thenReturn(resource("vast/pauseroll_companion_audio.xml"))

        val ad = createAd().setTagUrl("https://tag.example/vast").attach().trigger()

        verify(ui).showImage(anyOrNull(), any())
        verify(ui, never()).showVideo(any())
        assertEquals("https://example.com/ad.mp3", videoPlayer.playedUrl)
        assertTrue(tracking.contains("start"))
        assertEquals(listOf("open"), lifecycle)
        ad.detach()
    }

    /** The soundtrack is how long the still was meant to be watched, so its end ends the show. */
    @Test
    fun soundtrackEnd_closesTheStillByDefault() {
        whenever(client.fetchText(any(), any(), any()))
            .thenReturn(resource("vast/pauseroll_companion_audio.xml"))
        val ad = createAd().setTagUrl("https://tag.example/vast").attach().trigger()

        videoPlayer.finish()

        assertEquals(listOf("open", "closed"), lifecycle)
        assertNull(videoPlayer.playedUrl)
        ad.detach()
    }

    @Test
    fun soundtrackEnd_leavesTheStillUpWhenTheHostAsksForThat() {
        whenever(client.fetchText(any(), any(), any()))
            .thenReturn(resource("vast/pauseroll_companion_audio.xml"))
        val ad = createAd()
            .setTagUrl("https://tag.example/vast")
            .setDismissOnCreativeEnd(false)
            .attach()
            .trigger()

        videoPlayer.finish()

        assertEquals(listOf("open"), lifecycle)
        assertNull("the soundtrack still has to stop", videoPlayer.playedUrl)
        ad.detach()
    }

    /** A still has no playback to end, so the Duration in the same response ends it. */
    @Test
    fun still_closesAfterTheDurationTheResponseCarries() {
        whenever(client.fetchText(any(), any(), any()))
            .thenReturn(resource("vast/pauseroll_fill.xml"))

        val ad = createAd().setTagUrl("https://tag.example/vast").attach().trigger()
        assertEquals(listOf("open"), lifecycle)

        clock.now += 9_000L
        tick()
        assertEquals("closed too early: $lifecycle", listOf("open"), lifecycle)

        clock.now += 1_500L
        tick()

        assertTrue(lifecycle.contains("closed"))
        assertFalse("a still never played out", tracking.contains("complete"))
        ad.detach()
    }

    @Test
    fun still_staysWhenTheHostTurnsOffDismissalAtCreativeEnd() {
        whenever(client.fetchText(any(), any(), any()))
            .thenReturn(resource("vast/pauseroll_fill.xml"))

        val ad = createAd()
            .setTagUrl("https://tag.example/vast")
            .setDismissOnCreativeEnd(false)
            .attach()
            .trigger()

        clock.now += 60_000L
        tick()

        assertEquals(listOf("open"), lifecycle)
        ad.detach()
    }

    /** Without a duration in the response, the host's own display time is the only signal. */
    @Test
    fun still_withoutADurationUsesTheConfiguredDisplayTime() {
        whenever(client.fetchText(any(), any(), any()))
            .thenReturn(resource("vast/pauseroll_no_media.xml"))

        val ad = createAd()
            .setTagUrl("https://tag.example/vast")
            .setBannerDurationSeconds(8)
            .attach()
            .trigger()
        assertEquals(listOf("open"), lifecycle)

        clock.now += 8_000L
        tick()

        assertTrue(lifecycle.contains("closed"))
        ad.detach()
    }

    @Test
    fun still_withNothingToTimeWaitsForTheViewer() {
        whenever(client.fetchText(any(), any(), any()))
            .thenReturn(resource("vast/pauseroll_no_media.xml"))

        val ad = createAd().setTagUrl("https://tag.example/vast").attach().trigger()

        clock.now += 120_000L
        tick()

        assertEquals(listOf("open"), lifecycle)
        ad.detach()
    }

    @Test
    fun companionDownloadFails_fallsBackToLinearWhenPresent() {
        whenever(client.fetchText(any(), any(), any()))
            .thenReturn(resource("vast/pauseroll_fill.xml"))
        whenever(bitmaps.load(any(), any(), any())).thenReturn(null)
        whenever(ui.videoSurface()).thenReturn(mock())

        createAd().setTagUrl("https://tag.example/vast").attach().trigger()

        verify(ui).showVideo(any())
        verify(ui, never()).showImage(anyOrNull(), any())
        assertEquals(listOf("open"), lifecycle)
        assertTrue(tracking.contains("impression"))
        assertTrue(tracking.contains("start"))
    }

    @Test
    fun pause_companionFill_showsImage() {
        whenever(client.fetchText(any(), any(), any()))
            .thenReturn(resource("vast/pauseroll_no_media.xml"))

        val ad = createAd().setTagUrl("https://tag.example/vast").attach().trigger()

        verify(client, times(1)).fetchText(eq("https://tag.example/vast"), any(), any())
        assertEquals(listOf("open"), lifecycle)
        verify(ui).showImage(anyOrNull(), any())
        verify(ui, never()).showVideo(any())
        assertNull(videoPlayer.playedUrl)
        ad.detach()
    }

    @Test
    fun companionDownloadFails_noOverlay_andNoAd() {
        whenever(client.fetchText(any(), any(), any()))
            .thenReturn(resource("vast/pauseroll_no_media.xml"))
        whenever(bitmaps.load(any(), any(), any())).thenReturn(null)

        createAd().setTagUrl("https://tag.example/vast").attach().trigger()

        verify(ui, never()).showImage(anyOrNull(), any())
        assertEquals(listOf("no_ad"), lifecycle)
        assertTrue(tracking.none { it == "impression" })
    }

    @Test
    fun pause_videoOnly_showsVideo_andTracks() {
        whenever(client.fetchText(any(), any(), any()))
            .thenReturn(resource("vast/pauseroll_video_only.xml"))
        whenever(ui.videoSurface()).thenReturn(mock())

        val ad = createAd().setTagUrl("https://tag.example/vast").attach().trigger()

        assertEquals(listOf("open"), lifecycle)
        assertTrue(tracking.contains("impression"))
        assertTrue(tracking.contains("start"))
        verify(ui).showVideo(any())
        ad.detach()
    }

    @Test
    fun setSoundEnabled_reachesTheAdPlayer() {
        val ad = createAd().setSoundEnabled(false)
        assertFalse(videoPlayer.soundEnabled)
        ad.setSoundEnabled(true)
        assertTrue(videoPlayer.soundEnabled)
    }

    @Test
    fun chromeWaitsForTheCreativeToBeOnScreen() {
        whenever(client.fetchText(any(), any(), any()))
            .thenReturn(resource("vast/pauseroll_video_only.xml"))
        whenever(ui.videoSurface()).thenReturn(mock())
        videoPlayer.emitReadyOnPlay = false

        createAd().setTagUrl("https://tag.example/vast").attach().trigger()

        verify(ui).showVideo(any())
        verify(ui, never()).revealChrome()

        videoPlayer.signalReady()
        verify(ui).revealChrome()
    }

    @Test
    fun pause_doubleCallback_singleFetch() {
        whenever(client.fetchText(any(), any(), any()))
            .thenReturn(resource("vast/pauseroll_fill.xml"))
        whenever(ui.videoSurface()).thenReturn(mock())
        val ad = createAd().setTagUrl("https://tag.example/vast").attach().trigger()
        ad.trigger()
        verify(client, times(1)).fetchText(any(), any(), any())
    }

    @Test
    fun cancelDuringFetch_ignoresResponse() {
        lateinit var ad: PauseRollAd
        whenever(client.fetchText(any(), any(), any())).thenAnswer {
            ad.close()
            resource("vast/pauseroll_fill.xml")
        }
        ad = createAd().setTagUrl("https://tag.example/vast").attach().trigger()

        verify(ui, never()).showVideo(any())
        verify(ui, never()).showImage(anyOrNull(), any())
        assertTrue(lifecycle.isEmpty())
    }

    @Test
    fun httpNull_onError() {
        whenever(client.fetchText(any(), any(), any())).thenReturn(null)
        createAd().setTagUrl("https://tag.example/vast").attach().trigger()
        assertTrue(lifecycle.isEmpty())
        assertEquals(1, errors.size)
        assertTrue(errors.first().contains("VAST fetch failed"))
    }

    @Test
    fun missingTag_onError() {
        createAd().attach().trigger()
        assertTrue(errors.first().contains("tagUrl"))
    }

    @Test
    fun skipAfterOffset_emitsSkipped_andLeavesContentPaused() {
        whenever(client.fetchText(any(), any(), any()))
            .thenReturn(resource("vast/pauseroll_video_only.xml"))
        whenever(ui.videoSurface()).thenReturn(mock())
        val ad = createAd()
            .setTagUrl("https://tag.example/vast")
            .setSkipOffsetSeconds(5)
            .attach()
        ad.trigger()
        assertEquals("open", lifecycle.last())

        clock.now = clock.now + 5_000L
        ad.onSkipFromUi()
        assertTrue(lifecycle.contains("closed"))
        assertTrue(tracking.contains("skip"))
        assertEquals(1, videoPlayer.releases)
        ad.detach()
    }

    @Test
    fun skipBeforeOffset_ignored() {
        whenever(client.fetchText(any(), any(), any()))
            .thenReturn(resource("vast/pauseroll_video_only.xml"))
        whenever(ui.videoSurface()).thenReturn(mock())
        val ad = createAd()
            .setTagUrl("https://tag.example/vast")
            .setSkipOffsetSeconds(5)
            .attach()
        ad.trigger()

        clock.now = clock.now + 1_000L
        ad.onSkipFromUi()
        assertFalse(lifecycle.contains("closed"))
        assertFalse(tracking.contains("skip"))
    }

    @Test
    fun setRequestTimeout_passedToHttp() {
        whenever(client.fetchText(any(), any(), any()))
            .thenReturn(resource("vast/pauseroll_no_media.xml"))
        createAd()
            .setTagUrl("https://tag.example/vast")
            .setRequestTimeoutMs(3_000)
            .attach()
            .trigger()
        verify(client).fetchText(eq("https://tag.example/vast"), eq(3_000), any())
    }

    @Test
    fun tagUrlMacros_areExpandedBeforeFetch() {
        whenever(client.fetchText(any(), any(), any()))
            .thenReturn(resource("vast/pauseroll_no_media.xml"))
        createAd()
            .setTagUrl("https://tag.example/vast?ifa=\${IFA}&ua=\${USER_AGENT}&ip=\${IP}&r=\${RANDOM}")
            .setIfa("aa-bb")
            .setIp("1.2.3.4")
            .attach()
            .trigger()
        verify(client).fetchText(
            check { url ->
                assertTrue(url.startsWith("https://tag.example/vast?"))
                assertTrue(url.contains("ifa=aa-bb"))
                assertTrue(url.contains("ip=1.2.3.4"))
                assertTrue(Regex("r=\\d{8}").containsMatchIn(url))
                assertFalse(url.contains("\${"))
            },
            any(),
            any(),
        )
    }

    @Test
    fun detach_releasesUiAndAdPlayer() {
        whenever(client.fetchText(any(), any(), any()))
            .thenReturn(resource("vast/pauseroll_video_only.xml"))
        whenever(ui.videoSurface()).thenReturn(mock())
        val ad = createAd().setTagUrl("https://tag.example/vast").attach().trigger()

        ad.detach()

        verify(ui).detach()
        verify(ui).release()
        assertTrue(videoPlayer.releases >= 1)
        ad.detach()
    }

    @Test
    fun attachAfterDetach_isIgnored() {
        val ad = createAd().setTagUrl("https://tag.example/vast").attach()
        ad.detach()
        ad.attach()

        verify(ui, times(1)).attach()
    }

    @Test
    fun detachDuringFetch_dropsResponse() {
        lateinit var ad: PauseRollAd
        whenever(client.fetchText(any(), any(), any())).thenAnswer {
            ad.detach()
            resource("vast/pauseroll_video_only.xml")
        }
        whenever(ui.videoSurface()).thenReturn(mock())
        ad = createAd().setTagUrl("https://tag.example/vast").attach().trigger()

        verify(ui, never()).showVideo(any())
        assertTrue(lifecycle.isEmpty())
    }

    @Test
    fun attach_idempotent() {
        val ad = createAd().setTagUrl("https://x").attach().attach()
        verify(ui, times(1)).attach()
        ad.detach()
    }

    @Test
    fun close_emitsSlotClose() {
        whenever(client.fetchText(any(), any(), any()))
            .thenReturn(resource("vast/pauseroll_video_only.xml"))
        whenever(ui.videoSurface()).thenReturn(mock())
        val ad = createAd().setTagUrl("https://tag.example/vast").attach().trigger()
        ad.close()
        assertTrue(lifecycle.contains("closed"))
    }

    @Test
    fun wrapperTag_resolvesAndMergesTrackings() {
        whenever(client.fetchText(any(), any(), any())).thenAnswer { inv ->
            when (inv.getArgument<String>(0)) {
                "https://tag.example/vast" -> resource("vast/wrapper_with_trackings.xml")
                "https://example.com/inline.xml" -> resource("vast/wrapper_inline.xml")
                else -> null
            }
        }
        whenever(ui.videoSurface()).thenReturn(mock())
        createAd().setTagUrl("https://tag.example/vast").attach().trigger()

        assertEquals(listOf("open"), lifecycle)
        assertTrue(tracking.contains("impression"))
        assertTrue(tracking.contains("start"))
        verify(client).fetchText(eq("https://example.com/inline.xml"), any(), any())
        verify(ui).showVideo(any())
    }

    @Test
    fun bannerInfo_usesCompanionClickThrough() {
        whenever(client.fetchText(any(), any(), any()))
            .thenReturn(resource("vast/pauseroll_clicks.xml"))

        val ad = createAd().setTagUrl("https://tag.example/vast").attach().trigger()
        val chrome = argumentCaptor<Chrome>()
        verify(ui).showImage(anyOrNull(), chrome.capture())
        assertTrue(chrome.firstValue.infoAvailable)
        assertFalse(chrome.firstValue.pauseAvailable)

        ad.onInfoFromUi()
        verify(ui).showQr("https://example.com/companion-land")
        verify(client).fireTrackers(eq(listOf("https://example.com/companion-click")), any())
        ad.detach()
    }

    @Test
    fun linearFallback_usesLinearClickThrough() {
        whenever(client.fetchText(any(), any(), any()))
            .thenReturn(resource("vast/pauseroll_clicks.xml"))
        whenever(bitmaps.load(any(), any(), any())).thenReturn(null)
        whenever(ui.videoSurface()).thenReturn(mock())

        val ad = createAd().setTagUrl("https://tag.example/vast").attach().trigger()
        val chrome = argumentCaptor<Chrome>()
        verify(ui).showVideo(chrome.capture())
        assertTrue(chrome.firstValue.infoAvailable)
        assertTrue(chrome.firstValue.pauseAvailable)

        ad.onInfoFromUi()
        verify(ui).showQr("https://example.com/linear-land")
        verify(client).fireTrackers(eq(listOf("https://example.com/linear-click")), any())
    }

    @Test
    fun hostCanHideInfoPauseAndMute() {
        whenever(client.fetchText(any(), any(), any()))
            .thenReturn(resource("vast/pauseroll_clicks.xml"))
        whenever(bitmaps.load(any(), any(), any())).thenReturn(null)
        whenever(ui.videoSurface()).thenReturn(mock())

        createAd()
            .setInfoVisible(false)
            .setPauseVisible(false)
            .setMuteVisible(false)
            .setTagUrl("https://tag.example/vast")
            .attach()
            .trigger()

        val chrome = argumentCaptor<Chrome>()
        verify(ui).showVideo(chrome.capture())
        assertFalse(chrome.firstValue.infoAvailable)
        assertFalse(chrome.firstValue.pauseAvailable)
        assertFalse(chrome.firstValue.muteAvailable)
    }

    private fun createAd(ui: Ui = this.ui): PauseRollAd {
        val ad = PauseRollAd(
            appContext = mock<Context>(),
            ui = ui,
            client = client,
            bitmapLoader = bitmaps,
            videoPlayer = videoPlayer,
            clock = clock,
            mainPoster = main,
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

            override fun onStart(urls: List<String>) {
                tracking.add("start")
            }

            override fun onSkip(urls: List<String>) {
                tracking.add("skip")
            }
        })
        return ad
    }

    /** Runs one scheduled ticker round. */
    private fun tick() = main.runDelayed()

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
