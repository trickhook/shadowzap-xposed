package io.github.trickhook.shadowzap.api.host

import android.app.Activity
import android.content.Context
import io.github.trickhook.shadowzap.api.hooks.Hooks
import kotlinx.coroutines.CoroutineScope

/**
 * Everything native code running inside the host process can use.
 *
 * Every feature receives its own scope. Hooks, lifecycle subscriptions and coroutines created through a scope are
 * owned by it and are released automatically when the owner stops, so a stopped feature leaves nothing behind.
 */
public interface HostScope {
    /** Static facts about the host process. */
    public val environment: HostEnvironment

    /** Method hooking. */
    public val hooks: Hooks

    /** Logger namespaced to the owner of this scope. */
    public val log: Logger

    /**
     * Coroutine scope tied to the owner's lifetime. It uses a supervisor job, so one failing child does not cancel
     * the others, and it is cancelled when the owner stops. Uncaught exceptions are logged, never rethrown.
     */
    public val coroutineScope: CoroutineScope

    /** The host [android.app.Application], or `null` if it has not been created yet. */
    public val applicationContext: Context?

    /**
     * The foreground activity that is neither finishing nor destroyed, or `null` if there is none.
     * Never hold on to it; ask again when you need it.
     */
    public val currentActivity: Activity?

    /**
     * Runs [block] with the host [android.app.Application] context: immediately when it already exists, otherwise
     * as soon as it is created (on the main thread, while the application is attaching, so keep [block] short and
     * move slow work to [coroutineScope]).
     *
     * When [block] runs during attach, the Application's base context is set but `Application.onCreate` has not run
     * and `getApplicationContext()` still returns `null`: use the context passed to [block] itself (it *is* the
     * Application) instead of `context.applicationContext`, and defer libraries that expect a fully created
     * application (for example ones that call `getApplicationContext()` internally) to a later point such as
     * [onActivity].
     *
     * An exception thrown by [block] is logged and never affects other subscribers.
     * Dispose the returned handle to cancel a subscription that has not run yet.
     */
    public fun onContext(block: (Context) -> Unit): Disposable

    /**
     * Runs [block] on the main thread with the first usable (resumed, not finishing) activity: immediately when one
     * exists, otherwise when the next activity resumes.
     *
     * An exception thrown by [block] is logged and never affects other subscribers.
     * Dispose the returned handle to cancel a subscription that has not run yet.
     */
    public fun onActivity(block: (Activity) -> Unit): Disposable

    /** Suspends until the application context exists and returns it. */
    public suspend fun awaitContext(): Context

    /**
     * Suspends until a usable activity exists and returns it. The caller resumes on its own dispatcher, so switch
     * to the main thread before touching views.
     */
    public suspend fun awaitActivity(): Activity
}

/** Shorthand for [HostEnvironment.classLoader], the host app's class loader. */
public val HostScope.hostClassLoader: ClassLoader get() = environment.classLoader
