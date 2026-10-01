package io.github.trickhook.shadowzap.whatsapp

import android.content.Context
import androidx.core.content.pm.PackageInfoCompat
import io.github.trickhook.shadowzap.core.Feature
import io.github.trickhook.shadowzap.core.FeatureContext

/**
 * Fills [io.github.trickhook.shadowzap.core.HostInfo] with WhatsApp's version name (parsed leniently) and version
 * code from the package manager, as soon as the host Application exists. WhatsApp has no readable BuildConfig, so the
 * package manager is the only source. A failure leaves the facts `null` and never fails the feature.
 */
internal object WhatsAppInfoFeature : Feature {
    override val id: String = "whatsapp-info"

    override fun install(ctx: FeatureContext) {
        ctx.onContext { context ->
            try {
                fill(ctx, context)
            } catch (t: Throwable) {
                ctx.log.w("Could not read WhatsApp's package info", t)
            }
        }
    }

    private fun fill(ctx: FeatureContext, context: Context) {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val name = info.versionName
        val parsed = HostVersions.parseLenient(name)
        if (name != null && parsed == null) ctx.log.w("WhatsApp version '$name' could not be parsed")
        // Publish the parsed form first: readers that see the name also see its version.
        ctx.hostInfo.version = parsed
        ctx.hostInfo.versionName = name
        ctx.hostInfo.versionCode = PackageInfoCompat.getLongVersionCode(info)
        ctx.log.i("WhatsApp ${name ?: "?"} (${ctx.hostInfo.versionCode})")
    }
}
