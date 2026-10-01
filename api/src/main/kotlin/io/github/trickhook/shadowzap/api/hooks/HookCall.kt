package io.github.trickhook.shadowzap.api.hooks

import java.lang.reflect.Member

/**
 * State of one invocation of a hooked member, shared by all callbacks of that invocation.
 *
 * Not thread-safe and only valid during the callback; do not keep a reference to it.
 */
public interface HookCall {
    /** The hooked method or constructor. */
    public val member: Member

    /** The receiver, or `null` for static methods. For constructors this is the instance being initialised. */
    public val thisObject: Any?

    /** Arguments of the call. Replacing elements in `before` changes what the original receives. */
    public val args: Array<Any?>

    /**
     * The call's return value. Setting it clears [throwable]; in `before` it also skips the original.
     * For `void` methods and constructors the value is ignored.
     */
    public var result: Any?

    /** The exception the call will throw, or `null`. Setting it clears [result]; in `before` it skips the original. */
    public var throwable: Throwable?

    /** Whether the outcome was already decided by a `before` callback, meaning the original will not run. */
    public val isOriginalSkipped: Boolean

    /** Returns [result], or throws [throwable] when one is set. */
    public fun resultOrThrow(): Any? {
        throwable?.let { throw it }
        return result
    }
}

/** Returns argument [index] cast to [T]. */
@Suppress("UNCHECKED_CAST")
public fun <T> HookCall.arg(index: Int): T = args[index] as T

/** Returns [HookCall.thisObject] cast to [T]. */
@Suppress("UNCHECKED_CAST")
public fun <T> HookCall.self(): T = thisObject as T
