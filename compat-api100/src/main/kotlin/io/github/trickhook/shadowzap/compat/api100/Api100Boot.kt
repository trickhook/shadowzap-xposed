package io.github.trickhook.shadowzap.compat.api100

import android.content.pm.ApplicationInfo

/**
 * The loader side of [Api100Entry], implemented in the app module by [IMPLEMENTATION] (the loader's classes are
 * internal to that module, and this module cannot depend on it). No libxposed type appears here, so the
 * implementation compiles against API 102 like the rest of the loader.
 */
interface Api100Boot {
    /**
     * Boots the loader for [packageName] when it is the host's main process and nothing booted yet.
     * [frameworkLog] mirrors warnings and errors into the framework's own log.
     */
    fun boot(
        packageName: String?,
        processName: String?,
        appInfo: ApplicationInfo?,
        hostClassLoader: ClassLoader?,
        isFirstPackage: Boolean,
        modulePath: String,
        frameworkDescription: String,
        frameworkLog: (String, Throwable?) -> Unit,
    )

    companion object {
        /** Fully qualified name of the implementation, which must have a public no-arg constructor. */
        const val IMPLEMENTATION: String = "io.github.trickhook.shadowzap.entry.Api100BootImpl"
    }
}
