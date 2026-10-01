package io.github.trickhook.shadowzap.core

import io.github.trickhook.shadowzap.testing.TestEnvs
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AtomicFilesTest {
    private val root = TestEnvs.tempDir("atomic-files")

    @Test
    fun `write creates parent directories and replaces content`() {
        val target = File(root, "a/b/file.txt")
        AtomicFiles.writeText(target, "one")
        AtomicFiles.writeText(target, "two")
        assertEquals("two", target.readText())
        assertEquals(listOf("file.txt"), target.parentFile!!.list()!!.toList())
    }

    @Test
    fun `a failing writer leaves the old content and no temporary file`() {
        val target = File(root, "config.json").apply { writeText("old") }
        assertFailsWith<IllegalStateException> {
            AtomicFiles.write(target) { out ->
                out.write("partial".toByteArray())
                error("disk full")
            }
        }
        assertEquals("old", target.readText())
        assertEquals(listOf("config.json"), root.list()!!.toList())
    }

    @Test
    fun `replacing a directory is refused`() {
        val dir = File(root, "dir").apply { mkdirs() }
        assertFailsWith<IOException> { AtomicFiles.writeText(dir, "x") }
    }

    @Test
    fun `readTextOrNull tolerates missing files and directories`() {
        assertNull(AtomicFiles.readTextOrNull(File(root, "missing")))
        assertNull(AtomicFiles.readTextOrNull(root))
    }

    @Test
    fun `quarantine moves a file aside`() {
        val file = File(root, "states").apply { writeText("garbage") }
        val aside = AtomicFiles.quarantine(file)
        assertNotNull(aside)
        assertFalse(file.exists())
        assertTrue(aside.name.startsWith("states.corrupt."))
    }

    @Test
    fun `ensureDirectory replaces a file in the way`() {
        val path = File(root, "preloads").apply { writeText("not a dir") }
        path.ensureDirectory()
        assertTrue(path.isDirectory)
    }

    @Test
    fun `containsPath resolves traversal`() {
        val base = File(root, "fonts").apply { mkdirs() }
        assertTrue(base.containsPath(File(base, "set/font.ttf")))
        assertFalse(base.containsPath(File(base, "../outside.ttf")))
        assertFalse(base.containsPath(File(root, "fonts-other/x")))
    }
}
