package io.github.trickhook.shadowzap.core

import android.content.pm.ApplicationInfo

/**
 * Decides whether an entry point may boot the module in the process it was called for. Every entry asks before it
 * claims the boot guard, so a refused process never blocks a valid one.
 *
 * Needed because unscoped legacy frameworks (rovo89 Xposed, EdXposed without whitelist mode) call modules for every
 * app, and users may add other apps to the scope: the module must only start in WhatsApp's main process (WhatsApp
 * also runs secondary `:` processes that must stay untouched).
 */
internal object HostGate {
    const val WHATSAPP_PACKAGE: String = "com.whatsapp"

    /**
     * WhatsApp's Application class: named in its manifest, so it is never obfuscated. Lets a repackaged WhatsApp
     * build with another package name boot too.
     */
    const val WHATSAPP_MARKER_CLASS: String = "com.whatsapp.AppShell"

    /** Package name the old bridge reports for system_server. */
    private const val SYSTEM_SERVER_PACKAGE = "android"

    sealed interface Decision {
        data object Boot : Decision

        data class Skip(val reason: String) : Decision
    }

    fun decide(
        packageName: String?,
        processName: String?,
        appInfo: ApplicationInfo?,
        hostClassLoader: ClassLoader?,
        isFirstPackage: Boolean,
    ): Decision {
        if (packageName.isNullOrEmpty()) return Decision.Skip("no package name")
        if (packageName == SYSTEM_SERVER_PACKAGE) return Decision.Skip("system_server")
        if (!isFirstPackage) return Decision.Skip("$packageName is not the process' own package")
        if (processName != packageName) return Decision.Skip("secondary process ${processName ?: "(unknown)"}")
        if (appInfo == null || appInfo.dataDir.isNullOrEmpty()) return Decision.Skip("no ApplicationInfo")
        if (hostClassLoader == null) return Decision.Skip("no class loader")
        if (packageName == WHATSAPP_PACKAGE || hasClass(hostClassLoader, WHATSAPP_MARKER_CLASS)) return Decision.Boot
        return Decision.Skip("$packageName is not WhatsApp")
    }

    private fun hasClass(loader: ClassLoader, name: String): Boolean = try {
        loader.loadClass(name)
        true
    } catch (_: ClassNotFoundException) {
        false
    } catch (_: LinkageError) {
        false
    }
}
