package io.github.trickhook.shadowzap.entry

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BootGuardTest {
    @BeforeTest
    @AfterTest
    fun reset() {
        System.clearProperty(BootGuard.PROPERTY)
    }

    @Test
    fun `only the first entry boots`() {
        assertNull(BootGuard.claimedBy())
        assertTrue(BootGuard.claim("MODERN"))
        assertFalse(BootGuard.claim("LEGACY"))
        assertFalse(BootGuard.claim("MODERN"))
        assertEquals("MODERN", BootGuard.claimedBy())
    }

    @Test
    fun `the guard holds across class loaders because it lives in system properties`() {
        System.setProperty(BootGuard.PROPERTY, "LEGACY")
        assertFalse(BootGuard.claim("MODERN"))
    }
}
