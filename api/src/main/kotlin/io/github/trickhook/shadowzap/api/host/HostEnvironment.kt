package io.github.trickhook.shadowzap.api.host

import android.content.pm.ApplicationInfo

/** Static facts about the process the loader runs in. */
public interface HostEnvironment {
    /** Package name of the host app (`com.whatsapp`). */
    public val packageName: String

    /** Name of the current process. The loader only runs in the host's main process. */
    public val processName: String

    /** [ApplicationInfo] of the host app. */
    public val applicationInfo: ApplicationInfo

    /** The host app's class loader. Use it to look up host classes such as `com.whatsapp.*`. */
    public val classLoader: ClassLoader

    /** Absolute path of the loader APK on disk. */
    public val modulePath: String

    /** Display name of the loader. */
    public val loaderName: String

    /** Version name of the loader. */
    public val loaderVersion: String

    /** `true` when the loader is a debug build. */
    public val isDebugBuild: Boolean

    /** Name and version of the Xposed framework, for diagnostics only. */
    public val frameworkDescription: String

    /**
     * Version name of the host app as reported by the host itself, or `null` until it is known
     * (it is read from the package manager once the host Application exists).
     */
    public val hostVersionName: String?
}
