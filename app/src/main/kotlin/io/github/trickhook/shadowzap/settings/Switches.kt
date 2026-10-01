package io.github.trickhook.shadowzap.settings

import io.github.trickhook.shadowzap.api.host.Logger
import io.github.trickhook.shadowzap.core.AppJson
import io.github.trickhook.shadowzap.core.AtomicFiles
import io.github.trickhook.shadowzap.core.Env
import io.github.trickhook.shadowzap.core.Kernel
import io.github.trickhook.shadowzap.core.Paths
import io.github.trickhook.shadowzap.core.PrettyJson
import io.github.trickhook.shadowzap.core.ensureDirectory
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.io.File

/**
 * The on/off switches of the Shadowzap features, as the settings page writes them to `files/shadowzap/switches.json`
 * ([Paths.switchesFile]):
 *
 * ```
 * {"version":1,"enabled":{"media.statusDownload":true,"media.hdImages":false}}
 * ```
 *
 * Reading is lenient: a missing, oversized, unreadable or malformed file, a missing `enabled` object and every entry
 * that is not a boolean (`true`/`false`, the strings `"true"`/`"false"` or the numbers `1`/`0` are accepted) leave
 * the affected features at their [DEFAULTS]. Unknown ids are kept (and written back); an id that is neither in the
 * file nor in [DEFAULTS] is off. Another `version` is read the same way (logged).
 */
internal class Switches private constructor(
    /** The switches the file set explicitly. */
    val explicit: Map<String, Boolean>,
    /** Where the switches came from, for the log (e.g. `defaults (no file)`). */
    val origin: String,
    /** Problems met while reading, for the log; empty when the file was clean. */
    val problems: List<String>,
) {
    /** Whether feature [id] is on: the file's value, else its default, else off. */
    fun isEnabled(id: String): Boolean = explicit[id] ?: DEFAULTS[id] ?: false

    /** A copy with [id] set explicitly to [enabled]. */
    fun with(id: String, enabled: Boolean): Switches =
        Switches(LinkedHashMap(explicit).apply { put(id, enabled) }, "settings", emptyList())

    /** The file content for these switches. */
    fun toJson(): String {
        val document = buildJsonObject {
            put("version", VERSION)
            put("enabled", buildJsonObject { explicit.forEach { (id, value) -> put(id, value) } })
        }
        return PrettyJson.encodeToString(JsonObject.serializer(), document) + "\n"
    }

    /** One line for the log: every known feature with its state and whether it came from the file. */
    fun describe(): String = buildString {
        append(origin).append(": ")
        append(
            DEFAULTS.keys.joinToString(", ") { id ->
                val state = if (isEnabled(id)) "on" else "off"
                if (id in explicit) "$id=$state" else "$id=$state (default)"
            },
        )
        if (problems.isNotEmpty()) append("; ignored: ").append(problems.joinToString("; "))
    }

    companion object {
        /** A download button in the Status viewer. */
        const val STATUS_DOWNLOAD: String = "media.statusDownload"

        /** Photos are sent at full quality instead of WhatsApp's compressed size. */
        const val HD_IMAGES: String = "media.hdImages"

        /** Videos are sent at full quality instead of WhatsApp's transcoded size. */
        const val HD_VIDEOS: String = "media.hdVideos"

        /** Defaults of every feature, used whenever the file does not say otherwise. */
        val DEFAULTS: Map<String, Boolean> = linkedMapOf(
            STATUS_DOWNLOAD to true,
            HD_IMAGES to true,
            HD_VIDEOS to true,
        )

        /** The format this module writes and expects. */
        const val VERSION: Int = 1

        /** Anything larger is not a switch file; the defaults apply. */
        const val MAX_BYTES: Long = 64L * 1024

        private const val BOM = "﻿"

        /** Every feature at its default. */
        fun defaults(origin: String = "defaults"): Switches = Switches(emptyMap(), origin, emptyList())

        /** Parses the file's text; never throws. */
        fun parse(text: String, origin: String = "file"): Switches {
            val root: JsonElement = try {
                AppJson.parseToJsonElement(text.removePrefix(BOM))
            } catch (t: Throwable) {
                return Switches(emptyMap(), "defaults (unreadable file)", listOf("not JSON: ${t.message ?: t}"))
            }
            if (root !is JsonObject) {
                return Switches(emptyMap(), "defaults (unreadable file)", listOf("the root is not an object"))
            }

            val problems = ArrayList<String>()
            when (val version = root["version"]) {
                null -> problems += "no version (read as $VERSION)"
                else -> {
                    val number = (version as? JsonPrimitive)?.intOrNull
                    if (number != VERSION) problems += "version $version (read as $VERSION)"
                }
            }

            val enabled = root["enabled"] as? JsonObject
                ?: return Switches(emptyMap(), "defaults (no enabled object)", problems + "no enabled object")

            val explicit = LinkedHashMap<String, Boolean>()
            for ((id, value) in enabled) {
                val flag = booleanOf(value)
                if (flag == null) problems += "$id=$value" else explicit[id] = flag
            }
            return Switches(explicit, origin, problems)
        }

        /** Reads [file]; a missing, oversized or unreadable file gives the defaults. Never throws. */
        fun read(file: File): Switches = try {
            when {
                !file.exists() -> defaults("defaults (no file)")
                !file.isFile -> defaults("defaults (not a file)")
                file.length() > MAX_BYTES -> defaults("defaults (file larger than $MAX_BYTES bytes)")
                else -> parse(file.readText(Charsets.UTF_8), origin = file.path)
            }
        } catch (t: Throwable) {
            Switches(emptyMap(), "defaults (unreadable file)", listOf(t.toString()))
        }

        private fun booleanOf(value: JsonElement): Boolean? {
            val primitive = value as? JsonPrimitive ?: return null
            if (!primitive.isString) {
                primitive.booleanOrNull?.let { return it }
                return when (primitive.doubleOrNull) {
                    1.0 -> true
                    0.0 -> false
                    else -> null
                }
            }
            return when (primitive.content.trim().lowercase()) {
                "true" -> true
                "false" -> false
                else -> null
            }
        }
    }
}

/**
 * The switches of this process: read once from [Paths.switchesFile] the first time a feature asks, and updated in
 * place by the settings page ([set]), which runs in the same process. Features that read [isEnabled] at call time
 * therefore follow a change immediately; features that decide at install time follow it from the next start.
 */
internal object SwitchStore {
    @Volatile
    private var loaded: Pair<File, Switches>? = null

    fun isEnabled(env: Env, id: String): Boolean = forDataDir(env.dataDir).isEnabled(id)

    fun forDataDir(dataDir: File): Switches {
        val file = Paths(dataDir).switchesFile
        loaded?.let { (source, switches) -> if (source == file) return switches }
        synchronized(this) {
            loaded?.let { (source, switches) -> if (source == file) return switches }
            val switches = Switches.read(file)
            loaded = file to switches
            logger()?.let { log ->
                val line = "Switches from ${switches.describe()}"
                if (switches.problems.isEmpty()) log.i(line) else log.w(line)
            }
            return switches
        }
    }

    /** Sets [id] to [enabled], writes the file atomically and returns the new switches. Throws when writing fails. */
    fun set(dataDir: File, id: String, enabled: Boolean): Switches = synchronized(this) {
        val file = Paths(dataDir).switchesFile
        val updated = forDataDir(dataDir).with(id, enabled)
        file.parentFile?.ensureDirectory()
        AtomicFiles.writeText(file, updated.toJson())
        loaded = file to updated
        logger()?.i("Switch $id=${if (enabled) "on" else "off"}")
        updated
    }

    /** Forgets the switches read so far (tests). */
    internal fun reset() {
        loaded = null
    }

    private fun logger(): Logger? = try {
        Kernel.current?.logs?.logger("switches")
    } catch (_: Throwable) {
        null
    }
}
