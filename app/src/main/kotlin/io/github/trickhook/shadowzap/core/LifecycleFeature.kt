package io.github.trickhook.shadowzap.core

import android.annotation.SuppressLint
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import io.github.trickhook.shadowzap.api.hooks.HookHandle
import io.github.trickhook.shadowzap.api.hooks.after
import io.github.trickhook.shadowzap.api.hooks.before
import io.github.trickhook.shadowzap.api.hooks.requireMethod
import java.util.concurrent.atomic.AtomicReference

/**
 * Captures the host [Application] as soon as it attaches (the earliest moment a usable context exists) and starts
 * activity tracking. `Instrumentation.callApplicationOnCreate` is a fallback for hosts that bypass the usual attach
 * path, and an already running application (late boot) is picked up directly.
 */
internal object LifecycleFeature : Feature {
    override val id: String = "lifecycle"

    override fun install(ctx: FeatureContext) {
        val lifecycle = ctx.kernel.lifecycle

        val attachHandle = AtomicReference<HookHandle?>()
        val attachBaseContext = ContextWrapper::class.java.requireMethod("attachBaseContext", Context::class.java)
        attachHandle.set(
            ctx.hooks.after(attachBaseContext) { call ->
                val app = call.thisObject as? Application ?: return@after
                if (lifecycle.attachApplication(app)) {
                    ctx.log.i("Application attached (${app.javaClass.name})")
                }
                // Every Activity and Service also passes through here; nothing more to learn once captured.
                attachHandle.get()?.unhook()
            },
        )

        val callOnCreate = Instrumentation::class.java.requireMethod("callApplicationOnCreate", Application::class.java)
        ctx.hooks.before(callOnCreate) { call ->
            val app = call.args[0] as? Application ?: return@before
            if (lifecycle.attachApplication(app)) ctx.log.w("Application captured late, in callApplicationOnCreate")
        }

        currentApplicationOrNull()?.let { app ->
            if (lifecycle.attachApplication(app)) ctx.log.w("Loader booted after the Application was created")
        }
    }

    // ActivityThread.currentApplication is the only way to find an Application created before we booted.
    @SuppressLint("PrivateApi")
    private fun currentApplicationOrNull(): Application? = try {
        Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as? Application
    } catch (_: Throwable) {
        null
    }
}
