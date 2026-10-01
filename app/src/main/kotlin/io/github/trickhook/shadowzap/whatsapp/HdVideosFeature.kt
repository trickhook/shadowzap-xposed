package io.github.trickhook.shadowzap.whatsapp

import io.github.trickhook.shadowzap.api.hooks.before
import io.github.trickhook.shadowzap.core.Env
import io.github.trickhook.shadowzap.core.Feature
import io.github.trickhook.shadowzap.core.FeatureContext
import io.github.trickhook.shadowzap.settings.SwitchStore
import io.github.trickhook.shadowzap.settings.Switches
import java.lang.reflect.Constructor

/**
 * Lifts WhatsApp's send-side video transcode caps so outgoing videos keep their native resolution up to FullHD
 * (1920 long edge) and up to 120 fps, instead of being downsized to 720p/30 fps.
 *
 * **How WA caps a video send on 2.26.39.11:**
 * 1. The media sender resolves a quality tier via `X.C178587o7.A03(...)`/`.A02(int,long)` which produces a
 *    `X.C52022NCf` ("ProcessVideoQuality") parcelable with fields `A03 = videoMaxEdge` and `A00 = frameRate`.
 * 2. A downstream clamp (`if (cap <= source) source = cap`) downsizes the source to that cap.
 * 3. Then `X.C50469Mc7.A00(…)` copies those values into `X.C51602MvV` ("MediaTranscodeParams") which the
 *    videolite encoder in `X.ME0.A00(...)` turns into a `MediaFormat`.
 *
 * **Our move (surgical, graceful degradation):** hook the `X.C52022NCf` constructor and, before it stores the ints,
 * raise `videoMaxEdge` toward 1920 and `frameRate` toward 120. Because the downstream clamp is `cap <= source` the
 * raised cap simply means "no downsize if source is already smaller" — the sender never allocates a surface larger
 * than the source, so the encoder cannot crash on dimension mismatch.
 *
 * We also pin `videoMaxBitrate` to 15 Mbps so the raised resolution does not collapse to blocky, and hook the
 * `X.C51019MlK` ctor to lift its hard-coded 10 Mbps bitrate ceiling (`Math.min(..., 10000000)` at line 95).
 *
 * **Caveat the user accepted:** forcing 120 fps on a 30 fps source makes the muxer emit duplicate frames at the
 * higher clock — viewer sees stutter but the sender does NOT crash and the server does NOT reject the muxed MP4.
 */
internal object HdVideosFeature : Feature {
    override val id: String = "hd-videos"

    // Safe FullHD baseline. The field tested that forcing fps=120 and bitrate=25 Mbps makes WA's own
    // `C51019MlK` baseline-bitrate check and MediaCodec AVC profile reject the clip ("Can't send this video.
    // Choose a different video and try again."). We now stay inside what AVC/baseline accepts on every modern
    // ARM device: 1080p long edge, 60 fps, 12 Mbps. Bitrate ceiling is lifted only to 18 Mbps (vs. WA's 10) so
    // 1080p/60 does not collapse to blocky.
    private const val TARGET_EDGE: Int = 1920        // long edge, Full HD
    private const val TARGET_FPS: Int = 60           // 60 is universally supported on AVC; 120 is HEVC-only on flagships and WA rejects it
    private const val TARGET_BITRATE: Int = 12_000_000
    private const val BITRATE_CAP_FLOOR: Int = 18_000_000  // lift C51019MlK's internal 10 Mbps cap, but not past AVC baseline tolerance

    override fun isEnabled(env: Env): Boolean = SwitchStore.isEnabled(env, Switches.HD_VIDEOS)

    override fun install(ctx: FeatureContext) {
        ctx.onContext { context ->
            val loader = context.classLoader ?: ctx.env.hostClassLoader
            hookProcessVideoQuality(ctx, loader)
            hookBaselineBitrateCap(ctx, loader)
        }
    }

    /**
     * Hook `X.C52022NCf.<init>` so every ProcessVideoQuality instance ships with raised caps. The ctor has 16 args;
     * positions 10–13 are four ints in order (videoLimitMb, videoMaxEdge, videoMaxBitrate, frameRate) and the last
     * two are booleans. We shape-match on these primitives so a letter rename in a later WA build still resolves it.
     */
    private fun hookProcessVideoQuality(ctx: FeatureContext, loader: ClassLoader) {
        val cls = WaReflect.loadClass(ctx, "X.NCf", loader) ?: run {
            ctx.log.w("hd-videos: X.NCf (ProcessVideoQuality) not found; feature inert.")
            return
        }
        val intType = Int::class.javaPrimitiveType!!
        val boolType = Boolean::class.javaPrimitiveType!!
        val ctor: Constructor<*>? = cls.declaredConstructors.firstOrNull { c ->
            c.parameterCount == 16 &&
                c.parameterTypes[10] == intType && c.parameterTypes[11] == intType &&
                c.parameterTypes[12] == intType && c.parameterTypes[13] == intType &&
                c.parameterTypes[14] == boolType && c.parameterTypes[15] == boolType
        }
        if (ctor == null) {
            ctx.log.w("hd-videos: ProcessVideoQuality 16-arg ctor not found; signature changed.")
            return
        }

        ctx.hooks.before(ctor) { call ->
            try {
                val edge = call.args[11] as? Int
                val bitrate = call.args[12] as? Int
                val fps = call.args[13] as? Int
                // Pass-through for already-higher values; never downgrade. 'edge' is a cap, not a target —
                // raising it just prevents downsizing; it never upscales because WA's downstream clamp is
                // `if (cap <= source) source = cap`.
                if (edge != null && edge in 1 until TARGET_EDGE) call.args[11] = TARGET_EDGE
                if (bitrate != null && bitrate in 1 until TARGET_BITRATE) call.args[12] = TARGET_BITRATE
                // fps is risky: forcing 60 on a 30 fps source makes the muxer duplicate frames (viewer sees
                // stutter, no crash); forcing beyond 60 rejects on AVC. We clamp to 60.
                if (fps != null && fps in 1 until TARGET_FPS) call.args[13] = TARGET_FPS
            } catch (_: Throwable) {
            }
        }
        ctx.log.i(
            "hd-videos: ProcessVideoQuality ctor patched " +
                "(maxEdge→${TARGET_EDGE}, fps→${TARGET_FPS}, bitrate→${TARGET_BITRATE / 1_000_000} Mbps)",
        )
    }

    /**
     * `X.C51019MlK.<init>(X.C50311MYj)` applies `Math.min(x, 10_000_000)` to the baseline bitrate (line 95). We
     * cannot patch the arithmetic from here, but we can inspect the resulting instance's int field holding that
     * value and raise it — the sender then uses the raised value as the encoder's `KEY_BIT_RATE`.
     *
     * We shape-match: find every int field on the fresh instance whose value equals 10_000_000 (the min-cap signal)
     * and raise it to [BITRATE_CAP_FLOOR]. Other int fields are left alone.
     */
    private fun hookBaselineBitrateCap(ctx: FeatureContext, loader: ClassLoader) {
        val cls = WaReflect.loadClass(ctx, "X.MlK", loader) ?: run {
            ctx.log.w("hd-videos: X.MlK not found; bitrate ceiling not lifted.")
            return
        }
        val myj = WaReflect.loadClass(ctx, "X.MYj", loader) ?: run {
            ctx.log.w("hd-videos: X.MYj not found; cannot bind MlK 1-arg ctor.")
            return
        }
        val ctor: Constructor<*> = cls.declaredConstructors.firstOrNull { c ->
            c.parameterCount == 1 && c.parameterTypes[0] == myj
        } ?: run {
            ctx.log.w("hd-videos: C51019MlK(C50311MYj) ctor not found; signature changed.")
            return
        }

        ctx.hooks.hook(ctor, callback = object : io.github.trickhook.shadowzap.api.hooks.HookCallback {
            override fun after(call: io.github.trickhook.shadowzap.api.hooks.HookCall) {
                try {
                    val inst = call.thisObject ?: return
                    var raised = 0
                    for (field in inst.javaClass.declaredFields) {
                        if (field.type != Int::class.javaPrimitiveType) continue
                        field.isAccessible = true
                        val value = field.getInt(inst)
                        if (value == 10_000_000) {
                            field.setInt(inst, BITRATE_CAP_FLOOR)
                            raised++
                        }
                    }
                    if (raised > 0) {
                        ctx.log.i("hd-videos: C51019MlK bitrate cap raised on $raised field(s) to ${BITRATE_CAP_FLOOR / 1_000_000} Mbps")
                    }
                } catch (_: Throwable) {
                }
            }
        })
    }
}
