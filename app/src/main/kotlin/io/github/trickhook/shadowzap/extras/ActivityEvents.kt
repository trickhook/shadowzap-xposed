package io.github.trickhook.shadowzap.extras

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import io.github.trickhook.shadowzap.core.FeatureContext
import io.github.trickhook.shadowzap.core.OnceDisposable

/**
 * [Application.ActivityLifecycleCallbacks] with no-op defaults, so a feature overrides only the events it needs.
 * Every callback runs on the main thread.
 */
internal abstract class ActivityEvents : Application.ActivityLifecycleCallbacks {
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}

/**
 * Registers [events] on the host Application as soon as it exists (the kernel's `onContext`, which delivers the
 * Application itself), tied to the feature: disposing the feature's scope unregisters them. Events of activities
 * that started before the registration are not replayed; callers that care look at [FeatureContext.currentActivity].
 */
internal fun FeatureContext.watchActivities(events: ActivityEvents, onRegistered: (Application) -> Unit = {}) {
    onContext { context: Context ->
        val app = context as? Application ?: context.applicationContext as? Application
        if (app == null) {
            log.w("No Application to watch activities on (${context.javaClass.name})")
            return@onContext
        }
        app.registerActivityLifecycleCallbacks(events)
        track(OnceDisposable { app.unregisterActivityLifecycleCallbacks(events) })
        onRegistered(app)
    }
}
