package io.github.trickhook.shadowzap.hook

import io.github.trickhook.shadowzap.api.hooks.HookCall
import io.github.trickhook.shadowzap.api.hooks.HookCallback
import io.github.trickhook.shadowzap.api.hooks.HookHandle
import io.github.trickhook.shadowzap.api.hooks.Hooks
import io.github.trickhook.shadowzap.api.host.Logger
import java.lang.reflect.Constructor
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Framework-agnostic [Hooks] implementation.
 *
 * Each member gets exactly one framework hook (installed lazily by the [HookBackend]) and a priority-sorted list of
 * callbacks. Calls work on an immutable snapshot of that list, so hooking and unhooking never disturb in-flight calls
 * and never need a framework round-trip after the first hook.
 */
internal class HookDispatcher(
    private val backend: HookBackend,
    private val log: Logger,
) : Hooks {
    private val members = ConcurrentHashMap<Member, MemberHooks>()
    private val installLock = Any()
    private val sequence = AtomicLong()

    val backendName: String get() = backend.name

    override fun hook(member: Member, priority: Int, callback: HookCallback): HookHandle {
        requireHookable(member)
        val hooks = members[member] ?: synchronized(installLock) {
            members[member] ?: MemberHooks(member, log).also { created ->
                // Installing first means a refused hook leaves no half-registered member behind.
                created.backendHook = install(member, created)
                members[member] = created
            }
        }
        val entry = hooks.add(priority, sequence.incrementAndGet(), callback)
        return Handle(hooks, entry)
    }

    /**
     * Frameworks report a refused hook in their own ways (an exception, an `Error` such as libxposed's
     * `HookFailedError`, or a `null` handle); the [Hooks] contract promises [IllegalStateException].
     */
    private fun install(member: Member, target: MemberHooks): BackendHook = try {
        backend.install(member, target)
    } catch (e: IllegalArgumentException) {
        throw e
    } catch (e: IllegalStateException) {
        throw e
    } catch (t: Throwable) {
        throw IllegalStateException("Framework refused to hook $member", t)
    }

    override fun invokeOriginal(member: Member, thisObject: Any?, vararg args: Any?): Any? {
        requireHookable(member)
        @Suppress("UNCHECKED_CAST")
        return backend.invokeOriginal(member, thisObject, args as Array<Any?>)
    }

    /** Number of members with a framework hook, for diagnostics and tests. */
    fun installedMemberCount(): Int = members.size

    /** Number of live callbacks on [member]. */
    fun callbackCount(member: Member): Int = members[member]?.size() ?: 0

    private fun requireHookable(member: Member) {
        when (member) {
            is Method -> require(!Modifier.isAbstract(member.modifiers)) { "Cannot hook abstract method $member" }
            is Constructor<*> -> Unit
            else -> throw IllegalArgumentException("Only methods and constructors can be hooked, got $member")
        }
    }

    private class Handle(private val hooks: MemberHooks, private val entry: CallbackEntry) : HookHandle {
        override val member: Member get() = hooks.member
        override val isActive: Boolean get() = !entry.removed
        override fun unhook() {
            hooks.remove(entry)
        }

        override fun toString(): String = "HookHandle(${hooks.member}, priority=${entry.priority}, active=$isActive)"
    }
}

/** One registered callback. Identity matters: unhooking removes exactly this entry. */
internal class CallbackEntry(val priority: Int, val sequence: Long, val callback: HookCallback) {
    @Volatile
    var removed: Boolean = false
}

/** All callbacks of one hooked member. */
internal class MemberHooks(val member: Member, private val log: Logger) {
    @Volatile
    private var snapshot: Array<CallbackEntry> = emptyArray()

    /** The framework hook; kept installed even when no callbacks remain (the empty fast path is cheap). */
    @Volatile
    var backendHook: BackendHook? = null

    fun size(): Int = snapshot.size

    fun add(priority: Int, sequence: Long, callback: HookCallback): CallbackEntry {
        val entry = CallbackEntry(priority, sequence, callback)
        synchronized(this) {
            // Higher priority first; equal priorities keep registration order.
            snapshot = (snapshot + entry).sortedWith(ORDER).toTypedArray()
        }
        return entry
    }

    fun remove(entry: CallbackEntry) {
        synchronized(this) {
            if (entry.removed) return
            entry.removed = true
            snapshot = snapshot.filter { it !== entry }.toTypedArray()
        }
    }

    /** Starts a call; `null` when there are no callbacks (run the original untouched). */
    fun begin(thisObject: Any?, args: Array<Any?>): ActiveCall? {
        val entries = snapshot
        if (entries.isEmpty()) return null
        return ActiveCall(member, thisObject, args, entries, log)
    }

    /**
     * First phase for before/after backends (XposedBridge): starts the call and runs the `before` callbacks.
     * Returns `null` when there are no callbacks. When [ActiveCall.isOriginalSkipped] is then `true`, the backend
     * must apply the decided outcome instead of running the original; either way it must later call
     * [ActiveCall.finishAfter] with what the call produced.
     */
    fun beforePhase(thisObject: Any?, args: Array<Any?>): ActiveCall? {
        val call = begin(thisObject, args) ?: return null
        call.runBefore()
        call.dropInvalidDecision()
        return call
    }

    /**
     * Runs a whole call for around-style backends: `before` callbacks, then [original] unless skipped, then `after`
     * callbacks. Returns the final result or throws the final throwable.
     */
    fun dispatch(thisObject: Any?, args: Array<Any?>, original: (Array<Any?>) -> Any?): Any? {
        val call = beforePhase(thisObject, args) ?: return original(args)
        if (call.isOriginalSkipped) {
            call.finishAfter(call.result, call.throwable)
        } else {
            val outcome = runCatching { original(call.args) }
            call.finishAfter(outcome.getOrNull(), outcome.exceptionOrNull())
        }
        return call.resultOrThrow()
    }

    private companion object {
        val ORDER: Comparator<CallbackEntry> =
            compareByDescending<CallbackEntry> { it.priority }.thenBy { it.sequence }
    }
}

/**
 * State of one call, shared by its callbacks. Follows the classic Xposed contract: `before` callbacks run by
 * descending priority until one decides the outcome; `after` callbacks run in reverse order for exactly the callbacks
 * whose `before` ran. A throwing callback is logged and its changes to the outcome are rolled back.
 */
internal class ActiveCall(
    override val member: Member,
    override val thisObject: Any?,
    override val args: Array<Any?>,
    private val entries: Array<CallbackEntry>,
    private val log: Logger,
) : HookCall {
    private var inBefore = false
    private var decided = false
    private var beforeCount = 0
    private var currentResult: Any? = null
    private var currentThrowable: Throwable? = null

    override var result: Any?
        get() = currentResult
        set(value) {
            currentResult = value
            currentThrowable = null
            if (inBefore) decided = true
        }

    override var throwable: Throwable?
        get() = currentThrowable
        set(value) {
            currentThrowable = value
            if (value != null) currentResult = null
            if (inBefore) decided = true
        }

    override val isOriginalSkipped: Boolean get() = decided

    fun runBefore() {
        inBefore = true
        try {
            for (entry in entries) {
                beforeCount++
                try {
                    entry.callback.before(this)
                } catch (t: Throwable) {
                    log.e("Hook 'before' callback failed on $member; ignoring it", t)
                    currentResult = null
                    currentThrowable = null
                    decided = false
                }
                if (decided) break
            }
        } finally {
            inBefore = false
        }
    }

    /**
     * Refuses an outcome decided in `before` that the member cannot return (see [ReturnTypes]): the decision is
     * dropped, so the original runs as if no callback had decided.
     */
    fun dropInvalidDecision() {
        if (!decided) return
        val problem = outcomeProblem() ?: return
        log.e("Hook callbacks on $member decided an invalid outcome ($problem); running the original instead")
        decided = false
        currentResult = null
        currentThrowable = null
    }

    /**
     * Second phase: records what the call produced ([result]/[throwable] of the original, or the decided outcome
     * when it was skipped), runs the `after` callbacks and makes sure the final outcome is one the member can
     * return. An invalid final outcome is logged and replaced by the one from before the `after` callbacks.
     */
    fun finishAfter(result: Any?, throwable: Throwable?) {
        completeOriginal(result, throwable)
        val baselineResult = currentResult
        val baselineThrowable = currentThrowable
        runAfter()
        val problem = outcomeProblem() ?: return
        log.e("Hook callbacks on $member returned an invalid result ($problem); keeping the previous outcome")
        currentResult = baselineResult
        currentThrowable = baselineThrowable
    }

    /** Records the outcome of the original (or of the framework, for phase-based backends). */
    fun completeOriginal(result: Any?, throwable: Throwable?) {
        currentResult = if (throwable == null) result else null
        currentThrowable = throwable
    }

    private fun outcomeProblem(): String? =
        if (currentThrowable != null) null else ReturnTypes.problem(member, currentResult)

    fun runAfter() {
        for (i in beforeCount - 1 downTo 0) {
            val savedResult = currentResult
            val savedThrowable = currentThrowable
            try {
                entries[i].callback.after(this)
            } catch (t: Throwable) {
                log.e("Hook 'after' callback failed on $member; ignoring it", t)
                currentResult = savedResult
                currentThrowable = savedThrowable
            }
        }
    }
}
