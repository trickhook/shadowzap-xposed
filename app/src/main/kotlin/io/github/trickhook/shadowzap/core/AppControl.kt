package io.github.trickhook.shadowzap.core

import android.content.Context
import android.content.Intent
import android.os.Process
import io.github.trickhook.shadowzap.api.host.Logger
import kotlin.system.exitProcess

/** Process-level actions on the host app. */
internal object AppControl {
    /**
     * Restarts the host: relaunches its launcher activity in a fresh task and kills this process, so every hook
     * starts from scratch.
     *
     * @throws IllegalStateException when the host has no launcher activity.
     */
    fun reload(context: Context, log: Logger): Nothing {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: throw IllegalStateException("${context.packageName} has no launch intent; cannot reload")
        log.i("Reloading ${context.packageName}")
        context.startActivity(Intent.makeRestartActivityTask(launch.component))
        Process.killProcess(Process.myPid())
        exitProcess(0)
    }
}
