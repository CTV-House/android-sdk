package com.ctvhouse.sdk.core.identity

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.concurrent.atomic.AtomicInteger

/**
 * The device snapshot is what every macro is filled from, so what matters here is that it is
 * populated without permissions the host may have stripped, and that it never claims an
 * advertising ID the device did not give.
 */
@RunWith(RobolectricTestRunner::class)
class IdentityTest {

    private val appContext: Context = RuntimeEnvironment.getApplication()

    @Test
    fun theSnapshotDescribesTheAppBeforeAnyLookup() {
        val device = Identity(appContext) { null }.current()

        assertEquals(appContext.packageName, device.appBundle)
        assertTrue("no user agent to send", device.userAgent.isNotEmpty())
        assertTrue("no screen size for PLAYERSIZE", device.widthPx > 0 && device.heightPx > 0)
        assertTrue("language missing", device.language.isNotEmpty())
    }

    /** Nothing is known about consent yet, and guessing in the advertiser's favour is not ours. */
    @Test
    fun trackingCountsAsLimitedUntilTheDeviceSaysOtherwise() {
        val identity = Identity(appContext) { null }

        assertTrue(identity.current().limitAdTracking)
        assertEquals("", identity.current().ifa)

        identity.refresh()

        assertTrue("a device without an ID must still report the opt-out", identity.current().limitAdTracking)
        assertEquals("", identity.current().ifa)
    }

    @Test
    fun anIdFromTheDeviceReachesTheSnapshot() {
        val identity = Identity(appContext) { AdId("aa-bb-cc", "gaid", limitAdTracking = false) }

        identity.refresh()

        assertEquals("aa-bb-cc", identity.current().ifa)
        assertEquals("gaid", identity.current().ifaType)
        assertFalse(identity.current().limitAdTracking)
    }

    /** Binding a system service is expensive; a second screen event must not pay for it again. */
    @Test
    fun theDeviceIsAskedForItsIdOnce() {
        val calls = AtomicInteger()
        val identity = Identity(appContext) {
            calls.incrementAndGet()
            AdId("id", "gaid", limitAdTracking = false)
        }

        identity.refresh()
        identity.refresh()

        assertEquals(1, calls.get())
    }

    /** A host that manages consent itself knows more than we can see from here. */
    @Test
    fun anIdFromTheHostWinsOverThePlatform() {
        val identity = Identity(appContext) { AdId("platform-id", "gaid", limitAdTracking = false) }
        identity.refresh()

        identity.setIfa("host-id")

        assertEquals("host-id", identity.current().ifa)
        assertEquals("host", identity.current().ifaType)
        assertFalse(identity.current().limitAdTracking)

        identity.setIfa("")

        assertEquals("clearing the override must fall back to the device", "platform-id", identity.current().ifa)
    }

    /** A lookup that throws is a missing ID, not a crash in the host process. */
    @Test
    fun aFailingLookupLeavesTheSnapshotUsable() {
        val identity = Identity(appContext) { error("no service") }

        identity.refresh()

        assertEquals("", identity.current().ifa)
        assertTrue(identity.current().limitAdTracking)
        assertEquals(appContext.packageName, identity.current().appBundle)
    }

    /** Construction happens on the main thread, so the binder-backed fields wait for the lookup. */
    @Test
    fun theBinderBackedFieldsArriveWithTheLookup() {
        val identity = Identity(appContext) { null }
        assertEquals("", identity.current().appName)

        identity.refresh()

        assertTrue("app label missing", identity.current().appName.isNotEmpty())
    }

    @Test
    fun theHostSuppliedIpIsReportedAsGiven() {
        val identity = Identity(appContext) { null }

        identity.setIp("  1.2.3.4 ")

        assertEquals("1.2.3.4", identity.current().ip)
    }
}
