package io.github.trickhook.shadowzap.entry

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.trickhook.shadowzap.core.EntryKind
import io.github.trickhook.shadowzap.hook.LegacyHookBackend

/**
 * Entry point for legacy Xposed frameworks (declared in `assets/xposed_init`): old LSPatch, EdXposed and other
 * frameworks that only implement the classic API. Frameworks that read `META-INF/xposed/java_init.list` never load
 * this class.
 */
class LegacyEntry : IXposedHookZygoteInit, IXposedHookLoadPackage {
    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        modulePath = startupParam.modulePath
    }

    override fun handleLoadPackage(param: XC_LoadPackage.LoadPackageParam) {
        EntryBoot.boot(
            entry = EntryKind.LEGACY,
            packageName = param.packageName,
            processName = param.processName,
            appInfo = param.appInfo,
            hostClassLoader = param.classLoader,
            isFirstPackage = param.isFirstApplication,
            modulePath = modulePath.orEmpty(),
            frameworkDescription = "Xposed bridge ${frameworkVersion()}",
            backend = ::LegacyHookBackend,
        )
    }

    private fun frameworkVersion(): String = try {
        XposedBridge.getXposedVersion().toString()
    } catch (_: Throwable) {
        "unknown"
    }

    private companion object {
        /** Set in zygote by [initZygote]; inherited by every forked app process. */
        @Volatile
        var modulePath: String? = null
    }
}
