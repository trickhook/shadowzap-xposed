package io.github.trickhook.shadowzap.api.hooks

import io.github.trickhook.shadowzap.api.host.Disposable
import java.lang.reflect.Member

/** Standard hook priorities. Callbacks with a higher priority run their `before` first and their `after` last. */
public object HookPriority {
    public const val HIGHEST: Int = 10_000
    public const val HIGH: Int = 75
    public const val DEFAULT: Int = 50
    public const val LOW: Int = 25
    public const val LOWEST: Int = -10_000
}

/**
 * Callback invoked around a hooked method or constructor. Override only what you need.
 *
 * A callback that throws is logged and skipped; it never crashes the host. Changes it made to the call before
 * throwing are rolled back.
 *
 * Results are checked against the method's return type: `null` for a primitive or an object of the wrong type is
 * logged and dropped (a `before` decision then lets the original run; an `after` change keeps the previous result),
 * so a wrong result can never reach the host's caller.
 */
public interface HookCallback {
    /**
     * Runs before the original. Setting [HookCall.result] or [HookCall.throwable] here skips the original and every
     * lower-priority `before` callback; `after` callbacks of the callbacks that already ran still run.
     */
    public fun before(call: HookCall) {}

    /** Runs after the original (or after a `before` decided the outcome). May replace the result or throwable. */
    public fun after(call: HookCall) {}
}

/** A single installed callback. */
public interface HookHandle : Disposable {
    /** The hooked method or constructor. */
    public val member: Member

    /** `false` once [unhook] has been called. */
    public val isActive: Boolean

    /** Removes exactly this callback. Other callbacks on the same member are not affected. Idempotent. */
    public fun unhook()

    override fun dispose(): Unit = unhook()
}

/**
 * Method and constructor hooking, independent of the Xposed framework flavour in use.
 *
 * Implementations install one framework hook per member and dispatch to registered callbacks by priority, so adding
 * and removing callbacks is cheap and exact.
 */
public interface Hooks {
    /**
     * Installs [callback] on [member], which must be a [java.lang.reflect.Method] or [java.lang.reflect.Constructor].
     *
     * @throws IllegalArgumentException if [member] is neither a method nor a constructor, or is abstract.
     * @throws IllegalStateException if the framework refuses the hook.
     */
    public fun hook(member: Member, priority: Int = HookPriority.DEFAULT, callback: HookCallback): HookHandle

    /**
     * Calls the original implementation of [member], bypassing every hook (ours and other modules').
     * For a constructor with a `null` [thisObject], a new instance is created and returned.
     *
     * Exceptions thrown by the original are rethrown unwrapped.
     *
     * @throws UnsupportedOperationException if a new instance is requested and the runtime cannot allocate one
     * without running a constructor (legacy frameworks only).
     */
    public fun invokeOriginal(member: Member, thisObject: Any?, vararg args: Any?): Any?
}

/** Installs a callback that only runs [block] before [member]. */
public inline fun Hooks.before(
    member: Member,
    priority: Int = HookPriority.DEFAULT,
    crossinline block: (HookCall) -> Unit,
): HookHandle = hook(member, priority, object : HookCallback {
    override fun before(call: HookCall) = block(call)
})

/** Installs a callback that only runs [block] after [member]. */
public inline fun Hooks.after(
    member: Member,
    priority: Int = HookPriority.DEFAULT,
    crossinline block: (HookCall) -> Unit,
): HookHandle = hook(member, priority, object : HookCallback {
    override fun after(call: HookCall) = block(call)
})

/** Replaces [member] entirely: the original never runs and the call returns whatever [block] returns. */
public inline fun Hooks.replace(
    member: Member,
    priority: Int = HookPriority.DEFAULT,
    crossinline block: (HookCall) -> Any?,
): HookHandle = hook(member, priority, object : HookCallback {
    override fun before(call: HookCall) {
        call.result = block(call)
    }
})

/** Installs the same [callback] on every member in [members]. Stops and rolls back on the first failure. */
public fun Hooks.hookAll(
    members: Iterable<Member>,
    priority: Int = HookPriority.DEFAULT,
    callback: HookCallback,
): List<HookHandle> {
    val installed = ArrayList<HookHandle>()
    try {
        for (member in members) installed += hook(member, priority, callback)
    } catch (t: Throwable) {
        installed.forEach { it.unhook() }
        throw t
    }
    return installed
}
