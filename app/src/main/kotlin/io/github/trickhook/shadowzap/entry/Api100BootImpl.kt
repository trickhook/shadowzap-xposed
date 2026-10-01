package io.github.trickhook.shadowzap.entry

import android.content.pm.ApplicationInfo
import android.util.Log
import io.github.trickhook.shadowzap.compat.api100.Api100Boot
import io.github.trickhook.shadowzap.core.EntryKind
import io.github.trickhook.shadowzap.core.LoaderIdentity
import io.github.trickhook.shadowzap.core.LogSink
import io.github.trickhook.shadowzap.hook.LegacyHookBackend

/**
 * Loader side of `Api100Entry` (module `:compat-api100`), instantiated by name from there. API 100 frameworks
 * (LSPosed 1.9.x, Vector <= 2.0) expose the legacy XposedBridge API to modules, so hooks use [LegacyHookBackend].
 * Only `Api100Entry` loads this class, so the API 101+ path never touches `de.robv.android.xposed`.
 */
class Api100BootImpl : Api100Boot {
    override fun boot(
        packageName: String?,
        processName: String?,
        appInfo: ApplicationInfo?,
        hostClassLoader: ClassLoader?,
        isFirstPackage: Boolean,
        modulePath: String,
        frameworkDescription: String,
        frameworkLog: (String, Throwable?) -> Unit,
    ) {
        EntryBoot.boot(
            entry = EntryKind.API100,
            packageName = packageName,
            processName = processName,
            appInfo = appInfo,
            hostClassLoader = hostClassLoader,
            isFirstPackage = isFirstPackage,
            modulePath = modulePath,
            frameworkDescription = frameworkDescription,
            extraSinks = listOf(
                LogSink { entry, throwable ->
                    if (entry.level.priority >= Log.WARN) {
                        frameworkLog("${LoaderIdentity.LOG_TAG} [${entry.logger}] ${entry.message}", throwable)
                    }
                },
            ),
            backend = ::LegacyHookBackend,
        )
    }
}
