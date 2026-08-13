package com.ctvhouse.sdk.core.identity

import android.content.Context
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * What the device reports about its advertising ID decides whether a viewer can be targeted, so
 * the opt-out paths matter more here than the happy one.
 */
@RunWith(RobolectricTestRunner::class)
class AdvertisingIdTest {

    private val appContext: Context = RuntimeEnvironment.getApplication()

    @Test
    fun anIdFromTheDeviceIsPassedOnAsIs() {
        val id = AdvertisingId.adId(" aa-bb-cc ", "gaid", limited = false)

        assertEquals("aa-bb-cc", id?.value)
        assertEquals("gaid", id?.type)
        assertFalse(id!!.limitAdTracking)
    }

    /** The zeroed ID is what an opted-out device answers; forwarding it would fake an audience. */
    @Test
    fun theZeroedIdIsNotForwarded() {
        val id = AdvertisingId.adId("00000000-0000-0000-0000-000000000000", "gaid", limited = false)

        assertEquals("", id?.value)
        assertEquals("", id?.type)
        assertTrue(id!!.limitAdTracking)
    }

    @Test
    fun anOptedOutDeviceReportsTheOptOutAndNoId() {
        val id = AdvertisingId.adId("aa-bb-cc", "gaid", limited = true)

        assertEquals("", id?.value)
        assertTrue(id!!.limitAdTracking)
    }

    @Test
    fun noIdAtAllIsNotAnOptOutClaim() {
        assertNull(AdvertisingId.adId(null, "gaid", limited = false))
        assertNull(AdvertisingId.adId("   ", "gaid", limited = false))
    }

    /** No Play Services on the device: the Amazon settings pair is the second source. */
    @Test
    fun withoutPlayServicesTheSystemSettingsAreRead() {
        Settings.Secure.putString(appContext.contentResolver, "advertising_id", "amazon-id")
        Settings.Secure.putInt(appContext.contentResolver, "limit_ad_tracking", 0)

        val id = AdvertisingId.resolve(appContext)

        assertEquals("amazon-id", id?.value)
        assertEquals("afai", id?.type)
        assertFalse(id!!.limitAdTracking)
    }

    @Test
    fun aDeviceWithNeitherSourceHasNoId() {
        assertNull(AdvertisingId.resolve(appContext))
    }

    @Test
    fun anOptOutInTheSystemSettingsIsHonoured() {
        Settings.Secure.putString(appContext.contentResolver, "advertising_id", "amazon-id")
        Settings.Secure.putInt(appContext.contentResolver, "limit_ad_tracking", 1)

        val id = AdvertisingId.resolve(appContext)

        assertEquals("", id?.value)
        assertTrue(id!!.limitAdTracking)
    }
}
