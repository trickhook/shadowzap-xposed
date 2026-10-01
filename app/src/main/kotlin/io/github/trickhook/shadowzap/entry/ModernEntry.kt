package io.github.trickhook.shadowzap.entry

import android.app.Instrumentation
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.trickhook.shadowzap.core.EntryKind
import io.github.trickhook.shadowzap.core.LoaderIdentity
import io.github.trickhook.shadowzap.core.LogSink
import io.github.trickhook.shadowzap.hook.ModernHookBackend
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Entry point for frameworks implementing libxposed API 101+ (Vector >= 2.1, JingMatrix LSPatch >= 1.0), declared
 * in `META-INF/xposed/java_init.list`.
 *
 * Boots in [onPackageReady] (the host class loader exists, the Application does not yet). Frameworks only dispatch
 * that callback on Android 9+, so on Android 8.x the entry boots from `Instrumentation.newApplication` instead,
 * which runs just before the Application attaches. This path never touches the legacy `de.robv.android.xposed` API.
 */
class ModernEntry : XposedModule() {
    @Volatile
    private var processName: String? = null

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        processName = param.processName
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P && !param.isSystemServer) {
            try {
                bootFromNewApplication()
            } catch (t: Throwable) {
                log(Log.ERROR, LoaderIdentity.LOG_TAG, "Cannot hook Instrumentation.newApplication", t)
            }
        }
    }

    override fun onPackageReady(param: PackageReadyParam) {
        boot(param.packageName, param.applicationInfo, param.classLoader, param.isFirstPackage)
    }

    private fun bootFromNewApplication() {
        val newApplication = Instrumentation::class.java.getDeclaredMethod(
            "newApplication",
            ClassLoader::class.java,
            String::class.java,
            Context::class.java,
        )
        val booted = AtomicBoolean(false)
        hook(newApplication)
            .setPriority(XposedInterface.PRIORITY_DEFAULT)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                if (booted.compareAndSet(false, true)) {
                    val context = chain.getArg(2) as? Context
                    boot(context?.packageName, context?.applicationInfo, chain.getArg(0) as? ClassLoader, true)
                }
                chain.proceed()
            }
    }

    private fun boot(packageName: String?, appInfo: ApplicationInfo?, loader: ClassLoader?, first: Boolean) {
        EntryBoot.boot(
            entry = EntryKind.MODERN,
            packageName = packageName,
            processName = processName,
            appInfo = appInfo,
            hostClassLoader = loader,
            isFirstPackage = first,
            modulePath = moduleApplicationInfo.sourceDir.orEmpty(),
            frameworkDescription = "$frameworkName $frameworkVersion (API $apiVersion)",
            extraSinks = listOf(frameworkLogSink()),
            backend = { ModernHookBackend(this) },
        )
    }

    /** Mirrors warnings and errors into the framework's own log, which users attach to bug reports. */
    private fun frameworkLogSink() = LogSink { entry, throwable ->
        if (entry.level.priority >= Log.WARN) {
            log(entry.level.priority, LoaderIdentity.LOG_TAG, "[${entry.logger}] ${entry.message}", throwable)
        }
    }
}
