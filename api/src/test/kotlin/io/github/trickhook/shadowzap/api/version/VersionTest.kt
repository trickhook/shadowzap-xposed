package io.github.trickhook.shadowzap.api.version

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VersionTest {
    private fun v(value: String) = Version.parse(value)

    @Test
    fun `trailing zeros are insignificant for equals, hashCode and compareTo`() {
        val pairs = listOf("1.0" to "1.0.0", "1" to "1.0.0.0", "2.1-rc1" to "2.1.0-rc1", "1.02" to "1.2")
        for ((a, b) in pairs) {
            assertEquals(v(a), v(b), "$a == $b")
            assertEquals(v(a).hashCode(), v(b).hashCode(), "hash($a) == hash($b)")
            assertEquals(0, v(a).compareTo(v(b)), "$a <=> $b")
        }
        assertEquals(setOf(v("1.0")), setOf(v("1.0.0")))
    }

    @Test
    fun `toString keeps the written segments`() {
        assertEquals("1.0", v("1.0").toString())
        assertEquals("1.2.0-beta10", v("1.2.0-beta10").toString())
    }

    @Test
    fun `numeric ordering`() {
        val sorted = listOf("1.10", "1.2", "0.9.9", "1.2.1", "2").map(::v).sorted().map(Version::toString)
        assertEquals(listOf("0.9.9", "1.2", "1.2.1", "1.10", "2"), sorted)
    }

    @Test
    fun `prereleases sort before the release and by digit runs`() {
        assertTrue(v("1.0-rc") < v("1.0"))
        assertTrue(v("1.0-beta2") < v("1.0-beta10"))
        assertTrue(v("1.0-beta") < v("1.0-beta1"))
        assertTrue(v("1.0-alpha9") < v("1.0-beta1"))
        assertEquals(0, v("1.0-rc01").compareTo(v("1.0-rc1")))
        assertEquals(v("1.0-rc01"), v("1.0.0-rc1"))
        assertEquals(v("1.0-rc01").hashCode(), v("1.0.0-rc1").hashCode())
        assertEquals("1.0-rc01", v("1.0-rc01").toString())
        assertNotEquals(v("1.0"), v("1.0-rc"))
        assertNotEquals(v("1.0-rc1"), v("1.0-rc10"))
    }

    @Test
    fun `invalid versions are rejected`() {
        for (bad in listOf("", "1..2", "a.b", "1.0-", "1.0-RC", "1.0-rc.1", "-1", "1.x", "99999999999")) {
            assertFailsWith<IllegalArgumentException>(bad) { v(bad) }
            assertNull(Version.parseOrNull(bad))
        }
    }

    @Test
    fun `withoutLabel and of`() {
        assertEquals(Version.of(1, 2), v("1.2-rc").withoutLabel())
        assertTrue(v("1.2-rc").isPrerelease)
    }
}

class VersionRangeTest {
    private fun r(value: String) = VersionRange.parse(value)
    private fun v(value: String) = Version.parse(value)

    @Test
    fun `wildcard accepts everything`() {
        assertTrue(r("*").isAny)
        assertTrue(r(" * ").satisfies(v("0.0.1-alpha")))
        assertEquals(VersionRange.ANY, r("*"))
    }

    @Test
    fun `bounds are a conjunction evaluated without labels`() {
        val range = r(">=1.0 <2")
        assertTrue(range.satisfies(v("1.0")))
        assertTrue(range.satisfies(v("1.5-rc")))
        assertTrue(range.satisfies(v("1.99.99")))
        assertTrue(!range.satisfies(v("2.0-rc")))
        assertTrue(!range.satisfies(v("2")))
        assertTrue(!range.satisfies(v("0.9")))
        assertTrue(r("=1.0").satisfies(v("1.0.0")))
        assertTrue(r(">1").satisfies(v("1.0.1")))
        assertTrue(r("<=1.0").satisfies(v("1")))
    }

    @Test
    fun `equality ignores bound order and trailing zeros`() {
        assertEquals(r(">=1.0 <2"), r("<2.0 >=1"))
        assertEquals(r(">=1.0 <2").hashCode(), r("<2.0 >=1").hashCode())
        assertEquals(">=1.0 <2", r(">=1.0   <2").toString())
    }

    @Test
    fun `invalid ranges are rejected`() {
        for (bad in listOf("", "  ", "^1.0", "~1", "1.0", ">=1.0-rc", ">=", ">=1.0 <")) {
            assertFailsWith<IllegalArgumentException>(bad) { r(bad) }
            assertNull(VersionRange.parseOrNull(bad))
        }
    }
}

