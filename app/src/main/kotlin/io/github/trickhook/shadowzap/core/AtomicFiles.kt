package io.github.trickhook.shadowzap.core

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Crash-safe file writes: data goes to a temporary sibling, is flushed to disk, and replaces the target with an
 * atomic rename, so readers see either the old or the new content and never a partial file.
 */
internal object AtomicFiles {
    /** Writes [target] through [block]; on failure the target is untouched and the temporary file removed. */
    fun write(target: File, block: (OutputStream) -> Unit) {
        val parent = target.absoluteFile.parentFile ?: throw IOException("No parent directory for $target")
        parent.ensureDirectory()
        val temp = tempSibling(target)
        try {
            FileOutputStream(temp).use { out ->
                block(out)
                out.flush()
                out.fd.sync()
            }
            replace(temp, target)
        } catch (t: Throwable) {
            temp.delete()
            throw t
        }
    }

    fun writeBytes(target: File, bytes: ByteArray) = write(target) { it.write(bytes) }

    fun writeText(target: File, text: String) = writeBytes(target, text.toByteArray(Charsets.UTF_8))

    /** Atomically moves [source] over [target]. Both must be on the same filesystem. */
    fun replace(source: File, target: File) {
        if (target.isDirectory) throw IOException("Refusing to replace directory $target")
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            if (!source.renameTo(target)) throw IOException("Could not move $source to $target")
        }
    }

    /** A unique, not yet existing temporary file next to [target]: `.<name>.<8 hex>.tmp`. */
    fun tempSibling(target: File): File =
        File(target.absoluteFile.parentFile, ".${target.name}.${UUID.randomUUID().toString().take(8)}.tmp")

    private val TEMP_NAME = Regex("""^\..+\.[0-9a-f]{8}\.tmp$""")

    /**
     * Whether [name] is a [tempSibling] name. Such a file is either being written right now or was left behind by a
     * process that died before the rename; it never holds complete content.
     */
    fun isTempName(name: String): Boolean = TEMP_NAME.matches(name)

    /** The file's text, or `null` when it does not exist, is not a regular file or cannot be read. */
    fun readTextOrNull(file: File): String? = try {
        if (file.isFile) file.readText() else null
    } catch (_: IOException) {
        null
    }

    /**
     * Moves an unreadable file aside as `<name>.corrupt.<millis>` so it can be inspected but is never read again.
     * Returns the new location, or `null` when the file could not be moved (it is then deleted).
     */
    fun quarantine(file: File): File? {
        if (!file.exists()) return null
        val aside = File(file.parentFile, "${file.name}.corrupt.${System.currentTimeMillis()}")
        if (file.renameTo(aside)) return aside
        file.deleteRecursively()
        return null
    }
}

/** Makes sure this path is a directory, deleting a regular file that is in the way. Returns this. */
internal fun File.ensureDirectory(): File {
    if (isDirectory) return this
    if (exists()) delete()
    if (!mkdirs() && !isDirectory) throw IOException("Could not create directory $this")
    return this
}

/** Deletes this path if it is a directory where a regular file is expected. Returns this. */
internal fun File.ensureNotDirectory(): File {
    if (isDirectory) deleteRecursively()
    return this
}

/** Whether [child] is this directory or lies below it, after resolving `..` and symlinks. */
internal fun File.containsPath(child: File): Boolean {
    val root = canonicalFile
    val candidate = child.canonicalFile
    return candidate == root || candidate.path.startsWith(root.path + File.separator)
}
