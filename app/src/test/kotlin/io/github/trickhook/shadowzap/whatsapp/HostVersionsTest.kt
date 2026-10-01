package io.github.trickhook.shadowzap.whatsapp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HostVersionsTest {
    @Test
    fun `WhatsApp version names parse`() {
        assertEquals(listOf(2, 26, 39, 11), HostVersions.parseLenient("2.26.39.11")?.segments)
        assertEquals(listOf(2, 26, 39, 9), HostVersions.parseLenient(" 2.26.39.9 ")?.segments)
        assertEquals("beta", HostVersions.parseLenient("2.26.40.2-beta")?.label)
        assertEquals(listOf(2, 26, 40, 2), HostVersions.parseLenient("2.26.40.2 (beta)")?.segments)
    }

    @Test
    fun `garbage gives null`() {
        assertNull(HostVersions.parseLenient(null))
        assertNull(HostVersions.parseLenient(""))
        assertNull(HostVersions.parseLenient("beta"))
        assertNull(HostVersions.parseLenient("99999999999.1"))
    }
}
