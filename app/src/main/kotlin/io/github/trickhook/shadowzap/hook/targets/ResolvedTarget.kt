package io.github.trickhook.shadowzap.hook.targets

import io.github.trickhook.shadowzap.api.hooks.HookTargetNotFoundException

/** How [HostTargets] found a target, from cheapest to the "not found" outcome. */
internal enum class Resolution {
    /** From the per-build cache written by an earlier discovery, verified by loading it again. */
    CACHED,

    /** One of the spec's exact candidates matched. */
    EXACT,

    /** Found by structure among the host's classes (the host renamed or moved it). */
    DISCOVERED,

    /** Not found. The hook that needs it must be skipped; [ResolvedTarget.values] is empty. */
    MISSING,
}

/**
 * The outcome of resolving [spec]: the matching [values] (one at most unless [TargetSpec.multiple]), how they were
 * found and a human-readable [detail] (the resolved names, or for [Resolution.MISSING] what was tried).
 */
internal class ResolvedTarget<T : Any>(
    val spec: TargetSpec<T>,
    val values: List<T>,
    val resolution: Resolution,
    val detail: String,
) {
    init {
        require(values.isEmpty() == (resolution == Resolution.MISSING)) {
            "Target '${spec.id}': $resolution with ${values.size} values"
        }
    }

    /** The first value, or `null` when [Resolution.MISSING]. */
    val value: T? get() = values.firstOrNull()

    /** Whether at least one value was found (anything but [Resolution.MISSING]). */
    val found: Boolean get() = values.isNotEmpty()

    /** The first value, or [HookTargetNotFoundException] naming the spec and what was tried ([toString]). */
    fun require(): T = value ?: throw HookTargetNotFoundException(toString())

    /** `id: RESOLUTION detail`, e.g. `script.fromFile: EXACT a.B#loadScriptFromFile(String, String, boolean)`. */
    override fun toString(): String = "${spec.id}: $resolution $detail"
}
