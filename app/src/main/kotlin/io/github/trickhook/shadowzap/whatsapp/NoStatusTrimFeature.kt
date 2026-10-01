package io.github.trickhook.shadowzap.whatsapp

import android.content.Intent
import io.github.trickhook.shadowzap.api.hooks.before
import io.github.trickhook.shadowzap.api.hooks.requireMethod
import io.github.trickhook.shadowzap.core.Env
import io.github.trickhook.shadowzap.core.Feature
import io.github.trickhook.shadowzap.core.FeatureContext
import io.github.trickhook.shadowzap.settings.SwitchStore
import io.github.trickhook.shadowzap.settings.Switches

/**
 * Removes WhatsApp's status-video length cap and lifts it to [MAX_STATUS_VIDEO_SECONDS] (1 hour) instead.
 *
 * Two caps stack on the status composer. Both must fall:
 *
 * 1. The hard-coded 30 s branch. The composer picks it when the Intent extra `mec_statusVideoDurationCap` is true:
 *    `iA00 = 30` in VideoComposerViewModel/MediaComposerActivity on WA 2.26.39.11, wrapped by
 *    `AbstractC44341xF.A05(i) = i * 1000` into milliseconds. We force [EXTRA_KEY] to false in every
 *    `Intent.getBooleanExtra` so this branch never runs.
 *
 * 2. The ABProps-controlled branch. With the extra disabled, the composer falls into
 *    `C7K2.A00(ABProps, fileSize)`, which returns:
 *      - `ABProps.A0a(6728)` seconds when the file is bigger than a threshold (currently 90 s on this account);
 *      - 30 s otherwise.
 *    We hook `X.C7K2.A00(X.C015107i, long)` to return [MAX_STATUS_VIDEO_SECONDS] directly, so the trimmer slider
 *    extends to our limit regardless of what the server sent down.
 *
 * The second hook uses the R8-obfuscated class name `X.C7K2`, which can be renamed between builds — if the class
 * is not found we log a warning and the first hook still removes the 30 s floor.
 */
internal object NoStatusTrimFeature : Feature {
    override val id: String = "no-status-trim"

    private const val EXTRA_KEY = "mec_statusVideoDurationCap"
    // Real DEX names, not the JADX-mangled Java-safe names (JADX prefixes digit-starting classes with `C`).
    private const val DURATION_HELPER_CLASS = "X.7K2"
    private const val COMPOSER_VIEWMODEL_CLASS = "com.whatsapp.mediacomposer.ui.app.viewmodel.VideoComposerViewModel"

    /** The replacement cap in seconds (1 hour). */
    private const val MAX_STATUS_VIDEO_SECONDS: Int = 3600

    /** The replacement cap in milliseconds. */
    private const val MAX_STATUS_VIDEO_MS: Long = MAX_STATUS_VIDEO_SECONDS.toLong() * 1000L

    override fun isEnabled(env: Env): Boolean = SwitchStore.isEnabled(env, Switches.NO_STATUS_TRIM)

    override fun install(ctx: FeatureContext) {
        val getBooleanExtra = Intent::class.java.requireMethod(
            "getBooleanExtra", String::class.java, Boolean::class.javaPrimitiveType!!,
        )
        ctx.hooks.before(getBooleanExtra) { call ->
            val key = call.args.getOrNull(0) as? String ?: return@before
            if (key == EXTRA_KEY) call.result = false
        }
        ctx.log.i("Status video duration cap disabled (Intent extra '$EXTRA_KEY' forced to false)")

        hookDurationHelper(ctx)
        hookComposerCeiling(ctx)
        hookTrimmerMaxDuration(ctx)
    }

    /**
     * Patches `X.C8SF.A07(): long` — the "VideoMaxDurationEnforcer" getter that the trim-slider and the
     * pre-send `CWf` validator both read. Even when [hookDurationHelper] lifts C7K2 to 3600 s, the earlier
     * `X.C179787qC.A00(...)` computes a size-derived `jA02 = sizeCapMB * 1 MiB * videoDurMs / fileBytes`
     * (typically ~100 s for a ~16 MB status cap) and stores it into C8SF.A0h(j). The downstream `min(sizeDerived,
     * hookedCap)` keeps 100 s. Hooking C8SF.A07 to clamp UP to [MAX_STATUS_VIDEO_MS] widens the trimmer AND the
     * validator in one shot, since both call through this getter.
     */
    private fun hookTrimmerMaxDuration(ctx: FeatureContext) {
        ctx.onContext { context ->
            try {
                val loader = context.classLoader ?: ctx.env.hostClassLoader
                val cls = runCatching { Class.forName("X.C8SF", false, loader) }.getOrNull()
                    ?: runCatching { Class.forName("X.8SF", false, loader) }.getOrNull()
                if (cls == null) {
                    ctx.log.w("Could not find X.C8SF (VideoMaxDurationEnforcer); 1:40 secondary cap unpatched.")
                    return@onContext
                }
                val longType = Long::class.javaPrimitiveType!!
                val getters = cls.declaredMethods.filter { m ->
                    m.name == "A07" && m.parameterTypes.isEmpty() && m.returnType == longType
                }
                if (getters.isEmpty()) {
                    ctx.log.w("${cls.name}.A07(): long not found; signature changed.")
                    return@onContext
                }
                getters.forEach { g ->
                    ctx.hooks.before(g) { call ->
                        // Clamp UP only — never shrink an already-raised value.
                        call.result = MAX_STATUS_VIDEO_MS
                    }
                }
                ctx.log.i(
                    "Status trimmer max duration pinned to $MAX_STATUS_VIDEO_MS ms via ${cls.name}.A07 (${getters.size} overload(s))",
                )
            } catch (t: Throwable) {
                ctx.log.w("Could not patch C8SF.A07: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    /**
     * Patches `VideoComposerViewModel.A00(C52022NCf, VideoComposerViewModel, long, long) -> long`, the method that
     * decides the trimmer's absolute ceiling in milliseconds. The default implementation returns
     * `min(fileSizeDerivedMs, C7K2.A00(...) * 1000)`; even when we lift the second term to 1 hour via
     * [hookDurationHelper], the first (bitrate/filesize derived) still wins for small files — users saw the slider
     * stop at ~1:42. We identify A00 by (name, 4 params, last two long, return long) and replace the result with
     * [MAX_STATUS_VIDEO_MS].
     */
    private fun hookComposerCeiling(ctx: FeatureContext) {
        ctx.onContext { context ->
            try {
                val loader = context.classLoader ?: ctx.env.hostClassLoader
                val vm = Class.forName(COMPOSER_VIEWMODEL_CLASS, false, loader)
                val longType = Long::class.javaPrimitiveType!!
                val candidates = vm.declaredMethods.filter { m ->
                    m.name == "A00" &&
                        m.returnType == longType &&
                        m.parameterTypes.size == 4 &&
                        m.parameterTypes[2] == longType &&
                        m.parameterTypes[3] == longType
                }
                when (candidates.size) {
                    0 -> ctx.log.w("$COMPOSER_VIEWMODEL_CLASS has no A00(_, _, long, long) → long; signature changed.")
                    else -> {
                        candidates.forEach { a00 ->
                            ctx.hooks.before(a00) { call -> call.result = MAX_STATUS_VIDEO_MS }
                        }
                        ctx.log.i(
                            "Status video trimmer ceiling lifted to $MAX_STATUS_VIDEO_MS ms via VideoComposerViewModel.A00 (${candidates.size} overload(s))",
                        )
                    }
                }
            } catch (t: Throwable) {
                ctx.log.w(
                    "Could not lift the composer ceiling via $COMPOSER_VIEWMODEL_CLASS.A00: ${t.javaClass.simpleName}: ${t.message}",
                )
            }
        }
    }

    private fun hookDurationHelper(ctx: FeatureContext) {
        // Deferred to onContext: at MODERN entry time the host's class loader may not have WA's own DEX files
        // ready; waiting for the Application context guarantees X.7K2 (classes5.dex) is loadable.
        ctx.onContext { context ->
            try {
                val loader = context.classLoader ?: ctx.env.hostClassLoader
                val helper = Class.forName(DURATION_HELPER_CLASS, false, loader)
                // Resolving the ABProps parameter by name is brittle — pick A00 by (name, param count, shape)
                // instead. On WA 2.26.39.11 the only A00(Object, long) returning int is the duration helper.
                val candidates = helper.declaredMethods.filter { m ->
                    m.name == "A00" &&
                        m.parameterTypes.size == 2 &&
                        m.parameterTypes[1] == Long::class.javaPrimitiveType &&
                        (m.returnType == Int::class.javaPrimitiveType || m.returnType == Integer::class.java)
                }
                when (candidates.size) {
                    0 -> ctx.log.w("$DURATION_HELPER_CLASS has no A00(Object, long) → int; signature changed.")
                    else -> {
                        candidates.forEach { a00 ->
                            ctx.hooks.before(a00) { call -> call.result = MAX_STATUS_VIDEO_SECONDS }
                        }
                        ctx.log.i(
                            "Status video length lifted to $MAX_STATUS_VIDEO_SECONDS s via $DURATION_HELPER_CLASS.A00 (${candidates.size} overload(s))",
                        )
                    }
                }
            } catch (t: Throwable) {
                ctx.log.w(
                    "Could not lift the status video length cap via $DURATION_HELPER_CLASS.A00 (likely renamed in this WA build): ${t.javaClass.simpleName}: ${t.message}",
                )
            }
        }
    }
}
