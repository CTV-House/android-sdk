package com.ctvhouse.sdk.format

import android.content.Context
import android.os.Looper
import android.view.SurfaceView
import com.ctvhouse.sdk.core.media.BitmapLoader
import com.ctvhouse.sdk.core.net.Client
import com.ctvhouse.sdk.core.player.VideoPlayer
import com.ctvhouse.sdk.core.runtime.Clock
import com.ctvhouse.sdk.core.runtime.HandlerMainPoster
import com.ctvhouse.sdk.core.runtime.MainPoster
import com.ctvhouse.sdk.core.runtime.QueueMainPoster
import com.ctvhouse.sdk.core.ui.Ui
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.Executor

/**
 * A host must not pay for our mistakes or for its own build setup: whatever the surroundings,
 * a misuse turns into a reported error rather than an exception crossing back into host code.
 */
@RunWith(RobolectricTestRunner::class)
class HostSafetyTest {

    private val events = mutableListOf<String>()

    /**
     * Stands in for an app that shipped without Media3 on the classpath: the ad player throws a
     * linkage error the first time it is used. The opportunity has to end in `onError`.
     */
    @Test
    fun anAdPlayerThatCannotStartIsReportedInsteadOfThrown() {
        val slot = slot(
            videoPlayer = object : VideoPlayer {
                override fun play(
                    context: Context,
                    url: String,
                    surfaceView: SurfaceView?,
                    onEnded: () -> Unit,
                    onError: (String) -> Unit,
                    onMutedChanged: ((Boolean) -> Unit)?,
                    onPlayingChanged: ((Boolean) -> Unit)?,
                    onReady: (() -> Unit)?,
                ) = throw NoClassDefFoundError("androidx/media3/exoplayer/ExoPlayer")

                override fun positionMs(): Long = 0L
                override fun isMuted(): Boolean = false
                override fun isPlaying(): Boolean = false
                override fun setMuted(muted: Boolean) = Unit
                override fun setPlaying(playing: Boolean) = Unit
                override fun release() = Unit
            },
        )
        slot.setListener(recordingListener())
        slot.setController { _, sink -> sink.deliverXml(resource("vast/pauseroll_tracking.xml")) }

        slot.attach()
        slot.trigger("pause")

        assertTrue("no error reported: $events", events.any { it.startsWith("error:") })
        assertFalse("an unplayable creative must not be counted: $events", events.contains("open"))
    }

    /** And the next pause is a fresh opportunity rather than a slot stuck on the failure. */
    @Test
    fun aPlaybackFailureDoesNotBlockTheNextOpportunity() {
        val asked = mutableListOf<String>()
        val slot = slot(
            videoPlayer = object : VideoPlayer {
                override fun play(
                    context: Context,
                    url: String,
                    surfaceView: SurfaceView?,
                    onEnded: () -> Unit,
                    onError: (String) -> Unit,
                    onMutedChanged: ((Boolean) -> Unit)?,
                    onPlayingChanged: ((Boolean) -> Unit)?,
                    onReady: (() -> Unit)?,
                ) = throw IllegalStateException("no decoder")

                override fun positionMs(): Long = 0L
                override fun isMuted(): Boolean = false
                override fun isPlaying(): Boolean = false
                override fun setMuted(muted: Boolean) = Unit
                override fun setPlaying(playing: Boolean) = Unit
                override fun release() = Unit
            },
        )
        slot.setController { reason, sink ->
            asked.add(reason)
            sink.deliverXml(resource("vast/pauseroll_tracking.xml"))
        }

        slot.attach()
        slot.trigger("first")
        slot.trigger("second")

        assertTrue("the slot stayed stuck on the failed show: $asked", asked.size == 2)
    }

    /**
     * A host that calls from a worker thread gets the call moved to the looper instead of a
     * `CalledFromWrongThreadException` out of our view code.
     */
    @Test
    fun aCallFromAWorkerThreadIsMovedToTheMainLooper() {
        val ui = mock<Ui>()
        val slot = slot(ui = ui, mainPoster = HandlerMainPoster())

        val worker = Thread({ slot.attach() }, "host-worker")
        worker.start()
        worker.join()

        verify(ui, never()).attach()
        shadowOf(Looper.getMainLooper()).idle()
        verify(ui).attach()
    }

    private fun slot(
        ui: Ui = mock<Ui>().also { whenever(it.videoSurface()).thenReturn(mock()) },
        videoPlayer: VideoPlayer = mock(),
        mainPoster: MainPoster = QueueMainPoster(),
    ): TriggerRoll = TriggerRoll(
        appContext = mock<Context>(),
        ui = ui,
        client = mock<Client>(),
        bitmapLoader = mock<BitmapLoader>(),
        videoPlayer = videoPlayer,
        clock = Clock { 0L },
        mainPoster = mainPoster,
        io = Executor { it.run() },
        track = Executor { it.run() },
    )

    private fun recordingListener() = object : TriggerRoll.Listener {
        override fun onOpen() { events.add("open") }
        override fun onClose() { events.add("closed") }
        override fun onNoAd() { events.add("noAd") }
        override fun onError(message: String) { events.add("error:$message") }
    }

    private fun resource(path: String): String =
        requireNotNull(javaClass.classLoader!!.getResourceAsStream(path))
            .bufferedReader()
            .use { it.readText() }
}
