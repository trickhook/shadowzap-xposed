package io.github.trickhook.shadowzap.hook.targets

import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.IOException
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DexClassNamesTest {
    /** The classes of the d8 fixtures (see `hook-targets/fixture-src.txt`), in definition order. */
    private val fixtureClasses = listOf(
        "com.android.tools.r8.annotations.LambdaMethod",
        "fixture.alpha.Kind",
        "fixture.alpha.Outer\$0",
        "fixture.alpha.Outer\$Callback",
        "fixture.alpha.Outer\$Inner",
        "fixture.alpha.Outer",
        "fixture.beta.Caf\u00E9",
        "fixture.beta.\u6570\u636E",
        "fixture.beta.\uD835\uDD18nicode",
    )

    private fun read(bytes: ByteArray, size: Long = bytes.size.toLong(), counter: ReadCounter? = null): List<String> =
        DexClassNames.read(size) {
            val stream = ByteArrayInputStream(bytes)
            counter?.wrap(stream) ?: stream
        }

    @Test
    fun `reads the classes of real d8 output in every supported version`() {
        for (version in listOf("035", "039", "041")) {
            val bytes = TestDex.resource("fixture-$version.dex")
            assertEquals(fixtureClasses, read(bytes), "dex $version")
            // An unknown length (a stream of unknown size) reads the same.
            assertEquals(fixtureClasses, read(bytes, size = -1), "dex $version, size unknown")
        }
    }

    @Test
    fun `reads a compressed stream`() {
        val bytes = TestDex.resource("fixture-039.dex")
        val deflated = java.io.ByteArrayOutputStream().also { out ->
            DeflaterOutputStream(out, Deflater(Deflater.BEST_COMPRESSION)).use { it.write(bytes) }
        }.toByteArray()
        val names = DexClassNames.read(bytes.size.toLong()) { InflaterInputStream(ByteArrayInputStream(deflated)) }
        assertEquals(fixtureClasses, names)
    }

    @Test
    fun `modified UTF-8 names decode, including NUL and surrogate pairs`() {
        val names = listOf(
            "a.Plain",
            "a.b.Outer\$Inner\$1",
            "a.Caf\u00E9",
            "a.\u6570\u636E",
            "a.\uD835\uDD18nicode",
            "a.With\u0000Nul",
        )
        assertEquals(names, read(TestDex.dex(names)))
    }

    @Test
    fun `class definition order is kept and duplicates of one descriptor both decode`() {
        val names = listOf("z.Last", "a.First", "m.Middle", "a.First")
        assertEquals(names, read(TestDex.dex(names)))
    }

    @Test
    fun `reads only the tables and the descriptors, skipping the rest of the file`() {
        val names = (1..300).map { "com.example.pkg$it.Class$it" }
        val bytes = TestDex.dex(names, padding = 4 * 1024 * 1024)
        val counter = ReadCounter()
        assertEquals(names, read(bytes, counter = counter))
        assertTrue(counter.bytesRead < 200_000, "read ${counter.bytesRead} of ${bytes.size} bytes")
        // Forward reading reopens once, to go back to string_ids after class_defs.
        assertEquals(2, counter.opens)
    }

    @Test
    fun `string data before the tables is reached by reopening the stream`() {
        val names = listOf("b.Two", "a.One", "c.Three")
        val counter = ReadCounter()
        assertEquals(names, read(TestDex.dex(names, stringDataFirst = true), counter = counter))
        assertEquals(3, counter.opens)
    }

    @Test
    fun `dex 041 containers yield the classes of every section`() {
        val sections = listOf(listOf("q.C8", "q.C9"), listOf("q.C0", "q.C1", "q.C2"), listOf("r.Shared\$Inner"))
        val counter = ReadCounter()
        assertEquals(sections.flatten(), read(TestDex.container(sections), counter = counter))
        assertTrue(counter.opens >= sections.size, "opens: ${counter.opens}")
    }

    @Test
    fun `other dex versions are refused`() {
        for (version in listOf("034", "042", "0a5")) {
            assertFailsWith<DexFormatException>(version) { read(TestDex.dex(listOf("a.B"), version = version)) }
        }
        val latest = TestDex.dex(listOf("a.B"), version = "040")
        assertEquals(listOf("a.B"), read(latest))
    }

    @Test
    fun `malformed files fail with an IOException instead of garbage`() {
        val good = TestDex.dex(listOf("a.B", "c.D"))

        assertFailsWith<DexFormatException>("magic") { read("PK\u0003\u0004 not a dex".toByteArray() + ByteArray(200)) }
        assertFailsWith<DexFormatException>("endian") {
            read(TestDex.dex(listOf("a.B"), endian = 0x78563412))
        }
        assertFailsWith<IOException>("truncated") { read(good.copyOf(good.size - 3), size = -1) }
        assertFailsWith<IOException>("empty") { read(ByteArray(0)) }
        assertFailsWith<EOFException>("short header") { read(good.copyOf(0x40), size = -1) }

        // A class_def naming a type past type_ids.
        val badType = good.copyOf().also { putInt(it, u32(it, 0x64).toInt(), 99) }
        assertFailsWith<DexFormatException>("type index") { read(badType) }

        // A string_ids entry pointing past the end of the file.
        val badString = good.copyOf().also { putInt(it, u32(it, 0x3C).toInt(), it.size + 10) }
        assertFailsWith<DexFormatException>("string offset") { read(badString) }

        // class_defs extending past the file.
        val badTable = good.copyOf().also { putInt(it, 0x60, 1_000) }
        assertFailsWith<DexFormatException>("class_defs size") { read(badTable) }

        // A file size larger than the stream.
        val badSize = good.copyOf().also { putInt(it, 0x20, it.size + 1) }
        assertFailsWith<DexFormatException>("file size") { read(badSize) }

        // A declared string length that disagrees with the bytes.
        val badLength = good.copyOf().also { bytes ->
            val firstString = u32(bytes, u32(bytes, 0x3C).toInt()).toInt()
            bytes[firstString] = (bytes[firstString] + 1).toByte()
        }
        assertFailsWith<DexFormatException>("string length") { read(badLength) }
    }

    @Test
    fun `a dex 041 section must name its own offset`() {
        val bytes = TestDex.container(listOf(listOf("a.B"), listOf("c.D")))
        val second = u32(bytes, 0x20).toInt()
        putInt(bytes, second + 0x74, 0)
        assertFailsWith<DexFormatException> { read(bytes) }
    }

    private fun putInt(out: ByteArray, at: Int, value: Int) {
        for (i in 0 until 4) out[at + i] = (value ushr (8 * i)).toByte()
    }

    private fun u32(bytes: ByteArray, at: Int): Long =
        (0 until 4).fold(0L) { acc, i -> acc or ((bytes[at + i].toLong() and 0xFF) shl (8 * i)) }
}
