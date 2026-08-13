package com.ctvhouse.sdk.core.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TemplatesTest {

    @Test
    fun marking_withErid() {
        assertEquals(
            "РЕКЛАМА ABC",
            Templates.marking("РЕКЛАМА \${ERID}", "ABC"),
        )
    }

    @Test
    fun marking_withoutErid_trims() {
        assertEquals(
            "РЕКЛАМА",
            Templates.marking("РЕКЛАМА \${ERID}", null),
        )
    }

    @Test
    fun skipCountdown_seconds() {
        assertEquals(
            "Пропустить через 3",
            Templates.skipCountdown("Пропустить через \${SECONDS}", 3),
        )
    }
}
