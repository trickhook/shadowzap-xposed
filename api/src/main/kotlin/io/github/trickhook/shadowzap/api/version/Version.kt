package io.github.trickhook.shadowzap.api.version

/**
 * A host or module version: one or more non-negative integer segments, optionally followed by a prerelease label.
 *
 * Format: `<n>[.<n>]*[-<label>]`, where the label matches `[a-z0-9]+`. Leading zeros are insignificant
 * (`1.02` is `1.2`).
 *
 * ## Ordering and equality
 * 1. Numeric segments are compared left to right; the shorter version is padded with zeros, so `1.2` == `1.2.0`.
 * 2. With equal numbers, a bare version is greater than a labeled one (`1.2.0` > `1.2.0-rc`).
 * 3. Two labels are compared run by run: digit runs numerically, other runs lexically (`beta2` < `beta10`),
 *    and a label that is a prefix of the other sorts first (`beta` < `beta1`).
 *
 * [equals] and [hashCode] agree with [compareTo]: `Version.parse("1.0") == Version.parse("1.0.0")` and
 * `Version.parse("1.0-rc01") == Version.parse("1.0-rc1")`.
 * [toString] keeps the segments as written.
 */
public class Version(segments: List<Int>, public val label: String? = null) : Comparable<Version> {
    /** The numeric segments as given (not normalised). */
    public val segments: List<Int> = segments.toList()

    init {
        require(this.segments.isNotEmpty()) { "A version needs at least one numeric segment" }
        require(this.segments.all { it >= 0 }) { "Version segments must be non-negative: ${this.segments}" }
        if (label != null) require(LABEL.matches(label)) { "Invalid version label: '$label'" }
    }

    /** Segments without trailing zeros; with [canonicalLabel], the basis of [equals] and [hashCode]. */
    private val significant: List<Int> = this.segments.dropLastWhile { it == 0 }

    /** The label with leading zeros removed from digit runs (`rc01` -> `rc1`), matching [compareTo]. */
    private val canonicalLabel: String? = label?.let { l ->
        runs(l).joinToString("") { run -> if (run[0].isDigit()) run.trimStart('0').ifEmpty { "0" } else run }
    }

    /** Segment [index], or 0 past the end. */
    public fun segment(index: Int): Int = segments.getOrElse(index) { 0 }

    /** This version without its label. */
    public fun withoutLabel(): Version = if (label == null) this else Version(segments)

    /** Whether this is a prerelease (has a label). */
    public val isPrerelease: Boolean get() = label != null

    override fun compareTo(other: Version): Int {
        val length = maxOf(segments.size, other.segments.size)
        for (i in 0 until length) {
            val cmp = segment(i).compareTo(other.segment(i))
            if (cmp != 0) return cmp
        }
        return when {
            label == null && other.label == null -> 0
            label == null -> 1
            other.label == null -> -1
            else -> compareLabels(label, other.label)
        }
    }

    override fun equals(other: Any?): Boolean =
        other is Version && significant == other.significant && canonicalLabel == other.canonicalLabel

    override fun hashCode(): Int = 31 * significant.hashCode() + (canonicalLabel?.hashCode() ?: 0)

    override fun toString(): String = segments.joinToString(".") + (label?.let { "-$it" } ?: "")

    public companion object {
        private val LABEL = Regex("[a-z0-9]+")

        /** Creates a bare version from its segments, e.g. `Version.of(1, 2, 0)`. */
        public fun of(vararg segments: Int): Version = Version(segments.toList())

        /**
         * Parses [value].
         *
         * @throws IllegalArgumentException for an empty string, a non-numeric or negative segment, an empty segment,
         * an empty label (`1.0-`) or a label with characters outside `[a-z0-9]`.
         */
        public fun parse(value: String): Version {
            require(value.isNotEmpty()) { "Version string must not be empty" }
            val dash = value.indexOf('-')
            val numbers = if (dash >= 0) value.substring(0, dash) else value
            val label = if (dash >= 0) value.substring(dash + 1) else null

            val segments = numbers.split('.').map { part ->
                require(part.isNotEmpty() && part.all { it in '0'..'9' }) {
                    "Invalid version segment '$part' in '$value'"
                }
                part.toIntOrNull() ?: throw IllegalArgumentException("Version segment '$part' is too large in '$value'")
            }
            if (label != null) require(LABEL.matches(label)) { "Invalid version label '$label' in '$value'" }
            return Version(segments, label)
        }

        /** Like [parse] but returns `null` for invalid input. */
        public fun parseOrNull(value: String): Version? = try {
            parse(value)
        } catch (_: IllegalArgumentException) {
            null
        }

        private fun compareLabels(a: String, b: String): Int {
            val runsA = runs(a)
            val runsB = runs(b)
            for (i in 0 until minOf(runsA.size, runsB.size)) {
                val x = runsA[i]
                val y = runsB[i]
                val cmp = if (x[0].isDigit() && y[0].isDigit()) compareDigitRuns(x, y) else x.compareTo(y)
                if (cmp != 0) return cmp
            }
            return runsA.size.compareTo(runsB.size)
        }

        private fun compareDigitRuns(x: String, y: String): Int {
            val a = x.trimStart('0')
            val b = y.trimStart('0')
            return if (a.length != b.length) a.length.compareTo(b.length) else a.compareTo(b)
        }

        private fun runs(label: String): List<String> {
            val result = ArrayList<String>()
            var start = 0
            for (i in 1..label.length) {
                if (i == label.length || label[i].isDigit() != label[start].isDigit()) {
                    result += label.substring(start, i)
                    start = i
                }
            }
            return result
        }
    }
}
