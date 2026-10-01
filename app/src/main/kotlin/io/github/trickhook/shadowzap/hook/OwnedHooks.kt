package io.github.trickhook.shadowzap.hook

import io.github.trickhook.shadowzap.api.hooks.HookCallback
import io.github.trickhook.shadowzap.api.hooks.HookHandle
import io.github.trickhook.shadowzap.api.hooks.Hooks
import io.github.trickhook.shadowzap.core.DisposableBag
import java.lang.reflect.Member

/**
 * A [Hooks] view that records every hook it installs in [bag], so the owner (a feature or a plugin) can release all
 * of them at once. Hooks unhooked individually are forgotten immediately.
 */
internal class OwnedHooks(private val delegate: Hooks, private val bag: DisposableBag) : Hooks {
    override fun hook(member: Member, priority: Int, callback: HookCallback): HookHandle {
        check(!bag.isDisposed) { "Owner already stopped; refusing to hook $member" }
        val handle = delegate.hook(member, priority, callback)
        return bag.add(TrackedHandle(handle, bag))
    }

    override fun invokeOriginal(member: Member, thisObject: Any?, vararg args: Any?): Any? =
        delegate.invokeOriginal(member, thisObject, *args)

    private class TrackedHandle(private val handle: HookHandle, private val bag: DisposableBag) : HookHandle {
        override val member: Member get() = handle.member
        override val isActive: Boolean get() = handle.isActive
        override fun unhook() {
            handle.unhook()
            bag.remove(this)
        }
    }
}
