package io.github.trickhook.shadowzap.compat.api100

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

/**
 * Entry point for frameworks implementing libxposed API 100: official LSPosed 1.9.x and Vector up to 2.0. They
 * read `META-INF/xposed/java_init.list`, ignore `assets/xposed_init`, and construct every listed class through this
 * `(XposedInterface, ModuleLoadedParam)` constructor. API 101+ frameworks need a no-arg constructor instead, so they
 * skip this class (and boot `ModernEntry`), while API 100 frameworks fail on `ModernEntry` and boot this one.
 *
 * Hooks go through the legacy XposedBridge API, which API 100 frameworks expose to modules. The loader itself is
 * reached through [Api100Boot]; the host checks and the process-wide boot guard live there.
 */
class Api100Entry(base: XposedInterface, param: ModuleLoadedParam) : XposedModule(base, param) {
    private val processName: String? = param.processName
    private val isSystemServer: Boolean = param.isSystemServer

    override fun onPackageLoaded(param: PackageLoadedParam) {
        try {
            if (isSystemServer || !param.isFirstPackage) return
            val loader = Class.forName(Api100Boot.IMPLEMENTATION, true, Api100Entry::class.java.classLoader)
                .getDeclaredConstructor()
                .newInstance() as Api100Boot
            loader.boot(
                packageName = param.packageName,
                processName = processName,
                appInfo = param.applicationInfo,
                hostClassLoader = param.classLoader,
                isFirstPackage = true,
                modulePath = modulePath(),
                frameworkDescription = frameworkDescription(),
                frameworkLog = ::logToFramework,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Could not boot through libxposed API 100", t)
        }
    }

    private fun modulePath(): String = try {
        applicationInfo?.sourceDir.orEmpty()
    } catch (_: Throwable) {
        ""
    }

    private fun frameworkDescription(): String = try {
        "$frameworkName $frameworkVersion (API 100)"
    } catch (_: Throwable) {
        "libxposed (API 100)"
    }

    private fun logToFramework(message: String, throwable: Throwable?) {
        if (throwable != null) log(message, throwable) else log(message)
    }

    private companion object {
        const val TAG = "Shadowzap"
    }
}
