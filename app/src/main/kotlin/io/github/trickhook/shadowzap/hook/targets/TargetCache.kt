package io.github.trickhook.shadowzap.hook.targets

import io.github.trickhook.shadowzap.api.host.Logger
import io.github.trickhook.shadowzap.core.AppJson
import io.github.trickhook.shadowzap.core.AtomicFiles
import io.github.trickhook.shadowzap.core.HostInfo
import io.github.trickhook.shadowzap.core.PrettyJson
import io.github.trickhook.shadowzap.core.Sha256
import io.github.trickhook.shadowzap.core.ensureNotDirectory
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import java.io.File
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * One remembered value of a target: a class, or a member named by its declaring class and signature (binary type
 * names as [Class.getName] gives them: `int`, `java.lang.String`, `[Ljava.lang.String;`).
 */
@Serializable
internal data class CachedValue(
    @SerialName("class") val className: String,
    /** Method or field name; `null` for classes and constructors. */
    val name: String? = null,
    /** Parameter types of a method or constructor; `null` for classes and fields. */
    val params: List<String>? = null,
    /** Return type of a method or type of a field; `null` for classes and constructors. */
    val type: String? = null,
) {
    /** Whether [value] (a class or member of the host) is the one this entry names. */
    fun matches(value: Any): Boolean = try {
        of(value) == this
    } catch (_: LinkageError) {
        false
    } catch (_: TypeNotPresentException) {
        false
    }

    override fun toString(): String = buildString {
        append(className)
        if (name != null || params != null) append('#').append(name ?: "<init>")
        params?.let { append('(').append(it.joinToString(", ")).append(')') }
        type?.let { append(": ").append(it) }
    }

    companion object {
        fun of(value: Any): CachedValue = when (value) {
            is Class<*> -> CachedValue(value.name)
            is Method -> CachedValue(
                value.declaringClass.name,
                value.name,
                value.parameterTypes.map(Class<*>::getName),
                value.returnType.name,
            )
            is Constructor<*> ->
                CachedValue(value.declaringClass.name, params = value.parameterTypes.map(Class<*>::getName))
            is Field -> CachedValue(value.declaringClass.name, value.name, type = value.type.name)
            else -> throw IllegalArgumentException("Not a class or member: ${value.javaClass.name}")
        }
    }
}

/** What an earlier discovery found for one spec in this host build. */
@Serializable
internal data class CachedTarget(
    /** [TargetKind] name of the spec. */
    val kind: String,
    /** The discovered values; empty: discovery found nothing in this build (it is not run again). */
    val values: List<CachedValue> = emptyList(),
    /** Found while the debug force-discovery switch covered the spec. */
    val forced: Boolean = false,
    /** When it was found, epoch milliseconds. */
    val time: Long = 0,
)

/** The cache file. Entries are decoded one by one, so one unreadable entry never loses the others. */
@Serializable
internal class CacheDocument(
    val format: Int = 0,
    val key: String = "",
    val hostVersionCode: Long? = null,
    val hostVersionName: String? = null,
    val loaderVersionCode: Int? = null,
    val entries: Map<String, JsonElement> = emptyMap(),
)

/**
 * Identifies one host build together with the loader build: `<host version code>-<loader version code>-<stamp>`,
 * where the stamp hashes path, size and modification time (the last update) of every host APK and of the module
 * APK. Reinstalling or updating either side changes the key; nothing else does.
 */
internal class BuildKey(val value: String, val hostVersionCode: Long?) {
    override fun toString(): String = value

    companion object {
        private const val STAMP_HEX_DIGITS = 12

        fun of(hostVersionCode: Long?, loaderVersionCode: Int, stamp: String): BuildKey {
            val digest = Sha256.hex(stamp).take(STAMP_HEX_DIGITS)
            return BuildKey("${hostVersionCode ?: "x"}-$loaderVersionCode-$digest", hostVersionCode)
        }

        /** Stamp text of [apks] (host) and [modulePath] (may be empty): one `path|size|mtime` line each. */
        fun stamp(apks: List<File>, modulePath: String, loaderVersion: String): String = buildString {
            for (apk in apks) append("host=").append(fileStamp(apk)).append('\n')
            if (modulePath.isNotEmpty()) append("module=").append(fileStamp(File(modulePath))).append('\n')
            append("loader=").append(loaderVersion).append('\n')
        }

        private fun fileStamp(file: File): String = try {
            "${file.path}|${file.length()}|${file.lastModified()}"
        } catch (_: SecurityException) {
            "${file.path}|?"
        }
    }
}

/**
 * The per-build cache of discovered hook targets: `Paths.hookTargetsCacheFile(key)`, a small pretty-printed JSON
 * file. Loaded on first use; at that point files of other keys (stale builds), older quarantined copies and
 * abandoned temporary files are pruned. Written atomically after each change. An unreadable file is quarantined
 * and the cache starts empty; an unreadable entry is dropped alone. Every failure is logged and never thrown.
 *
 * Not thread-safe: [HostTargets] calls it under its lock.
 */
internal class TargetCache(
    private val dir: File,
    private val fileOf: (String) -> File,
    private val key: () -> BuildKey,
    private val hostInfo: HostInfo,
    private val loaderVersionCode: Int,
    private val log: Logger,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class State(val key: BuildKey?, val file: File?, val entries: MutableMap<String, CachedTarget>)

    private var state: State? = null
    private var crossChecked = false

    /** The cache file of this build, or `null` when the cache is disabled (no usable key). */
    val file: File? get() = state().file

    fun get(id: String): CachedTarget? = state().entries[id]

    fun put(id: String, target: CachedTarget) {
        val state = state()
        if (state.entries[id] == target) return
        state.entries[id] = target
        save(state)
    }

    fun remove(id: String) {
        val state = state()
        if (state.entries.remove(id) != null) save(state)
    }

    private fun state(): State = state ?: load().also { state = it }

    private fun load(): State {
        val buildKey: BuildKey
        val file: File
        try {
            buildKey = key()
            file = fileOf(buildKey.value)
        } catch (t: Throwable) {
            log.w("Hook target cache disabled: no build key", t)
            return State(null, null, LinkedHashMap())
        }
        prune(buildKey, file)
        return State(buildKey, file, read(buildKey, file))
    }

    private fun read(buildKey: BuildKey, file: File): MutableMap<String, CachedTarget> {
        val entries = LinkedHashMap<String, CachedTarget>()
        try {
            if (file.isDirectory) {
                file.deleteRecursively()
                return entries
            }
            if (!file.isFile) return entries
            if (file.length() > MAX_FILE_BYTES) {
                quarantine(file, "it is larger than $MAX_FILE_BYTES bytes")
                return entries
            }
            val document = try {
                AppJson.decodeFromString(CacheDocument.serializer(), file.readText())
            } catch (e: IllegalArgumentException) {
                // SerializationException included: not JSON, or not the document shape.
                quarantine(file, e.toString())
                return entries
            }
            if (document.format != FORMAT || document.key != buildKey.value) {
                log.i("Ignoring ${file.name}: format ${document.format}, key '${document.key}'")
                return entries
            }
            for ((id, element) in document.entries) {
                try {
                    entries[id] = AppJson.decodeFromJsonElement(CachedTarget.serializer(), element)
                } catch (e: IllegalArgumentException) {
                    log.w("Dropping unreadable hook target cache entry '$id': $e")
                }
            }
            log.d("Hook target cache ${file.name}: ${entries.size} entries")
        } catch (e: Exception) {
            log.w("Could not read ${file.path}; starting empty", e)
        }
        return entries
    }

    private fun save(state: State) {
        val file = state.file ?: return
        val buildKey = state.key ?: return
        crossCheck(buildKey)
        try {
            val document = CacheDocument(
                format = FORMAT,
                key = buildKey.value,
                hostVersionCode = buildKey.hostVersionCode,
                hostVersionName = hostInfo.versionName,
                loaderVersionCode = loaderVersionCode,
                entries = state.entries.toSortedMap().mapValues { (_, target) ->
                    AppJson.encodeToJsonElement(CachedTarget.serializer(), target)
                },
            )
            file.ensureNotDirectory()
            AtomicFiles.writeText(file, PrettyJson.encodeToString(CacheDocument.serializer(), document) + "\n")
        } catch (t: Throwable) {
            log.w("Could not write ${file.path}", t)
        }
    }

    /** A key's version code, when one was known at boot, is compared with the package manager's value. */
    private fun crossCheck(buildKey: BuildKey) {
        if (crossChecked) return
        val reported = hostInfo.versionCode ?: return
        crossChecked = true
        if (buildKey.hostVersionCode != null && buildKey.hostVersionCode != reported) {
            log.w("Host version code ${buildKey.hostVersionCode} (build key) differs from $reported (package info)")
        }
    }

    /**
     * Deletes files of other build keys (`<prefix><other key>...`), all but the newest quarantined copy of this
     * key's file, and temporary files abandoned by a process that died mid-write.
     */
    private fun prune(buildKey: BuildKey, current: File) {
        val keyAt = current.name.indexOf(buildKey.value)
        if (keyAt <= 0) return
        val prefix = current.name.substring(0, keyAt)
        val files = try {
            dir.listFiles()
        } catch (_: SecurityException) {
            null
        } ?: return
        val corruptPrefix = "${current.name}$QUARANTINE_INFIX"
        val newestCorrupt = files.filter { it.name.startsWith(corruptPrefix) }.maxByOrNull { it.name }
        val now = clock()
        var pruned = 0
        for (candidate in files) {
            val name = candidate.name
            val stale = when {
                name == current.name || candidate == newestCorrupt -> false
                name.startsWith(prefix) -> true
                name.startsWith(".$prefix") && AtomicFiles.isTempName(name) ->
                    now - candidate.lastModified() > TEMP_GRACE_MILLIS
                else -> false
            }
            if (stale && candidate.deleteRecursively()) pruned++
        }
        if (pruned > 0) log.d("Pruned $pruned stale hook target cache files in ${dir.path}")
    }

    private fun quarantine(file: File, reason: String) {
        val aside = AtomicFiles.quarantine(file)
        val where = aside?.let { "moved to ${it.name}" } ?: "deleted"
        log.w("Hook target cache ${file.name} is unreadable ($reason); $where")
    }

    companion object {
        const val FORMAT = 1
        private const val MAX_FILE_BYTES = 1024L * 1024
        private const val TEMP_GRACE_MILLIS = 10L * 60 * 1000
        private const val QUARANTINE_INFIX = ".corrupt."
    }
}
