package io.github.trickhook.shadowzap.core

import android.app.Activity
import android.content.Context
import io.github.trickhook.shadowzap.api.hooks.Hooks
import io.github.trickhook.shadowzap.api.host.Disposable
import io.github.trickhook.shadowzap.api.host.HostEnvironment
import io.github.trickhook.shadowzap.api.host.HostScope
import io.github.trickhook.shadowzap.api.host.Logger
import io.github.trickhook.shadowzap.hook.OwnedHooks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel

/**
 * The [HostScope] of one owner (a feature).
 *
 * Every hook, lifecycle subscription and coroutine created through it is recorded, and [dispose] releases all of
 * them: coroutines are cancelled, hooks removed and pending lifecycle subscriptions dropped. After disposal new hooks
 * and registrations are refused.
 */
internal class ScopedHost(
    private val kernel: Kernel,
    /** Owner tag, e.g. `feature:whatsapp-info`. */
    val owner: String,
    override val log: Logger,
) : HostScope, Disposable {
    private val bag = DisposableBag { log.e("Failed to release a resource of $owner", it) }

    override val environment: HostEnvironment get() = kernel.environment

    override val hooks: Hooks = OwnedHooks(kernel.hooks, bag)

    override val coroutineScope: CoroutineScope = kernel.scope.child(owner, log).also { scope ->
        bag.add(Disposable { scope.cancel("$owner stopped") })
    }

    override val applicationContext: Context? get() = kernel.lifecycle.application

    override val currentActivity: Activity? get() = kernel.lifecycle.currentActivity

    val isDisposed: Boolean get() = bag.isDisposed

    override fun onContext(block: (Context) -> Unit): Disposable =
        track(kernel.lifecycle.onContext(guarded(block)))

    override fun onActivity(block: (Activity) -> Unit): Disposable =
        track(kernel.lifecycle.onActivity(guarded(block)))

    override suspend fun awaitContext(): Context = kernel.lifecycle.awaitContext()

    override suspend fun awaitActivity(): Activity = kernel.lifecycle.awaitActivity()

    /** Ties an arbitrary resource (a seam registration, a listener) to this owner's lifetime. */
    fun <T : Disposable> track(resource: T): T = bag.add(resource)

    override fun dispose() = bag.dispose()

    /** Drops callbacks that fire after the owner stopped (a queued subscription may race with disposal). */
    private fun <T> guarded(block: (T) -> Unit): (T) -> Unit = { value -> if (!bag.isDisposed) block(value) }
}
