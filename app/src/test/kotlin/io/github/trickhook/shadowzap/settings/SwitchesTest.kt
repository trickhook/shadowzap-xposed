package io.github.trickhook.shadowzap.settings

import io.github.trickhook.shadowzap.core.Paths
import io.github.trickhook.shadowzap.testing.TestEnvs
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SwitchesTest {
    private val status = Switches.STATUS_DOWNLOAD
    private val images = Switches.HD_IMAGES
    private val videos = Switches.HD_VIDEOS
    private val noTrim = Switches.NO_STATUS_TRIM
    private val allowDebug = Switches.ALLOW_DEBUG
    private val antiRevoke = Switches.ANTI_REVOKE
    private val seeEdited = Switches.SEE_EDITED
    private val viewOnce = Switches.VIEW_ONCE_BYPASS
    private val viewOnceSave = Switches.VIEW_ONCE_SAVE
    private val autoPreview = Switches.MEDIA_AUTOPREVIEW
    private val ghostMode = Switches.GHOST_MODE
    private val propsUnlock = Switches.PROPS_UNLOCK
    private val stripFlagSecure = Switches.STRIP_FLAG_SECURE
    private val anonymousStatus = Switches.ANONYMOUS_STATUS
    private val unlimitedPins = Switches.UNLIMITED_PINS

    @AfterTest
    fun forget() = SwitchStore.reset()

    @Test
    fun `feature ids and defaults`() {
        assertEquals("media.statusDownload", status)
        assertEquals("media.hdImages", images)
        assertEquals("media.hdVideos", videos)
        assertEquals("media.noStatusTrim", noTrim)
        assertEquals("developer.allowDebug", allowDebug)
        assertEquals("privacy.antiRevoke", antiRevoke)
        assertEquals("privacy.seeEdited", seeEdited)
        assertEquals("privacy.viewOnceBypass", viewOnce)
        assertEquals("privacy.viewOnceSave", viewOnceSave)
        assertEquals("media.autoPreview", autoPreview)
        assertEquals("privacy.ghostMode", ghostMode)
        assertEquals("developer.propsUnlock", propsUnlock)
        assertEquals("privacy.stripFlagSecure", stripFlagSecure)
        assertEquals("status.anonymousView", anonymousStatus)
        assertEquals("chat.pinUnlimited", unlimitedPins)
        assertEquals(
            mapOf(
                status to true,
                images to true,
                videos to true,
                noTrim to true,
                allowDebug to false,
                antiRevoke to true,
                seeEdited to true,
                viewOnce to true,
                viewOnceSave to true,
                autoPreview to true,
                ghostMode to true,
                propsUnlock to false,
                stripFlagSecure to true,
                anonymousStatus to true,
                unlimitedPins to true,
            ),
            Switches.DEFAULTS,
        )
    }

    @Test
    fun `the switch file lives in files shadowzap`() {
        val dataDir = File("/data/user/0/com.whatsapp")
        assertEquals(File(dataDir, "files/shadowzap/switches.json"), Paths(dataDir).switchesFile)
    }

    @Test
    fun `explicit switches win and missing ones keep their default`() {
        val switches = Switches.parse("""{"version":1,"enabled":{"$status":false,"$images":true,"future.thing":true}}""")
        assertFalse(switches.isEnabled(status))
        assertTrue(switches.isEnabled(images))
        assertTrue(switches.isEnabled(videos), "not in the file: default")
        assertTrue(switches.isEnabled("future.thing"), "unknown ids are kept")
        assertFalse(switches.isEnabled("some.unknown"), "unknown and not in the file: off")
        assertTrue(switches.problems.isEmpty(), switches.problems.toString())
        assertEquals(mapOf(status to false, images to true, "future.thing" to true), switches.explicit)
    }

    @Test
    fun `lenient values - strings and 0 or 1 are booleans, anything else keeps the default`() {
        val switches = Switches.parse(
            """{"version":1,"enabled":{"$status":"FALSE","$images":0,"$videos":"maybe","a":1,"b":null,"c":{},"d":2}}""",
        )
        assertFalse(switches.isEnabled(status))
        assertFalse(switches.isEnabled(images))
        assertTrue(switches.isEnabled(videos), "unparseable value: default")
        assertTrue(switches.isEnabled("a"))
        assertFalse(switches.isEnabled("b"))
        assertEquals(setOf(status, images, "a"), switches.explicit.keys)
        assertEquals(4, switches.problems.size, switches.problems.toString())
    }

    @Test
    fun `unreadable content gives the defaults`() {
        for (text in listOf("", "not json", "[1,2]", "\"x\"", "{\"version\":1}", "{\"version\":1,\"enabled\":[true]}", "{")) {
            val switches = Switches.parse(text)
            assertTrue(switches.explicit.isEmpty(), text)
            Switches.DEFAULTS.forEach { (id, default) -> assertEquals(default, switches.isEnabled(id), "$text / $id") }
            assertTrue(switches.problems.isNotEmpty(), text)
            assertTrue(switches.origin.startsWith("defaults"), switches.origin)
        }
    }

    @Test
    fun `a byte order mark, a missing or other version and extra keys are tolerated`() {
        val bom = Switches.parse("﻿{\"version\":1,\"enabled\":{\"$status\":false}}")
        assertFalse(bom.isEnabled(status))
        assertTrue(bom.problems.isEmpty())

        val noVersion = Switches.parse("""{"enabled":{"$status":false},"updatedAt":123}""")
        assertFalse(noVersion.isEnabled(status))
        assertEquals(1, noVersion.problems.size)

        val future = Switches.parse("""{"version":2,"enabled":{"$videos":false}}""")
        assertFalse(future.isEnabled(videos))
        assertTrue(future.problems.single().startsWith("version 2"), future.problems.toString())
    }

    @Test
    fun `reading a file - missing, oversized, a directory or valid`() {
        val dir = TestEnvs.tempDir("switches")
        val missing = Switches.read(File(dir, "switches.json"))
        assertEquals("defaults (no file)", missing.origin)
        assertTrue(missing.isEnabled(status))

        val directory = File(dir, "dir.json").apply { mkdirs() }
        assertEquals("defaults (not a file)", Switches.read(directory).origin)

        val big = File(dir, "big.json").apply { writeText("{" + " ".repeat(Switches.MAX_BYTES.toInt()) + "}") }
        assertTrue(Switches.read(big).origin.startsWith("defaults (file larger"))

        val valid = File(dir, "valid.json").apply { writeText("""{"version":1,"enabled":{"$images":false}}""") }
        val switches = Switches.read(valid)
        assertFalse(switches.isEnabled(images))
        assertEquals(valid.path, switches.origin)
        val line = switches.describe()
        assertTrue("$images=off" in line, line)
        assertTrue("$status=on (default)" in line, line)
    }

    @Test
    fun `the store reads once per process and set writes through`() {
        val dataDir = TestEnvs.tempDir("switches-store")
        val file = Paths(dataDir).switchesFile
        file.parentFile!!.mkdirs()
        file.writeText("""{"version":1,"enabled":{"$status":false,"future.thing":true}}""")
        val env = TestEnvs.env(dataDir)
        assertFalse(SwitchStore.isEnabled(env, status))
        assertTrue(SwitchStore.isEnabled(env, videos))

        file.writeText("""{"version":1,"enabled":{"$status":true}}""")
        assertFalse(SwitchStore.isEnabled(env, status), "an outside change applies from the next start")

        SwitchStore.set(dataDir, videos, false)
        assertFalse(SwitchStore.isEnabled(env, videos), "set applies immediately")

        SwitchStore.reset()
        val reread = Switches.read(file)
        assertFalse(reread.isEnabled(videos))
        assertFalse(reread.isEnabled(status), "set wrote the in-memory state, not the outside change")
        assertTrue(reread.isEnabled("future.thing"), "unknown ids survive a write")
    }
}
