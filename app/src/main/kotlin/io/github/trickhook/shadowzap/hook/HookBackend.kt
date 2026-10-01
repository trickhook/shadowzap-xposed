package io.github.trickhook.shadowzap.hook

import java.lang.reflect.Member

/**
 * What an Xposed flavour must provide: one framework hook per member that forwards every call to a
 * [MemberHooks] (via [MemberHooks.dispatch] or the [MemberHooks.begin] phases), and a way to call the original.
 *
 * Implementations must not reference any other flavour's classes: the modern path may never touch
 * `de.robv.android.xposed`, and the legacy path may never touch libxposed.
 */
internal interface HookBackend {
    /** Short name for logs, e.g. `libxposed` or `XposedBridge`. */
    val name: String

    /**
     * Installs the single framework hook for [member] that feeds [target].
     *
     * @throws Throwable when the framework refuses the hook; the dispatcher then reports it to the caller.
     */
    fun install(member: Member, target: MemberHooks): BackendHook

    /** Invokes the original [member], bypassing all hooks. Exceptions of the original are rethrown unwrapped. */
    fun invokeOriginal(member: Member, thisObject: Any?, args: Array<Any?>): Any?
}

/** A framework-level hook installed by a [HookBackend]. */
internal fun interface BackendHook {
    fun remove()
}
