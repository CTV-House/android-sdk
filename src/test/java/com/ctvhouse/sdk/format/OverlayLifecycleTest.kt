package com.ctvhouse.sdk.format

import android.content.Context
import com.ctvhouse.sdk.core.media.BitmapLoader
import com.ctvhouse.sdk.core.net.Client
import com.ctvhouse.sdk.core.player.FakeVideoPlayer
import com.ctvhouse.sdk.core.runtime.Clock
import com.ctvhouse.sdk.core.runtime.QueueMainPoster
import com.ctvhouse.sdk.core.ui.Ui
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * `detach()` has to leave nothing behind in the host process: no live threads of ours and no
 * reference to the host chrome.
 */
class OverlayLifecycleTest {

    @Test
    fun ownedThreadsAreGoneAfterDetach() {
        val io = Executors.newSingleThreadExecutor()
        val track = Executors.newSingleThreadExecutor()
        val slot = slot(io, track, ownsExecutors = true)
        // Force both pools to actually spin up a thread before the slot releases them.
        awaitIdle(io)
        awaitIdle(track)

        slot.attach()
        slot.detach()

        assertTrue("io pool still accepts work", io.isShutdown)
        assertTrue("tracker pool still accepts work", track.isShutdown)
        assertTrue("io thread outlived the slot", io.awaitTermination(5, TimeUnit.SECONDS))
        assertTrue("tracker thread outlived the slot", track.awaitTermination(5, TimeUnit.SECONDS))
    }

    @Test
    fun poolsSuppliedByTheHostAreLeftAlone() {
        val io = Executors.newSingleThreadExecutor()
        val slot = slot(io, io, ownsExecutors = false)

        slot.attach()
        slot.detach()

        assertTrue("a pool the library did not create must survive", !io.isShutdown)
        io.shutdownNow()
    }

    /**
     * The host resumes content the moment the overlay goes away, which ends the opportunity.
     * A pixel the show already earned must not be cancelled along with it.
     */
    @Test
    fun aPixelEarnedBeforeTheHostClosedTheSlotStillGoesOut() {
        val pinged = mutableListOf<String>()
        val queued = mutableListOf<Runnable>()
        val slot = fillingSlot(client = recordingClient(pinged), track = Executor { queued.add(it) })

        slot.attach()
        slot.trigger("pause")
        slot.close()
        queued.forEach { it.run() }

        assertTrue("impression dropped when the slot closed: $pinged", pinged.contains(IMPRESSION))
    }

    /** Same for a screen going away: the pending ping holds a URL, not the host. */
    @Test
    fun aPixelQueuedBeforeDetachStillReachesTheServer() {
        val sent = CountDownLatch(1)
        val blocked = CountDownLatch(1)
        val track = Executors.newSingleThreadExecutor()
        track.execute { blocked.await(5, TimeUnit.SECONDS) }
        val slot = fillingSlot(
            client = object : Client {
                override fun fetchText(url: String, timeoutMs: Int, userAgent: String): String? =
                    null

                override fun fetchBytes(
                    url: String,
                    timeoutMs: Int,
                    userAgent: String,
                    maxBytes: Int,
                ): ByteArray? = null

                override fun fireTrackers(urls: List<String>, userAgent: String) {
                    if (urls.contains(IMPRESSION)) sent.countDown()
                }
            },
            track = track,
            ownsExecutors = true,
        )

        slot.attach()
        slot.trigger("pause")
        slot.detach()
        blocked.countDown()

        assertTrue("detach cancelled a pixel the show had earned", sent.await(5, TimeUnit.SECONDS))
        assertTrue(track.awaitTermination(5, TimeUnit.SECONDS))
    }

    @Test
    fun detachReleasesTheHostChrome() {
        val ui = mock<Ui>()
        val io = Executors.newSingleThreadExecutor()
        val slot = slot(io, io, ownsExecutors = true, ui = ui)

        slot.attach()
        slot.detach()

        verify(ui).detach()
        verify(ui).release()
    }

    private fun slot(
        io: ExecutorService,
        track: ExecutorService,
        ownsExecutors: Boolean,
        ui: Ui = mock(),
    ): TriggerRoll = TriggerRoll(
        appContext = mock<Context>(),
        ui = ui,
        client = mock<Client>(),
        bitmapLoader = mock<BitmapLoader>(),
        videoPlayer = FakeVideoPlayer(),
        clock = Clock { 0L },
        mainPoster = QueueMainPoster(),
        io = io,
        track = track,
        ownsExecutors = ownsExecutors,
    )

    /** A slot whose controller always answers with a filling tag. */
    private fun fillingSlot(
        client: Client,
        track: Executor,
        ownsExecutors: Boolean = false,
    ): TriggerRoll {
        val ui = mock<Ui>()
        whenever(ui.videoSurface()).thenReturn(mock())
        val slot = TriggerRoll(
            appContext = mock<Context>(),
            ui = ui,
            client = client,
            bitmapLoader = mock<BitmapLoader>(),
            videoPlayer = FakeVideoPlayer(),
            clock = Clock { 0L },
            mainPoster = QueueMainPoster(),
            io = Executor { it.run() },
            track = track,
            ownsExecutors = ownsExecutors,
        )
        slot.setController { _, sink -> sink.deliverXml(resource("vast/pauseroll_tracking.xml")) }
        return slot
    }

    private fun recordingClient(into: MutableList<String>): Client = mock<Client>().also { client ->
        doAnswer { inv ->
            into.addAll(inv.getArgument<List<String>>(0))
            null
        }.whenever(client).fireTrackers(any(), any())
    }

    private fun resource(path: String): String =
        requireNotNull(javaClass.classLoader!!.getResourceAsStream(path))
            .bufferedReader()
            .use { it.readText() }

    private fun awaitIdle(executor: ExecutorService) {
        val started = CountDownLatch(1)
        executor.execute { started.countDown() }
        assertTrue(started.await(5, TimeUnit.SECONDS))
    }

    private companion object {
        const val IMPRESSION = "https://example.com/imp"
    }
}
