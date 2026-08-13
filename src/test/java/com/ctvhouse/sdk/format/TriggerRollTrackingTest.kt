package com.ctvhouse.sdk.format

import android.content.Context
import com.ctvhouse.sdk.core.media.BitmapLoader
import com.ctvhouse.sdk.core.net.Client
import com.ctvhouse.sdk.core.player.FakeVideoPlayer
import com.ctvhouse.sdk.core.runtime.Clock
import com.ctvhouse.sdk.core.runtime.QueueMainPoster
import com.ctvhouse.sdk.core.ui.Chrome
import com.ctvhouse.sdk.core.ui.Ui
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.concurrent.Executor

/** Covers what the ticker and the dismissal paths report to a [TriggerRoll.Listener]. */
class TriggerRollTrackingTest {

    private lateinit var ui: Ui
    private lateinit var clock: FakeClock
    private lateinit var main: QueueMainPoster
    private lateinit var videoPlayer: FakeVideoPlayer
    private lateinit var pauseRoll: TriggerRoll
    private val io = Executor { it.run() }
    private val events = mutableListOf<String>()
    private val pinged = mutableListOf<String>()

    @Before
    fun setUp() {
        events.clear()
        pinged.clear()
        ui = mock()
        whenever(ui.videoSurface()).thenReturn(mock())
        clock = FakeClock(0L)
        main = QueueMainPoster()
        videoPlayer = FakeVideoPlayer()

        val client = mock<Client>()
        doAnswer { inv ->
            pinged.addAll(inv.getArgument<List<String>>(0))
            null
        }.whenever(client).fireTrackers(any(), any())

        pauseRoll = TriggerRoll(
            appContext = mock<Context>(),
            ui = ui,
            client = client,
            bitmapLoader = mock<BitmapLoader>(),
            videoPlayer = videoPlayer,
            clock = clock,
            mainPoster = main,
            io = io,
            track = io,
        )
        pauseRoll.setListener(recordingListener())
        pauseRoll.setController { _, sink -> sink.deliverXml(resource("vast/pauseroll_tracking.xml")) }
    }

    @Test
    fun show_firesCreativeViewLoadedStartAndImpression() {
        startAd()
        assertEquals(listOf("impression", "creativeView", "loaded", "start", "open"), events)
        assertTrue(pinged.contains("https://example.com/imp"))
        assertTrue(pinged.contains("https://example.com/start"))
    }

    @Test
    fun quartiles_followCreativePosition_notWallClock() {
        startAd()

        // Ten seconds of wall clock with a stalled creative must not report progress.
        clock.now = 10_000L
        tick()
        assertFalse(events.contains("firstQuartile"))

        videoPlayer.positionMs = 2_500L
        tick()
        assertTrue(events.contains("firstQuartile"))
        assertFalse(events.contains("midpoint"))

        videoPlayer.positionMs = 5_000L
        tick()
        assertTrue(events.contains("midpoint"))

        videoPlayer.positionMs = 7_500L
        tick()
        assertTrue(events.contains("thirdQuartile"))
        assertEquals(1, events.count { it == "firstQuartile" })
    }

    @Test
    fun progress_firesOncePerOffset() {
        startAd()
        videoPlayer.positionMs = 3_000L
        tick()
        tick()
        assertEquals(1, events.count { it == "progress:3000" })
        assertFalse(events.any { it.startsWith("progress:8000") })

        videoPlayer.positionMs = 8_000L
        tick()
        assertEquals(1, events.count { it == "progress:80.0%" })
    }

    @Test
    fun viewable_requiresTimeOnScreen() {
        startAd()
        tick()
        assertFalse(events.contains("viewable"))

        clock.now = 2_000L
        tick()
        tick()
        assertEquals(1, events.count { it == "viewable" })
        assertTrue(pinged.contains("https://example.com/viewable"))
    }

    @Test
    fun overlayClosedTooEarly_reportsNotViewable() {
        startAd()
        videoPlayer.finish()

        assertFalse(events.contains("viewable"))
        assertTrue(events.contains("notViewable"))
    }

    @Test
    fun creativeEnd_firesCompleteAndCloseLinear() {
        startAd()
        videoPlayer.finish()

        assertTrue(events.contains("overlayViewDuration"))
        assertTrue(events.contains("complete"))
        assertTrue(events.contains("closeLinear"))
        assertTrue(events.contains("close"))
        assertTrue(events.contains("closed"))
    }

    @Test
    fun contentResumed_doesNotFakeComplete() {
        startAd()
        pauseRoll.close()

        assertFalse("complete must describe a creative that played out", events.contains("complete"))
        assertFalse(events.contains("closeLinear"))
        assertTrue(events.contains("close"))
        assertTrue(events.contains("closed"))
    }

    @Test
    fun skip_firesSkipButNeitherCompleteNorClose() {
        startAd()
        clock.now = 5_000L
        pauseRoll.onSkipFromUi()

        assertTrue(events.contains("skip"))
        assertTrue(events.contains("closed"))
        assertFalse(events.contains("complete"))
        assertFalse(events.contains("close"))
    }

    @Test
    fun skip_hostResumeInOnSkip_doesNotFireVastClose() {
        pauseRoll.setListener(object : TriggerRoll.Listener {
            override fun onOpen() { events.add("open") }
            override fun onClose() { events.add("closed") }
            override fun onSkip(urls: List<String>) {
                events.add("skip")
                pauseRoll.close()
            }
            override fun onClose(urls: List<String>) { events.add("close") }
        })
        startAd()
        clock.now = 5_000L
        pauseRoll.onSkipFromUi()

        assertTrue(events.contains("skip"))
        assertTrue(events.contains("closed"))
        assertFalse(events.contains("close"))
        assertFalse(events.contains("complete"))
    }

    @Test
    fun adPauseAndResume_areReported() {
        startAd()
        videoPlayer.setPlaying(true)
        videoPlayer.setPlaying(false)
        videoPlayer.setPlaying(true)

        assertTrue(events.contains("pause"))
        assertTrue(events.contains("resume"))
    }

    @Test
    fun muteAndUnmute_areReported() {
        startAd()
        videoPlayer.setMuted(true)
        videoPlayer.setMuted(false)

        assertTrue(events.contains("mute"))
        assertTrue(events.contains("unmute"))
    }

    @Test
    fun muteFromChrome_togglesAndReports() {
        startAd()
        pauseRoll.onMuteFromUi()
        assertTrue(videoPlayer.muted)
        assertTrue(events.contains("mute"))

        pauseRoll.onMuteFromUi()
        assertFalse(videoPlayer.muted)
        assertTrue(events.contains("unmute"))
    }

    @Test
    fun pauseFromChrome_togglesAndReports() {
        startAd()
        pauseRoll.onPauseFromUi()
        assertFalse(videoPlayer.playing)
        assertTrue(events.contains("pause"))

        pauseRoll.onPauseFromUi()
        assertTrue(videoPlayer.playing)
        assertTrue(events.contains("resume"))
    }

    @Test
    fun infoFromChrome_pingsClickTrackingAndShowsQr() {
        startAd()
        val chrome = argumentCaptor<Chrome>()
        verify(ui).showVideo(chrome.capture())
        assertTrue(chrome.firstValue.infoAvailable)

        pauseRoll.onInfoFromUi()
        assertTrue(pinged.contains("https://example.com/click"))
        verify(ui).showQr("https://example.com/land")
    }

    @Test
    fun infoHiddenWhenHostDisablesIt() {
        pauseRoll.setInfoVisible(false)
        startAd()
        val chrome = argumentCaptor<Chrome>()
        verify(ui).showVideo(chrome.capture())
        assertFalse(chrome.firstValue.infoAvailable)
    }

    @Test
    fun pauseAndMuteHiddenWhenHostDisablesThem() {
        pauseRoll.setPauseVisible(false)
        pauseRoll.setMuteVisible(false)
        startAd()
        val chrome = argumentCaptor<Chrome>()
        verify(ui).showVideo(chrome.capture())
        assertFalse(chrome.firstValue.pauseAvailable)
        assertFalse(chrome.firstValue.muteAvailable)
    }

    @Test
    fun mediaError_reportsVastErrorAndHidesOverlay() {
        startAd()
        videoPlayer.fail("SOURCE_ERROR")

        assertTrue(events.contains("vastError"))
        assertTrue(pinged.contains("https://example.com/error"))
        assertTrue(events.any { it.startsWith("error:") })
        assertFalse(events.contains("dismissed"))
    }

    @Test
    fun noFill_reportsVastErrorAndNoAd() {
        pauseRoll.setController { _, sink ->
            sink.deliverXml(
                """
                <VAST version="3.0"><Ad><InLine>
                <Error><![CDATA[https://example.com/nofill-error]]></Error>
                </InLine></Ad></VAST>
                """.trimIndent(),
            )
        }
        startAd(expectShown = false)

        assertTrue(events.contains("vastError"))
        assertTrue(events.contains("noAd"))
        assertTrue(pinged.contains("https://example.com/nofill-error"))
    }

    @Test
    fun controllerAnsweringOnAnotherThread_deliversOnMain() {
        pauseRoll.setController { _, sink ->
            val worker = Thread({ sink.noAd() }, "controller-worker")
            worker.start()
            worker.join()
        }
        startAd(expectShown = false)
        main.runPosted()

        assertTrue(events.contains("noAd"))
    }

    @Test
    fun chrome_isRewrittenOnlyWhenTheCountdownMoves() {
        startAd()

        tick()
        tick()
        tick()
        verify(ui, times(1)).updateControls(any())

        clock.now = 1_000L
        tick()
        verify(ui, times(2)).updateControls(any())
    }

    @Test
    fun skip_thenAnotherPause_getsAnotherAd() {
        val opportunities = countingController()
        startAd()
        clock.now = 5_000L
        pauseRoll.onSkipFromUi()
        events.clear()

        pauseRoll.trigger("pause")

        assertEquals(2, opportunities.size)
        assertTrue("the second pause was left without an ad: $events", events.contains("open"))
    }

    @Test
    fun creativeEnd_thenAnotherPause_getsAnotherAd() {
        val opportunities = countingController()
        startAd()
        videoPlayer.finish()
        events.clear()

        pauseRoll.trigger("pause")

        assertEquals(2, opportunities.size)
        assertTrue("the second pause was left without an ad: $events", events.contains("open"))
    }

    @Test
    fun mediaError_thenAnotherPause_getsAnotherAd() {
        val opportunities = countingController()
        startAd()
        videoPlayer.fail("SOURCE_ERROR")
        events.clear()

        pauseRoll.trigger("pause")

        assertEquals(2, opportunities.size)
        assertTrue("the second pause was left without an ad: $events", events.contains("open"))
    }

    @Test
    fun noFill_thenAnotherPause_asksAgain() {
        val opportunities = mutableListOf<String>()
        pauseRoll.setController { reason, sink ->
            opportunities.add(reason)
            sink.noAd()
        }
        pauseRoll.attach()

        pauseRoll.trigger("pause")
        pauseRoll.trigger("pause")

        assertEquals(2, opportunities.size)
    }

    @Test
    fun repeatedPauseCycles_keepServingAds() {
        val opportunities = countingController()
        pauseRoll.attach()
        repeat(5) { round ->
            events.clear()
            pauseRoll.trigger("pause")
            assertTrue("round $round was left without an ad: $events", events.contains("open"))
            pauseRoll.close()
            assertTrue("round $round never closed: $events", events.contains("closed"))
        }
        assertEquals(5, opportunities.size)
    }

    @Test
    fun triggerWithoutAController_reportsAnErrorAndShowsNothing() {
        pauseRoll.setController(null)
        pauseRoll.attach()

        pauseRoll.trigger("pause")

        assertTrue(events.any { it.startsWith("error:") })
        assertFalse(events.contains("open"))
    }

    @Test
    fun detachedInstance_stopsReportingAndIgnoresReattach() {
        startAd()
        pauseRoll.detach()
        events.clear()

        pauseRoll.attach()
        pauseRoll.trigger()
        tick()

        assertTrue(events.isEmpty())
    }

    /**
     * Some placements want the creative to stay after it played out, so that the viewer has to
     * skip. The playout is still reported when it happens — only the dismissal waits.
     */
    @Test
    fun creativeEnd_holdsTheCreativeWhenTheHostAsksForThat() {
        pauseRoll.setDismissOnCreativeEnd(false)
        startAd()

        videoPlayer.finish()

        assertTrue("playout must still be reported: $events", events.contains("complete"))
        assertTrue(events.contains("closeLinear"))
        assertFalse("the creative was dropped: $events", events.contains("closed"))
        assertNotNull("the player holds the last frame", videoPlayer.playedUrl)

        clock.now = 5_000L
        pauseRoll.onSkipFromUi()

        assertTrue(events.contains("skip"))
        assertTrue(events.contains("closed"))
        assertEquals("complete belongs to the playout, once", 1, events.count { it == "complete" })
    }

    @Test
    fun creativeEnd_closesTheShowByDefault() {
        startAd()

        videoPlayer.finish()

        assertTrue(events.contains("complete"))
        assertTrue(events.contains("closeLinear"))
        assertTrue(events.contains("closed"))
    }

    /** Installs a controller that always fills and returns the reasons it was asked for. */
    private fun countingController(): List<String> {
        val reasons = mutableListOf<String>()
        pauseRoll.setController { reason, sink ->
            reasons.add(reason)
            sink.deliverXml(resource("vast/pauseroll_tracking.xml"))
        }
        return reasons
    }

    private fun startAd(expectShown: Boolean = true) {
        pauseRoll.attach()
        pauseRoll.trigger()
        if (expectShown) {
            assertTrue("ad did not reach the screen: $events", events.contains("open"))
        }
    }

    /** Runs one scheduled ticker round. */
    private fun tick() = main.runDelayed()

    private fun recordingListener() = object : TriggerRoll.Listener {
        override fun onOpen() { events.add("open") }
        override fun onClose() { events.add("closed") }
        override fun onNoAd() { events.add("noAd") }
        override fun onError(message: String) { events.add("error:$message") }
        override fun onImpression(urls: List<String>) { events.add("impression") }
        override fun onVastError(urls: List<String>) { events.add("vastError") }
        override fun onViewable(urls: List<String>) { events.add("viewable") }
        override fun onNotViewable(urls: List<String>) { events.add("notViewable") }
        override fun onCreativeView(urls: List<String>) { events.add("creativeView") }
        override fun onLoaded(urls: List<String>) { events.add("loaded") }
        override fun onStart(urls: List<String>) { events.add("start") }
        override fun onFirstQuartile(urls: List<String>) { events.add("firstQuartile") }
        override fun onMidpoint(urls: List<String>) { events.add("midpoint") }
        override fun onThirdQuartile(urls: List<String>) { events.add("thirdQuartile") }
        override fun onComplete(urls: List<String>) { events.add("complete") }
        override fun onCloseLinear(urls: List<String>) { events.add("closeLinear") }
        override fun onClose(urls: List<String>) { events.add("close") }
        override fun onSkip(urls: List<String>) { events.add("skip") }
        override fun onMute(urls: List<String>) { events.add("mute") }
        override fun onUnmute(urls: List<String>) { events.add("unmute") }
        override fun onPause(urls: List<String>) { events.add("pause") }
        override fun onResume(urls: List<String>) { events.add("resume") }
        override fun onOverlayViewDuration(urls: List<String>) { events.add("overlayViewDuration") }
        override fun onTracking(event: String, urls: List<String>) { events.add("tracking:$event") }
        override fun onProgress(offset: TriggerRoll.ProgressOffset, urls: List<String>) {
            val label = when (offset) {
                is TriggerRoll.ProgressOffset.Absolute -> "${offset.ms}"
                is TriggerRoll.ProgressOffset.Percent -> "${offset.value}%"
            }
            events.add("progress:$label")
        }
    }

    private fun resource(path: String): String =
        requireNotNull(javaClass.classLoader!!.getResourceAsStream(path))
            .bufferedReader()
            .use { it.readText() }

    private class FakeClock(var now: Long) : Clock {
        override fun currentTimeMillis(): Long = now
    }
}
