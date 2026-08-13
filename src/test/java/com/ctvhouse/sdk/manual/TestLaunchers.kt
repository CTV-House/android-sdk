package com.ctvhouse.sdk.manual

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
 * Wiring for tests that cannot build a launcher themselves: the collaborators are `internal`, so a
 * Java test has no way to name them.
 */
internal object TestLaunchers {

    @JvmStatic
    fun switchRollAd(): SwitchRollAd = SwitchRollAd(
        appContext = mock<Context>(),
        ui = ui(),
        client = mock<Client>(),
        bitmapLoader = mock<BitmapLoader>(),
        videoPlayer = FakeVideoPlayer(),
        clock = Clock { 0L },
        mainPoster = QueueMainPoster(),
        io = Executor { it.run() },
        track = Executor { it.run() },
    )

    @JvmStatic
    fun pauseRollAd(): PauseRollAd = PauseRollAd(
        appContext = mock<Context>(),
        ui = ui(),
        client = mock<Client>(),
        bitmapLoader = mock<BitmapLoader>(),
        videoPlayer = FakeVideoPlayer(),
        clock = Clock { 0L },
        mainPoster = QueueMainPoster(),
        io = Executor { it.run() },
        track = Executor { it.run() },
    )

    private fun ui(): Ui = mock<Ui>().also { whenever(it.videoSurface()).thenReturn(mock()) }
}
