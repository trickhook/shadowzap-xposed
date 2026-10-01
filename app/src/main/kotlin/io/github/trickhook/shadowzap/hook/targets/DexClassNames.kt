package io.github.trickhook.shadowzap.hook.targets

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/** A dex file (or dex container) that [DexClassNames] cannot read: bad magic, unsupported version or layout. */
internal class DexFormatException(message: String) : IOException(message)

/**
 * Minimal dex parser that lists the classes a dex file defines, as binary names (`a.b.Outer$Inner`).
 *
 * It reads only what it needs: the header, `type_ids`, `class_defs`, the `string_ids` entries of the class
 * descriptors and those descriptors' `string_data`. Reads go forward through one stream; a table that lies before
 * the current position (the `string_ids`, and the sections of a dex 041 container) reopens the stream, which costs
 * a seek for the stored (uncompressed) dex entries of APKs. Memory is bounded by the class count (at most 65536 per
 * dex), never by the size of the file.
 *
 * Supports dex 035 to 040 and the dex 041 container (several dex sections in one file that share tables; its
 * offsets are relative to the start of the container). No `DexFile` API, no hidden API.
 */
internal object DexClassNames {
    /** Class definitions and type ids of one dex (type indices are 16-bit). */
    private const val MAX_CLASSES = 1 shl 16

    /** Longest class descriptor read, in UTF-16 units (JVM names are at most 65535 modified UTF-8 bytes). */
    private const val MAX_DESCRIPTOR_CHARS = 0xFFFF

    /** Sections of one dex 041 container. */
    private const val MAX_SECTIONS = 4096

    private const val HEADER_SIZE = 0x70
    private const val CONTAINER_HEADER_SIZE = 0x78
    private const val CLASS_DEF_SIZE = 32
    private const val ENDIAN_CONSTANT = 0x12345678L
    private const val REVERSE_ENDIAN_CONSTANT = 0x78563412L
    private const val MIN_VERSION = 35
    private const val CONTAINER_VERSION = 41
    private const val CHUNK_BYTES = 64 * 1024

    /**
     * The binary names of the classes defined in the dex file (or dex 041 container) that [open] streams, in
     * definition order. [size] is the stream's length, or a negative value when unknown. Every call of [open] must
     * return a fresh stream positioned at the start of the file; streams are closed here.
     *
     * @throws IOException when the file cannot be read or is not a dex file this parser supports.
     */
    fun read(size: Long, open: () -> InputStream): List<String> =
        ForwardReader(if (size >= 0) size else Long.MAX_VALUE, open).use { reader ->
            val names = ArrayList<String>()
            var headerOffset = 0L
            var sections = 0
            while (true) {
                val header = readHeader(reader, headerOffset, size)
                readClassNames(reader, header, names)
                if (header.version < CONTAINER_VERSION) break
                if (++sections >= MAX_SECTIONS) throw DexFormatException("more than $MAX_SECTIONS dex sections")
                headerOffset += header.fileSize
                if (headerOffset >= header.limit) break
            }
            names
        }

    private class Header(
        val version: Int,
        val fileSize: Long,
        /** End of the region every offset must stay in: the file, or the whole 041 container. */
        val limit: Long,
        val stringIdsSize: Long,
        val stringIdsOff: Long,
        val typeIdsSize: Long,
        val typeIdsOff: Long,
        val classDefsSize: Long,
        val classDefsOff: Long,
    )

    private fun readHeader(reader: ForwardReader, at: Long, size: Long): Header {
        val bytes = ByteArray(CONTAINER_HEADER_SIZE)
        reader.seek(at)
        reader.readFully(bytes, 0, HEADER_SIZE)
        val magicOk = bytes[0] == 'd'.code.toByte() && bytes[1] == 'e'.code.toByte() &&
            bytes[2] == 'x'.code.toByte() && bytes[3] == '\n'.code.toByte() && bytes[7] == 0.toByte() &&
            isDigit(bytes[4]) && isDigit(bytes[5]) && isDigit(bytes[6])
        if (!magicOk) throw DexFormatException("not a dex file (magic at offset $at)")
        val version = (bytes[4] - ZERO) * 100 + (bytes[5] - ZERO) * 10 + (bytes[6] - ZERO)
        if (version !in MIN_VERSION..CONTAINER_VERSION) throw DexFormatException("unsupported dex version $version")
        when (u32(bytes, 0x28)) {
            ENDIAN_CONSTANT -> Unit
            REVERSE_ENDIAN_CONSTANT -> throw DexFormatException("big-endian dex files are not supported")
            else -> throw DexFormatException("bad endian tag")
        }
        val fileSize = u32(bytes, 0x20)
        val headerSize = u32(bytes, 0x24)
        val limit: Long
        if (version >= CONTAINER_VERSION) {
            if (headerSize < CONTAINER_HEADER_SIZE) throw DexFormatException("dex 041 header of $headerSize bytes")
            reader.readFully(bytes, HEADER_SIZE, CONTAINER_HEADER_SIZE - HEADER_SIZE)
            val containerSize = u32(bytes, 0x70)
            val headerOffset = u32(bytes, 0x74)
            if (headerOffset != at) throw DexFormatException("dex section at $at claims offset $headerOffset")
            if (size >= 0 && containerSize > size) {
                throw DexFormatException("container of $containerSize bytes in a file of $size")
            }
            limit = containerSize
            if (fileSize < CONTAINER_HEADER_SIZE || at + fileSize > limit) {
                throw DexFormatException("dex section at $at has size $fileSize (container $limit)")
            }
        } else {
            if (headerSize < HEADER_SIZE) throw DexFormatException("dex header of $headerSize bytes")
            if (fileSize < HEADER_SIZE || (size >= 0 && fileSize > size)) {
                throw DexFormatException("dex of $fileSize bytes in a file of $size")
            }
            limit = fileSize
        }
        return Header(
            version = version,
            fileSize = fileSize,
            limit = limit,
            stringIdsSize = u32(bytes, 0x38),
            stringIdsOff = u32(bytes, 0x3C),
            typeIdsSize = u32(bytes, 0x40),
            typeIdsOff = u32(bytes, 0x44),
            classDefsSize = u32(bytes, 0x60),
            classDefsOff = u32(bytes, 0x64),
        )
    }

    private fun readClassNames(reader: ForwardReader, header: Header, names: MutableList<String>) {
        if (header.classDefsSize == 0L) return
        if (header.classDefsSize > MAX_CLASSES) throw DexFormatException("${header.classDefsSize} class definitions")
        if (header.typeIdsSize > MAX_CLASSES) throw DexFormatException("${header.typeIdsSize} type ids")
        checkTable("class_defs", header.classDefsOff, header.classDefsSize, CLASS_DEF_SIZE, header.limit)
        checkTable("type_ids", header.typeIdsOff, header.typeIdsSize, 4, header.limit)
        checkTable("string_ids", header.stringIdsOff, header.stringIdsSize, 4, header.limit)
        val count = header.classDefsSize.toInt()
        val typeCount = header.typeIdsSize.toInt()

        // type_ids and class_defs in file order (type_ids comes first in every dex writer's layout).
        val typeIds: IntArray
        val classTypes: IntArray
        if (header.typeIdsOff <= header.classDefsOff) {
            typeIds = readU32s(reader, header.typeIdsOff, typeCount, 4)
            classTypes = readU32s(reader, header.classDefsOff, count, CLASS_DEF_SIZE)
        } else {
            classTypes = readU32s(reader, header.classDefsOff, count, CLASS_DEF_SIZE)
            typeIds = readU32s(reader, header.typeIdsOff, typeCount, 4)
        }

        // The string index of each class descriptor, sorted so string_ids is read forward (key: id << 16 | class).
        val keys = LongArray(count) { i ->
            val type = Integer.toUnsignedLong(classTypes[i])
            if (type >= typeCount) throw DexFormatException("class_def #$i names type $type of $typeCount")
            val string = Integer.toUnsignedLong(typeIds[type.toInt()])
            if (string >= header.stringIdsSize) {
                throw DexFormatException("type $type names string $string of ${header.stringIdsSize}")
            }
            (string shl 16) or i.toLong()
        }
        keys.sort()
        val entry = ByteArray(4)
        var previousId = -1L
        var previousOffset = 0L
        for (k in keys.indices) {
            val id = keys[k] ushr 16
            if (id != previousId) {
                reader.seek(header.stringIdsOff + 4 * id)
                reader.readFully(entry, 0, 4)
                previousOffset = u32(entry, 0)
                previousId = id
                if (previousOffset >= header.limit) throw DexFormatException("string $id at $previousOffset")
            }
            // Re-key by string_data offset (< 2^32) for the next pass.
            keys[k] = (previousOffset shl 16) or (keys[k] and 0xFFFF)
        }

        // The descriptors themselves, in ascending file order.
        keys.sort()
        val decoded = arrayOfNulls<String>(count)
        var lastOffset = -1L
        var lastName: String? = null
        for (key in keys) {
            val offset = key ushr 16
            if (offset != lastOffset) {
                reader.seek(offset)
                lastName = binaryName(readMutf8(reader))
                lastOffset = offset
            }
            decoded[(key and 0xFFFF).toInt()] = lastName
        }
        for (name in decoded) if (name != null) names += name
    }

    private fun checkTable(what: String, offset: Long, count: Long, stride: Int, limit: Long) {
        if (count == 0L) return
        if (offset < HEADER_SIZE || offset + count * stride > limit) {
            throw DexFormatException("$what ($count at offset $offset) outside the dex (size $limit)")
        }
    }

    /** [count] records of [stride] bytes at [offset]; returns the first u32 (raw bits) of each. */
    private fun readU32s(reader: ForwardReader, offset: Long, count: Int, stride: Int): IntArray {
        val out = IntArray(count)
        if (count == 0) return out
        reader.seek(offset)
        val perChunk = maxOf(1, CHUNK_BYTES / stride)
        val buffer = ByteArray(perChunk * stride)
        var done = 0
        while (done < count) {
            val n = minOf(perChunk, count - done)
            reader.readFully(buffer, 0, n * stride)
            for (k in 0 until n) out[done + k] = u32(buffer, k * stride).toInt()
            done += n
        }
        return out
    }

    /** One `string_data_item`: ULEB128 length in UTF-16 units, then modified UTF-8 up to a NUL byte. */
    private fun readMutf8(reader: ForwardReader): String {
        var length = 0L
        var shift = 0
        while (true) {
            val b = reader.readByte()
            length = length or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) break
            shift += 7
            if (shift > 28) throw DexFormatException("bad string length")
        }
        if (length > MAX_DESCRIPTOR_CHARS) throw DexFormatException("class descriptor of $length characters")
        val chars = CharArray(length.toInt())
        var n = 0
        while (true) {
            val a = reader.readByte()
            if (a == 0) break
            if (n == chars.size) throw DexFormatException("string longer than its declared $length characters")
            chars[n++] = when {
                a < 0x80 -> a
                a and 0xE0 == 0xC0 -> (a and 0x1F) shl 6 or continuation(reader)
                a and 0xF0 == 0xE0 -> (a and 0x0F) shl 12 or (continuation(reader) shl 6) or continuation(reader)
                else -> throw DexFormatException("bad modified UTF-8 byte 0x${a.toString(16)}")
            }.toChar()
        }
        if (n != chars.size) throw DexFormatException("string of $n characters, declared $length")
        return String(chars)
    }

    private fun continuation(reader: ForwardReader): Int {
        val b = reader.readByte()
        if (b and 0xC0 != 0x80) throw DexFormatException("bad modified UTF-8 continuation 0x${b.toString(16)}")
        return b and 0x3F
    }

    /** `La/b/Outer$Inner;` -> `a.b.Outer$Inner`; `null` for anything that is not a class descriptor. */
    private fun binaryName(descriptor: String): String? {
        if (descriptor.length < 3 || descriptor[0] != 'L' || descriptor[descriptor.length - 1] != ';') return null
        return descriptor.substring(1, descriptor.length - 1).replace('/', '.')
    }

    private fun isDigit(b: Byte): Boolean = b in ZERO_BYTE..NINE_BYTE

    private fun u32(bytes: ByteArray, at: Int): Long =
        (bytes[at].toLong() and 0xFF) or
            ((bytes[at + 1].toLong() and 0xFF) shl 8) or
            ((bytes[at + 2].toLong() and 0xFF) shl 16) or
            ((bytes[at + 3].toLong() and 0xFF) shl 24)

    private const val ZERO = '0'.code
    private const val ZERO_BYTE: Byte = 0x30
    private const val NINE_BYTE: Byte = 0x39
}

/**
 * Forward-only reader over a stream of at most [size] bytes. Seeking backwards reopens the stream; seeking forwards
 * skips (a seek for stored zip entries, inflate-and-discard for compressed ones).
 */
private class ForwardReader(private val size: Long, private val open: () -> InputStream) : Closeable {
    private var input: InputStream? = null
    private var position = 0L

    fun seek(offset: Long) {
        if (offset < 0 || offset > size) throw DexFormatException("offset $offset outside the file")
        var stream = input
        if (stream == null || offset < position) {
            input = null
            stream?.close()
            stream = BufferedInputStream(open(), BUFFER_BYTES)
            input = stream
            position = 0
        }
        var remaining = offset - position
        while (remaining > 0) {
            val skipped = stream.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                if (stream.read() < 0) throw EOFException("dex ends before offset $offset")
                remaining--
            }
        }
        position = offset
    }

    fun readFully(buffer: ByteArray, offset: Int, length: Int) {
        val stream = input ?: throw IllegalStateException("seek first")
        if (position + length > size) throw EOFException("dex ends before offset ${position + length}")
        var done = 0
        while (done < length) {
            val n = stream.read(buffer, offset + done, length - done)
            if (n < 0) throw EOFException("dex ends at offset ${position + done}")
            done += n
        }
        position += length
    }

    fun readByte(): Int {
        val stream = input ?: throw IllegalStateException("seek first")
        if (position >= size) throw EOFException("dex ends at offset $position")
        val b = stream.read()
        if (b < 0) throw EOFException("dex ends at offset $position")
        position++
        return b
    }

    override fun close() {
        val stream = input
        input = null
        stream?.close()
    }

    private companion object {
        const val BUFFER_BYTES = 32 * 1024
    }
}
