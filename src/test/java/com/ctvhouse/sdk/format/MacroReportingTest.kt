package com.ctvhouse.sdk.format

import android.content.Context
import com.ctvhouse.sdk.core.identity.AdId
import com.ctvhouse.sdk.core.identity.Identity
import com.ctvhouse.sdk.core.media.BitmapLoader
import com.ctvhouse.sdk.core.net.Client
import com.ctvhouse.sdk.core.player.FakeVideoPlayer
import com.ctvhouse.sdk.core.runtime.Clock
import com.ctvhouse.sdk.core.runtime.QueueMainPoster
import com.ctvhouse.sdk.core.ui.Ui
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.concurrent.Executor

/**
 * A tag URL used to be the only place macros were filled, which left `[IFA]` sitting in the
 * pixels the same response asked us to fire. Every URL the library sends now goes through the
 * same device snapshot.
 */
class MacroReportingTest {

    private lateinit var ui: Ui
    private lateinit var videoPlayer: FakeVideoPlayer
    private lateinit var slot: TriggerRoll
    private val pinged = mutableListOf<String>()

    @Before
    fun setUp() {
        pinged.clear()
        ui = mock()
        whenever(ui.videoSurface()).thenReturn(mock())
        videoPlayer = FakeVideoPlayer()
        val client = mock<Client>()
        doAnswer { inv ->
            pinged.addAll(inv.getArgument<List<String>>(0))
            null
        }.whenever(client).fireTrackers(any(), any())

        val identity = Identity(mock<Context>()) { AdId("aa-bb-cc", "gaid", limitAdTracking = false) }
        identity.setIp("1.2.3.4")
        slot = TriggerRoll(
            appContext = mock<Context>(),
            ui = ui,
            client = client,
            bitmapLoader = mock<BitmapLoader>(),
            videoPlayer = videoPlayer,
            clock = Clock { 1_700_000_000_000L },
            mainPoster = QueueMainPoster(),
            io = Executor { it.run() },
            track = Executor { it.run() },
            identity = identity,
        )
        slot.setController { _, sink -> sink.deliverXml(resource("vast/pauseroll_macros.xml")) }
    }

    @Test
    fun anImpressionPixelCarriesTheDeviceNotTheTokens() {
        slot.attach()
        slot.trigger("pause")

        val impression = pinged.first { it.startsWith("https://example.com/imp") }
        assertTrue("device ID missing: $impression", impression.contains("ifa=aa-bb-cc"))
        assertTrue("opt-out flag missing: $impression", impression.contains("lmt=0"))
        assertTrue(
            "cache buster missing: $impression",
            Regex("cb=\\d{8}").containsMatchIn(impression),
        )
        assertFalse(impression.contains("["))
    }

    @Test
    fun anEventTrackerIsFilledTheSameWay() {
        slot.attach()
        slot.trigger("pause")

        val start = pinged.first { it.startsWith("https://example.com/start") }
        assertFalse("tokens left in $start", start.contains("\${"))
        assertTrue("user agent missing: $start", start.contains("ua="))
    }

    @Test
    fun theCreativeUrlHandedToThePlayerIsExpanded() {
        slot.attach()
        slot.trigger("pause")

        val played = videoPlayer.playedUrl
        assertNotNull(played)
        assertTrue("creative kept its token: $played", played!!.contains("ifa=aa-bb-cc"))
    }

    @Test
    fun theLandingUrlBehindTheQrIsExpanded() {
        slot.attach()
        slot.trigger("pause")

        slot.onInfoFromUi()

        verify(ui).showQr("https://example.com/land?ifa=aa-bb-cc")
        assertTrue(pinged.any { it.startsWith("https://example.com/click?cb=") })
    }

    /** Each pixel gets its own cache buster; a shared one would collapse into a single request. */
    @Test
    fun everyPixelGetsItsOwnCacheBuster() {
        slot.attach()
        slot.trigger("pause")
        slot.onInfoFromUi()

        val busters = pinged.mapNotNull { Regex("cb=(\\d{8})").find(it)?.groupValues?.get(1) }
        assertTrue("expected several busted URLs: $pinged", busters.size >= 2)
        assertTrue("cache busters repeated: $busters", busters.toSet().size == busters.size)
    }

    private fun resource(path: String): String =
        requireNotNull(javaClass.classLoader!!.getResourceAsStream(path))
            .bufferedReader()
            .use { it.readText() }
}
