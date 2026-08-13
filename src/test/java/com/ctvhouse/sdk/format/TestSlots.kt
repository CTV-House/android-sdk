package com.ctvhouse.sdk.format

import android.content.Context
import com.ctvhouse.sdk.core.media.BitmapLoader
import com.ctvhouse.sdk.core.net.Client
import com.ctvhouse.sdk.core.player.FakeVideoPlayer
import com.ctvhouse.sdk.core.runtime.Clock
import com.ctvhouse.sdk.core.runtime.QueueMainPoster
import com.ctvhouse.sdk.core.ui.Ui
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.concurrent.Executor

/**
 * Wiring for tests that cannot build a slot themselves: the collaborators are `internal`, so a
 * Java test has no way to name them.
 */
internal object TestSlots {

    /** A slot whose controller answers every opportunity with a filling tag. */
    @JvmStatic
    fun fillingTriggerRoll(): TriggerRoll {
        val ui = mock<Ui>()
        whenever(ui.videoSurface()).thenReturn(mock())
        val slot = TriggerRoll(
            appContext = mock<Context>(),
            ui = ui,
            client = mock<Client>(),
            bitmapLoader = mock<BitmapLoader>(),
            videoPlayer = FakeVideoPlayer(),
            clock = Clock { 0L },
            mainPoster = QueueMainPoster(),
            io = Executor { it.run() },
            track = Executor { it.run() },
        )
        val xml = requireNotNull(
            TestSlots::class.java.classLoader!!.getResourceAsStream("vast/pauseroll_tracking.xml"),
        ).bufferedReader().use { it.readText() }
        return slot.setController { _, sink -> sink.deliverXml(xml) }
    }
}
