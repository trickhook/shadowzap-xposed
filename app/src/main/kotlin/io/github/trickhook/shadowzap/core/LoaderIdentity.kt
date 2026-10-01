package io.github.trickhook.shadowzap.core

import io.github.trickhook.shadowzap.BuildConfig

/** How the module identifies itself in logs and diagnostics. */
internal object LoaderIdentity {
    /** Display name of the module. */
    const val NAME: String = "ShadowzapXposed"

    /** Version name of this build. */
    val VERSION: String = BuildConfig.VERSION_NAME

    /** Logcat tag of every entry. */
    const val LOG_TAG: String = "Shadowzap"
}
