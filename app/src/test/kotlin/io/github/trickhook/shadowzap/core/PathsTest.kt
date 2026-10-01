package io.github.trickhook.shadowzap.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Every path stays inside the module's own directories of WhatsApp's data directory. */
class PathsTest {
    private val paths = Paths(File("/data/user/0/com.whatsapp"))

    private fun rel(file: File) = file.invariantSeparatorsPath.removePrefix("/data/user/0/com.whatsapp/")

    @Test
    fun `module roots`() {
        assertEquals("files/shadowzap", rel(paths.filesDir))
        assertEquals("cache/shadowzap", rel(paths.cacheDir))
        assertEquals("files/shadowzap/switches.json", rel(paths.switchesFile))
        assertEquals("cache/shadowzap/media", rel(paths.mediaStagingDir))
    }

    @Test
    fun `hook target paths`() {
        assertEquals(
            "cache/shadowzap/hook-targets-x-1-0123456789ab.json",
            rel(paths.hookTargetsCacheFile("x-1-0123456789ab")),
        )
        assertEquals("cache/shadowzap", rel(paths.hookTargetsCacheDir))
        assertEquals("files/shadowzap/debug/force-discovery", rel(paths.forceDiscoveryFile))
        assertFailsWith<IllegalArgumentException> { paths.hookTargetsCacheFile("../x") }
        assertFailsWith<IllegalArgumentException> { paths.hookTargetsCacheFile("") }
    }
}
