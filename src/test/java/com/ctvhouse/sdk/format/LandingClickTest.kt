package com.ctvhouse.sdk.format

import android.content.Intent
import android.graphics.Bitmap
import com.ctvhouse.sdk.core.media.BitmapLoader
import com.ctvhouse.sdk.core.net.Client
import com.ctvhouse.sdk.core.player.FakeVideoPlayer
import com.ctvhouse.sdk.core.runtime.Clock
import com.ctvhouse.sdk.core.runtime.QueueMainPoster
import com.ctvhouse.sdk.core.ui.Chrome
import com.ctvhouse.sdk.core.ui.Ui
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.Executor

/** The landing chip is the one control that leaves the app, so what it hands the system matters. */
@RunWith(RobolectricTestRunner::class)
class LandingClickTest {

    private val app = RuntimeEnvironment.getApplication()
    private val ui = mock<Ui>()
    private val client = mock<Client>()

    @Test
    fun landing_opensTheDeviceBrowserAndClosesTheShow() {
        val events = mutableListOf<String>()
        val slot = slot().setListener(object : TriggerRoll.Listener {
            override fun onOpen() { events.add("open") }
            override fun onClose() { events.add("closed") }
        })
        slot.attach()
        slot.trigger("pause")

        val chrome = argumentCaptor<Chrome>()
        verify(ui).showImage(anyOrNull(), chrome.capture())
        assertTrue(chrome.firstValue.landingAvailable)
        assertEquals("Перейти", chrome.firstValue.landingLabel)

        slot.onLandingFromUi()

        val started = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals("https://example.com/companion-land", started.data.toString())
        assertTrue(started.hasCategory(Intent.CATEGORY_BROWSABLE))
        assertTrue(
            started.selector?.hasCategory(Intent.CATEGORY_APP_BROWSER) == true,
        )
        assertTrue(
            "the browser must not land in the host back stack",
            started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0,
        )
        verify(client).fireTrackers(eq(listOf("https://example.com/companion-click")), any())
        verify(ui).hide()
        assertEquals(listOf("open", "closed"), events)
        slot.detach()
    }

    /** A click that arrives after the show is gone has no landing URL left to open. */
    @Test
    fun landing_afterTheShowIsOver_opensNothing() {
        val slot = slot()
        slot.attach()
        slot.trigger("pause")
        slot.close()

        slot.onLandingFromUi()

        assertNull(shadowOf(app).nextStartedActivity)
        verify(client, never()).fireTrackers(
            eq(listOf("https://example.com/companion-click")),
            any(),
        )
        slot.detach()
    }

    @Test
    fun hostCanHideLanding() {
        val slot = slot().setLandingVisible(false)
        slot.attach()
        slot.trigger("pause")

        val chrome = argumentCaptor<Chrome>()
        verify(ui).showImage(anyOrNull(), chrome.capture())
        assertFalse(chrome.firstValue.landingAvailable)
        assertTrue("the info QR stays", chrome.firstValue.infoAvailable)
        slot.detach()
    }

    private fun slot(): TriggerRoll {
        val bitmaps = mock<BitmapLoader>()
        whenever(bitmaps.load(any(), any(), any())).thenReturn(mock<Bitmap>())
        return TriggerRoll(
            appContext = app,
            ui = ui,
            client = client,
            bitmapLoader = bitmaps,
            videoPlayer = FakeVideoPlayer(),
            clock = Clock { 0L },
            mainPoster = QueueMainPoster(),
            io = Executor { it.run() },
            track = Executor { it.run() },
        ).setController { _, sink -> sink.deliverXml(resource("vast/pauseroll_clicks.xml")) }
    }

    private fun resource(path: String): String =
        requireNotNull(javaClass.classLoader!!.getResourceAsStream(path))
            .bufferedReader()
            .use { it.readText() }
}
