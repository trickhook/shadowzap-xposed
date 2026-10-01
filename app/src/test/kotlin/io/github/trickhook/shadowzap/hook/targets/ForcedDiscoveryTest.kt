package io.github.trickhook.shadowzap.hook.targets

import io.github.trickhook.shadowzap.api.host.LogLevel
import io.github.trickhook.shadowzap.testing.RecordingLogger
import io.github.trickhook.shadowzap.testing.TestEnvs
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ForcedDiscoveryTest {
    private val dir = TestEnvs.tempDir("forced")
    private val log = RecordingLogger()

    @Test
    fun `ids one per line, trimmed, blank lines and a BOM ignored`() {
        val parsed = ForcedDiscovery.parse("\uFEFF  theme.darker \r\n\r\n\tscript.fromFile\n\nfonts.manager")
        assertFalse(parsed.all)
        assertEquals(listOf("theme.darker", "script.fromFile", "fonts.manager"), parsed.ids.toList())
        assertTrue(parsed.covers("script.fromFile"))
        assertFalse(parsed.covers("theme"))
        assertFalse(parsed.covers("theme.darker.extra"))
        assertFalse(parsed.isEmpty)
    }

    @Test
    fun `a star line covers every id, next to explicit ids`() {
        val parsed = ForcedDiscovery.parse("a.b\n*\n")
        assertTrue(parsed.all)
        assertEquals(setOf("a.b"), parsed.ids)
        assertTrue(parsed.covers("anything.at.all"))
        assertFalse(ForcedDiscovery.parse("a*\n**").all)
    }

    @Test
    fun `empty text forces nothing`() {
        for (text in listOf("", "\n\n", "   \r\n\t", "\uFEFF")) {
            val parsed = ForcedDiscovery.parse(text)
            assertTrue(parsed.isEmpty, text)
            assertFalse(parsed.covers("x"))
        }
        assertTrue(ForcedDiscovery.NONE.isEmpty)
    }

    @Test
    fun `reading is tolerant and logged`() {
        assertSame(ForcedDiscovery.NONE, ForcedDiscovery.read(null, log))
        assertSame(ForcedDiscovery.NONE, ForcedDiscovery.read(File(dir, "absent"), log))
        assertSame(ForcedDiscovery.NONE, ForcedDiscovery.read(File(dir, "a-directory").apply { mkdirs() }, log))

        val huge = File(dir, "huge").apply { writeText("x\n".repeat(40_000)) }
        assertSame(ForcedDiscovery.NONE, ForcedDiscovery.read(huge, log))
        assertTrue(log.entries.any { it.first == LogLevel.WARN && "is larger than" in it.second })

        val file = File(dir, "force-discovery").apply { writeText("theme.darker\n*\n") }
        val read = ForcedDiscovery.read(file, log)
        assertTrue(read.all)
        assertTrue(log.entries.any { it.second == "Debug: ${file.path} forces discovery for every target" })

        File(dir, "some").writeText("a.b\nc.d\n")
        ForcedDiscovery.read(File(dir, "some"), log)
        assertTrue(log.entries.any { it.second.endsWith("forces discovery for a.b, c.d") })

        val empty = File(dir, "empty").apply { writeText("\n") }
        val before = log.entries.size
        assertTrue(ForcedDiscovery.read(empty, log).isEmpty)
        assertEquals(before, log.entries.size)
    }
}
