package io.github.trickhook.shadowzap.core

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import io.github.trickhook.shadowzap.api.host.Disposable
import io.github.trickhook.shadowzap.api.host.Logger
import kotlinx.coroutines.suspendCancellableCoroutine
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/** One queued callback; delivered at most once, even if it races with its own cancellation. */
private class Subscriber<T>(private val block: (T) -> Unit) {
    private val done = AtomicBoolean(false)

    val isDone: Boolean get() = done.get()

    fun cancel() {
        done.set(true)
    }

    fun deliver(value: T, onError: (Throwable) -> Unit) {
        if (!done.compareAndSet(false, true)) return
        try {
            block(value)
        } catch (t: Throwable) {
            onError(t)
        }
    }
}

/**
 * A value that is set once and never replaced. Subscribers that arrive before it is set are queued and run once, in
 * subscription order, when it is; later subscribers run immediately. Each subscriber is isolated: one that throws
 * is reported to [onError] and the rest still run.
 */
internal class Latch<T : Any>(private val onError: (Throwable) -> Unit) {
    private val lock = Any()
    private var value: T? = null
    private val waiting = ArrayList<Subscriber<T>>()

    fun get(): T? = synchronized(lock) { value }

    /** Sets the value if it is not set yet and runs the queued subscribers. Returns `false` if already set. */
    fun offer(candidate: T): Boolean {
        val toRun = synchronized(lock) {
            if (value != null) return false
            value = candidate
            waiting.toList().also { waiting.clear() }
        }
        toRun.forEach { it.deliver(candidate, onError) }
        return true
    }

    fun subscribe(block: (T) -> Unit): Disposable {
        val subscriber = Subscriber(block)
        val current = synchronized(lock) {
            value ?: run {
                waiting += subscriber
                return OnceDisposable {
                    subscriber.cancel()
                    synchronized(lock) { waiting.remove(subscriber) }
                }
            }
        }
        subscriber.deliver(current, onError)
        return Disposable.NONE
    }

    /** Number of subscribers still waiting (for diagnostics and tests). */
    fun pendingCount(): Int = synchronized(lock) { waiting.size }
}

/**
 * Tracks the foreground activity and queues subscribers until one is usable.
 *
 * Only a resumed activity becomes current, it is held weakly, and it stops being current once [isUsable] says it is
 * finishing or destroyed. Subscribers always run through [dispatch] (the main thread in production) and re-check
 * usability there, going back to the queue if the activity went away in the meantime.
 */
internal class ActivityTracker<A : Any>(
    private val isUsable: (A) -> Boolean,
    private val dispatch: (() -> Unit) -> Unit,
    private val onError: (Throwable) -> Unit,
) {
    private val lock = Any()
    private var current: WeakReference<A>? = null
    private val waiting = ArrayList<Subscriber<A>>()

    /** The current activity if it is still usable. */
    fun current(): A? = synchronized(lock) { current?.get()?.takeIf(isUsable) }

    /** [activity] reached the foreground: it becomes current and queued subscribers run with it. */
    fun onResumed(activity: A) {
        val toRun = synchronized(lock) {
            current = WeakReference(activity)
            if (!isUsable(activity)) return
            waiting.toList().also { waiting.clear() }
        }
        toRun.forEach { subscriber -> dispatch { deliverOrRequeue(subscriber) } }
    }

    /** [activity] is being destroyed: forget it if it was current. */
    fun onDestroyed(activity: A) {
        synchronized(lock) {
            if (current?.get() === activity) current = null
        }
    }

    fun subscribe(block: (A) -> Unit): Disposable {
        val subscriber = Subscriber(block)
        val immediate = synchronized(lock) {
            val usable = current() != null
            if (!usable) waiting += subscriber
            usable
        }
        if (immediate) dispatch { deliverOrRequeue(subscriber) }
        return OnceDisposable {
            subscriber.cancel()
            synchronized(lock) { waiting.remove(subscriber) }
        }
    }

    fun pendingCount(): Int = synchronized(lock) { waiting.size }

    private fun deliverOrRequeue(subscriber: Subscriber<A>) {
        if (subscriber.isDone) return
        val activity = synchronized(lock) {
            current() ?: run {
                waiting += subscriber
                null
            }
        }
        if (activity != null) subscriber.deliver(activity, onError)
    }
}

/**
 * The host's application context and foreground activity.
 *
 * The context is the host [Application] itself, captured once when it attaches and never replaced by a service's or
 * an activity's context. Activities are tracked through [Application.ActivityLifecycleCallbacks], so nothing here
 * depends on host class names.
 */
internal class Lifecycle(private val log: Logger) {
    private val applicationLatch = Latch<Application> { log.e("Application context subscriber failed", it) }

    private val activities = ActivityTracker<Activity>(
        isUsable = { !it.isFinishing && !it.isDestroyed },
        dispatch = { block -> MainThread.run(block) },
        onError = { log.e("Activity subscriber failed", it) },
    )

    private val callbacksRegistered = AtomicBoolean(false)

    val application: Application? get() = applicationLatch.get()

    val currentActivity: Activity? get() = activities.current()

    fun onContext(block: (Context) -> Unit): Disposable = applicationLatch.subscribe(block)

    fun onActivity(block: (Activity) -> Unit): Disposable = activities.subscribe(block)

    suspend fun awaitContext(): Context = suspendCancellableCoroutine { continuation ->
        val subscription = onContext { continuation.resume(it) }
        continuation.invokeOnCancellation { subscription.dispose() }
    }

    suspend fun awaitActivity(): Activity = suspendCancellableCoroutine { continuation ->
        val subscription = onActivity { continuation.resume(it) }
        continuation.invokeOnCancellation { subscription.dispose() }
    }

    /**
     * Records the host [Application]. Only the first call has an effect; it starts activity tracking and then runs
     * the queued context subscribers. Returns whether this call captured the application.
     */
    fun attachApplication(app: Application): Boolean {
        if (applicationLatch.get() != null) return false
        if (callbacksRegistered.compareAndSet(false, true)) {
            try {
                app.registerActivityLifecycleCallbacks(ActivityCallbacks())
            } catch (t: Throwable) {
                callbacksRegistered.set(false)
                log.e("Could not register activity callbacks", t)
            }
        }
        return applicationLatch.offer(app)
    }

    private inner class ActivityCallbacks : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) = activities.onResumed(activity)
        override fun onActivityDestroyed(activity: Activity) = activities.onDestroyed(activity)
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    }
}
