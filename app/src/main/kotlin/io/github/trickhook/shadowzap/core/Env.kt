package io.github.trickhook.shadowzap.core

import android.content.pm.ApplicationInfo
import io.github.trickhook.shadowzap.api.host.HostEnvironment
import io.github.trickhook.shadowzap.BuildConfig
import java.io.File

/** Which Xposed entry point booted the loader. */
internal enum class EntryKind {
    /** libxposed modern API 101+ (`META-INF/xposed/java_init.list`, `ModernEntry`). */
    MODERN,

    /**
     * libxposed API 100 (`java_init.list`, `Api100Entry` in `:compat-api100`) on LSPosed 1.9.x and Vector <= 2.0;
     * hooks through the legacy XposedBridge API.
     */
    API100,

    /** Legacy `de.robv.android.xposed` API (`assets/xposed_init`). */
    LEGACY,
}

/** Immutable facts about the process, captured by the entry point before anything else runs. */
internal class Env(
    val entry: EntryKind,
    val packageName: String,
    val processName: String,
    val appInfo: ApplicationInfo,
    /** The host app's class loader. */
    val hostClassLoader: ClassLoader,
    /** Path of the module APK; empty when the framework did not provide it. */
    val modulePath: String,
    /** Framework name and version, for diagnostics. */
    val frameworkDescription: String,
    val isDebugBuild: Boolean = BuildConfig.DEBUG,
    val loaderVersion: String = LoaderIdentity.VERSION,
    val loaderVersionCode: Int = BuildConfig.VERSION_CODE,
) {
    /** The class loader that loaded the module. */
    val moduleClassLoader: ClassLoader = Env::class.java.classLoader ?: ClassLoader.getSystemClassLoader()

    /** The host app's data directory, e.g. `/data/user/0/com.whatsapp`. */
    val dataDir: File get() = File(checkNotNull(appInfo.dataDir) { "ApplicationInfo.dataDir is not set" })
}

/** [HostEnvironment] view of [Env], with the host version filled in once it is known. */
internal class EnvironmentView(private val env: Env, private val hostInfo: HostInfo) : HostEnvironment {
    override val packageName: String get() = env.packageName
    override val processName: String get() = env.processName
    override val applicationInfo: ApplicationInfo get() = env.appInfo
    override val classLoader: ClassLoader get() = env.hostClassLoader
    override val modulePath: String get() = env.modulePath
    override val loaderName: String get() = LoaderIdentity.NAME
    override val loaderVersion: String get() = env.loaderVersion
    override val isDebugBuild: Boolean get() = env.isDebugBuild
    override val frameworkDescription: String get() = env.frameworkDescription
    override val hostVersionName: String? get() = hostInfo.versionName
}
