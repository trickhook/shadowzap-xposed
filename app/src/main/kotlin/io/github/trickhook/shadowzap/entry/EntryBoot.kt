package io.github.trickhook.shadowzap.entry

import android.content.pm.ApplicationInfo
import android.util.Log
import io.github.trickhook.shadowzap.core.Bootstrap
import io.github.trickhook.shadowzap.core.EntryKind
import io.github.trickhook.shadowzap.core.Env
import io.github.trickhook.shadowzap.core.HostGate
import io.github.trickhook.shadowzap.core.LoaderIdentity
import io.github.trickhook.shadowzap.core.LogSink
import io.github.trickhook.shadowzap.hook.HookBackend

/**
 * The boot path shared by every entry point: host gate, environment, boot guard, then [Bootstrap].
 *
 * Each entry passes its own [HookBackend] factory, so this code never references a framework flavour: the modern
 * path (target API 102, whose class loader refuses `de.robv.android.xposed`) never loads a legacy class.
 */
internal object EntryBoot {
    fun boot(
        entry: EntryKind,
        packageName: String?,
        processName: String?,
        appInfo: ApplicationInfo?,
        hostClassLoader: ClassLoader?,
        isFirstPackage: Boolean,
        modulePath: String,
        frameworkDescription: String,
        extraSinks: List<LogSink> = emptyList(),
        backend: () -> HookBackend,
    ) {
        val decision = HostGate.decide(packageName, processName, appInfo, hostClassLoader, isFirstPackage)
        if (decision is HostGate.Decision.Skip) {
            Log.d(LoaderIdentity.LOG_TAG, "$entry entry: not booting in ${processName ?: packageName}: ${decision.reason}")
            return
        }

        // Everything that can fail on odd framework input happens before the claim, so a failure here never
        // blocks another entry from booting.
        val env: Env
        val hooks: HookBackend
        try {
            env = Env(
                entry = entry,
                packageName = checkNotNull(packageName),
                processName = checkNotNull(processName),
                appInfo = checkNotNull(appInfo),
                hostClassLoader = checkNotNull(hostClassLoader),
                modulePath = modulePath,
                frameworkDescription = frameworkDescription,
            )
            hooks = backend()
        } catch (t: Throwable) {
            Log.e(LoaderIdentity.LOG_TAG, "$entry entry: cannot prepare the boot in $processName", t)
            return
        }

        if (!BootGuard.claim(entry.name)) {
            Log.i(LoaderIdentity.LOG_TAG, "$entry entry: already booted by ${BootGuard.claimedBy()}; skipping")
            return
        }
        Bootstrap.start(env, hooks, extraSinks = extraSinks)
    }
}
