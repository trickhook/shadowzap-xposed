package io.github.trickhook.shadowzap.api.version

/**
 * A version constraint: either the wildcard `*` or a whitespace-separated conjunction of bounds using
 * `<`, `<=`, `=`, `>=` and `>`, for example `">=1.0 <2"`.
 *
 * Bounds must be bare versions. A candidate is checked on its numbers only: its label is ignored, so `1.5-rc`
 * satisfies `>=1.0 <2` while `2.0-rc` does not satisfy `<2`. The wildcard is satisfied by every version.
 */
public class VersionRange private constructor(public val bounds: List<Bound>) {
    /** One comparison, such as `>=1.0`. */
    public class Bound(public val op: Op, public val version: Version) {
        init {
            require(version.label == null) { "Range bounds must not be labeled: $op$version" }
        }

        /** Whether [candidate] (compared without its label) satisfies this bound. */
        public fun accepts(candidate: Version): Boolean {
            val cmp = candidate.withoutLabel().compareTo(version)
            return when (op) {
                Op.LT -> cmp < 0
                Op.LTE -> cmp <= 0
                Op.EQ -> cmp == 0
                Op.GTE -> cmp >= 0
                Op.GT -> cmp > 0
            }
        }

        override fun equals(other: Any?): Boolean = other is Bound && op == other.op && version == other.version
        override fun hashCode(): Int = 31 * op.hashCode() + version.hashCode()
        override fun toString(): String = "${op.symbol}$version"
    }

    /** Comparison operators, longest symbols first so parsing is unambiguous. */
    public enum class Op(public val symbol: String) {
        LTE("<="),
        GTE(">="),
        LT("<"),
        GT(">"),
        EQ("="),
    }

    /** Whether this is the wildcard range. */
    public val isAny: Boolean get() = bounds.isEmpty()

    /** Whether [version] satisfies every bound. */
    public fun satisfies(version: Version): Boolean = bounds.all { it.accepts(version) }

    override fun equals(other: Any?): Boolean = other is VersionRange && bounds.toSet() == other.bounds.toSet()
    override fun hashCode(): Int = bounds.toSet().hashCode()
    override fun toString(): String = if (bounds.isEmpty()) "*" else bounds.joinToString(" ")

    public companion object {
        /** The wildcard range `*`. */
        public val ANY: VersionRange = VersionRange(emptyList())

        /** A range from explicit bounds; an empty list is [ANY]. */
        public fun of(bounds: List<Bound>): VersionRange = if (bounds.isEmpty()) ANY else VersionRange(bounds.toList())

        /**
         * Parses `*` or a conjunction of bounds.
         *
         * @throws IllegalArgumentException for blank input, an unknown operator (`^`, `~`), a missing operator or a
         * labeled bound.
         */
        public fun parse(value: String): VersionRange {
            val trimmed = value.trim()
            require(trimmed.isNotEmpty()) { "Version range must not be empty" }
            if (trimmed == "*") return ANY
            val bounds = trimmed.split(WHITESPACE).map { token ->
                val op = Op.entries.firstOrNull { token.startsWith(it.symbol) }
                    ?: throw IllegalArgumentException("Invalid bound '$token' in version range '$value'")
                val version = Version.parse(token.substring(op.symbol.length))
                require(version.label == null) { "Range bounds must not be labeled: '$token' in '$value'" }
                Bound(op, version)
            }
            return VersionRange(bounds)
        }

        /** Like [parse] but returns `null` for invalid input. */
        public fun parseOrNull(value: String): VersionRange? = try {
            parse(value)
        } catch (_: IllegalArgumentException) {
            null
        }

        private val WHITESPACE = Regex("\\s+")
    }
}
