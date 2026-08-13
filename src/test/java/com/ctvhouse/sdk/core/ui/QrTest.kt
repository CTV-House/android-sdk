package com.ctvhouse.sdk.core.ui

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class QrTest {

    @Test
    fun bitmap_encodesALandingUrl() {
        assertNotNull(Qr.bitmap("https://example.com/land", 128))
    }

    @Test
    fun bitmap_rejectsBlankOrEmptySize() {
        assertNull(Qr.bitmap("  ", 128))
        assertNull(Qr.bitmap("https://example.com", 0))
    }
}
