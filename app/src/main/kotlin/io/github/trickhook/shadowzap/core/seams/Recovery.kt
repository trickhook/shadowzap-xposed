package io.github.trickhook.shadowzap.core.seams

import android.app.Activity
import android.app.AlertDialog
import io.github.trickhook.shadowzap.api.host.Logger
import io.github.trickhook.shadowzap.core.AppControl
import io.github.trickhook.shadowzap.core.MainThread
import io.github.trickhook.shadowzap.core.Paths

/**
 * Shows the recovery options. A feature may install a richer presenter into
 * [io.github.trickhook.shadowzap.core.Kernel.recovery]; callers always go through the slot.
 */
internal fun interface RecoveryPresenter {
    /** Shows the recovery UI on [activity]. May be called from any thread. */
    fun show(activity: Activity)
}

/** Kernel fallback: restart WhatsApp, optionally forgetting every resolved hook target first. */
internal class BasicRecoveryPresenter(
    private val paths: Paths,
    private val log: Logger,
) : RecoveryPresenter {
    override fun show(activity: Activity) = MainThread.run {
        if (activity.isFinishing || activity.isDestroyed) return@run
        try {
            AlertDialog.Builder(activity)
                .setTitle("Shadowzap recovery")
                .setItems(arrayOf("Restart WhatsApp", "Find hooks again and restart")) { _, which ->
                    if (which == 1) clearHookTargets()
                    AppControl.reload(activity, log)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } catch (t: Throwable) {
            log.e("Could not show the recovery dialog", t)
        }
    }

    private fun clearHookTargets() {
        val files = paths.hookTargetsCacheDir.listFiles { file -> file.name.startsWith("hook-targets-") }.orEmpty()
        for (file in files) {
            if (!file.delete()) log.w("Could not delete ${file.path}")
        }
        log.i("Forgot ${files.size} hook target cache file(s)")
    }
}
