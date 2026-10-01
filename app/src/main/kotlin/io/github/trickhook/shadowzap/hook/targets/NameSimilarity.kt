package io.github.trickhook.shadowzap.hook.targets

/**
 * How alike two binary class names are, used to pick one of several discovered classes for a single-valued spec:
 * the class closest to the spec's exact candidates is most likely the renamed or moved original
 * (`com.whatsapp.settings.SettingsPrivacy` -> `SettingsPrivacyActivity` rather than `SettingsChat`;
 * `settings.ui.SettingsRow` -> `uibase.settings.SettingsRow`).
 */
internal object NameSimilarity {
    /** Weight of the simple name (after the last `.`, nested classes included); the package has the rest. */
    private const val SIMPLE_NAME_WEIGHT = 0.75

    /** Score in 0..1 of [candidate] against the closest of [references]; 0 without references. */
    fun score(candidate: String, references: List<String>): Double =
        references.maxOfOrNull { score(candidate, it) } ?: 0.0

    /** Score in 0..1: edit-distance similarity of the simple names, and shared leading package segments. */
    fun score(candidate: String, reference: String): Double {
        val simple = ratio(candidate.substringAfterLast('.'), reference.substringAfterLast('.'))
        val pkg = packageRatio(candidate.substringBeforeLast('.', ""), reference.substringBeforeLast('.', ""))
        return SIMPLE_NAME_WEIGHT * simple + (1 - SIMPLE_NAME_WEIGHT) * pkg
    }

    /** `1 - distance / longer length`; 1 for two empty strings. */
    fun ratio(a: String, b: String): Double {
        val longest = maxOf(a.length, b.length)
        if (longest == 0) return 1.0
        return 1.0 - levenshtein(a, b).toDouble() / longest
    }

    private fun packageRatio(a: String, b: String): Double {
        val left = if (a.isEmpty()) emptyList() else a.split('.')
        val right = if (b.isEmpty()) emptyList() else b.split('.')
        val longest = maxOf(left.size, right.size)
        if (longest == 0) return 1.0
        var common = 0
        while (common < left.size && common < right.size && left[common] == right[common]) common++
        return common.toDouble() / longest
    }

    /** Levenshtein distance (insertions, deletions, substitutions), two rows. */
    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(substitution, previous[j] + 1, current[j - 1] + 1)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }
}
