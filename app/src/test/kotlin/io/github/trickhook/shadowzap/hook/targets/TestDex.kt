package io.github.trickhook.shadowzap.hook.targets

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes minimal dex files for tests: a header, `string_ids`, `type_ids`, `class_defs` and the descriptors'
 * `string_data`, the parts [DexClassNames] reads, laid out like d8 does unless a test asks otherwise. Real d8
 * output is covered by the fixtures in `src/test/resources/hook-targets`.
 */
internal object TestDex {
    private const val HEADER = 0x70
    private const val CONTAINER_HEADER = 0x78
    private const val NO_INDEX = -1

    /**
     * A classic dex defining [classNames] (binary names) in that order. [stringDataFirst] puts the string data right
     * after the header (before every table); [padding] zero bytes sit between the tables and the string data, like
     * the code of a real dex.
     */
    fun dex(
        classNames: List<String>,
        version: String = "035",
        stringDataFirst: Boolean = false,
        padding: Int = 0,
        endian: Int = 0x12345678,
    ): ByteArray {
        val descriptors = classNames.map(::descriptor).distinct().sorted()
        val stringData = descriptors.map(::stringDataItem)
        val stringDataSize = stringData.sumOf { it.size }
        val n = descriptors.size

        val stringDataOff: Int
        val stringIdsOff: Int
        if (stringDataFirst) {
            stringDataOff = HEADER
            stringIdsOff = align4(stringDataOff + stringDataSize)
        } else {
            stringIdsOff = HEADER
            stringDataOff = -1 // after the tables
        }
        val typeIdsOff = stringIdsOff + 4 * n
        val classDefsOff = typeIdsOff + 4 * n
        val tablesEnd = classDefsOff + 32 * classNames.size
        val dataOff = if (stringDataFirst) stringDataOff else tablesEnd + padding
        val fileSize = if (stringDataFirst) tablesEnd + padding else dataOff + stringDataSize

        val out = ByteArray(fileSize)
        header(out, 0, version, fileSize, HEADER, endian)
        putInt(out, 0x38, n)
        putInt(out, 0x3C, stringIdsOff)
        putInt(out, 0x40, n)
        putInt(out, 0x44, typeIdsOff)
        putInt(out, 0x60, classNames.size)
        putInt(out, 0x64, classDefsOff)
        putInt(out, 0x68, stringDataSize)
        putInt(out, 0x6C, dataOff)
        var at = dataOff
        for ((i, item) in stringData.withIndex()) {
            putInt(out, stringIdsOff + 4 * i, at)
            item.copyInto(out, at)
            at += item.size
        }
        for (i in 0 until n) putInt(out, typeIdsOff + 4 * i, i)
        for ((i, name) in classNames.withIndex()) classDef(out, classDefsOff + 32 * i, descriptors.indexOf(descriptor(name)))
        return out
    }

    /**
     * A dex 041 container with one section per entry of [sections], shaped like d8's: each section holds its header,
     * type ids and class defs; the last one also holds the string ids and string data every section shares.
     */
    fun container(sections: List<List<String>>): ByteArray {
        val descriptors = sections.flatten().map(::descriptor).distinct().sorted()
        val stringData = descriptors.map(::stringDataItem)
        val starts = IntArray(sections.size)
        var at = 0
        for ((k, classes) in sections.withIndex()) {
            starts[k] = at
            at += CONTAINER_HEADER + 4 * classes.size + 32 * classes.size
        }
        val stringIdsOff = at
        val stringDataOff = stringIdsOff + 4 * descriptors.size
        val total = stringDataOff + stringData.sumOf { it.size }
        val out = ByteArray(total)
        var data = stringDataOff
        for ((i, item) in stringData.withIndex()) {
            putInt(out, stringIdsOff + 4 * i, data)
            item.copyInto(out, data)
            data += item.size
        }
        for ((k, classes) in sections.withIndex()) {
            val start = starts[k]
            val end = if (k == sections.lastIndex) total else starts[k + 1]
            header(out, start, "041", end - start, CONTAINER_HEADER, 0x12345678)
            putInt(out, start + 0x70, total)
            putInt(out, start + 0x74, start)
            val typeIdsOff = start + CONTAINER_HEADER
            val classDefsOff = typeIdsOff + 4 * classes.size
            putInt(out, start + 0x38, descriptors.size)
            putInt(out, start + 0x3C, stringIdsOff)
            putInt(out, start + 0x40, classes.size)
            putInt(out, start + 0x44, typeIdsOff)
            putInt(out, start + 0x60, classes.size)
            putInt(out, start + 0x64, classDefsOff)
            for ((i, name) in classes.withIndex()) {
                putInt(out, typeIdsOff + 4 * i, descriptors.indexOf(descriptor(name)))
                classDef(out, classDefsOff + 32 * i, i)
            }
        }
        return out
    }

    /** Writes a zip (an APK stand-in) with [entries] in order; stored (uncompressed) like APK dex files, or deflated. */
    fun zip(file: File, entries: List<Pair<String, ByteArray>>, stored: Boolean = true): File {
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, bytes) in entries) {
                val entry = ZipEntry(name)
                if (stored) {
                    entry.method = ZipEntry.STORED
                    entry.size = bytes.size.toLong()
                    entry.compressedSize = bytes.size.toLong()
                    entry.crc = CRC32().apply { update(bytes) }.value
                }
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return file
    }

    fun resource(name: String): ByteArray =
        checkNotNull(TestDex::class.java.getResourceAsStream("/hook-targets/$name")) { "missing resource $name" }
            .use { it.readBytes() }

    private fun descriptor(binaryName: String) = "L" + binaryName.replace('.', '/') + ";"

    private fun stringDataItem(value: String): ByteArray {
        val out = ByteArrayOutputStream()
        var length = value.length
        while (true) {
            val low = length and 0x7F
            length = length ushr 7
            if (length == 0) {
                out.write(low)
                break
            }
            out.write(low or 0x80)
        }
        for (c in value) {
            val code = c.code
            when {
                code in 1..0x7F -> out.write(code)
                code < 0x800 -> {
                    out.write(0xC0 or (code ushr 6))
                    out.write(0x80 or (code and 0x3F))
                }
                else -> {
                    out.write(0xE0 or (code ushr 12))
                    out.write(0x80 or ((code ushr 6) and 0x3F))
                    out.write(0x80 or (code and 0x3F))
                }
            }
        }
        out.write(0)
        return out.toByteArray()
    }

    private fun header(out: ByteArray, at: Int, version: String, fileSize: Int, headerSize: Int, endian: Int) {
        "dex\n$version\u0000".toByteArray(Charsets.ISO_8859_1).copyInto(out, at)
        putInt(out, at + 0x20, fileSize)
        putInt(out, at + 0x24, headerSize)
        putInt(out, at + 0x28, endian)
    }

    private fun classDef(out: ByteArray, at: Int, typeIndex: Int) {
        putInt(out, at, typeIndex)
        putInt(out, at + 4, 1) // public
        putInt(out, at + 8, NO_INDEX)
        putInt(out, at + 16, NO_INDEX)
    }

    private fun putInt(out: ByteArray, at: Int, value: Int) {
        out[at] = value.toByte()
        out[at + 1] = (value ushr 8).toByte()
        out[at + 2] = (value ushr 16).toByte()
        out[at + 3] = (value ushr 24).toByte()
    }

    private fun align4(value: Int) = (value + 3) and 3.inv()
}

/** Counts the bytes actually read (not skipped) and how often the stream was opened. */
internal class ReadCounter {
    var opens = 0
        private set
    var bytesRead = 0L
        private set

    fun wrap(input: InputStream): InputStream {
        opens++
        return object : FilterInputStream(input) {
            override fun read(): Int = super.read().also { if (it >= 0) bytesRead++ }

            override fun read(b: ByteArray, off: Int, len: Int): Int =
                super.read(b, off, len).also { if (it > 0) bytesRead += it }
        }
    }
}
