package io.github.trickhook.shadowzap.whatsapp

import io.github.trickhook.shadowzap.api.version.Version

/** Lenient parsing of host version strings such as `2.26.39.11` or `2.26.40.2-beta`. */
internal object HostVersions {
    private val LEADING = Regex("""^\s*[vV]?(\d+(?:\.\d+)*)(?:-([0-9A-Za-z]+))?""")

    /**
     * Parses [raw] strictly when possible, otherwise takes its leading `N(.N)*` part and an optional `-label`
     * directly after it (lower-cased). Returns `null` when there is no leading number or a segment overflows.
     */
    fun parseLenient(raw: String?): Version? {
        if (raw.isNullOrBlank()) return null
        Version.parseOrNull(raw.trim())?.let { return it }
        val match = LEADING.find(raw) ?: return null
        val segments = match.groupValues[1].split('.').map { it.toIntOrNull() ?: return null }
        val label = match.groupValues[2].lowercase().ifEmpty { null }
        return try {
            Version(segments, label)
        } catch (_: IllegalArgumentException) {
            Version(segments)
        }
    }
}
