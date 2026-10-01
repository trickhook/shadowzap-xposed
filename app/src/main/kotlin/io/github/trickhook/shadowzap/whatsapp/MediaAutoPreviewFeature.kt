package io.github.trickhook.shadowzap.whatsapp

import io.github.trickhook.shadowzap.api.hooks.before
import io.github.trickhook.shadowzap.core.Env
import io.github.trickhook.shadowzap.core.Feature
import io.github.trickhook.shadowzap.core.FeatureContext
import io.github.trickhook.shadowzap.settings.SwitchStore
import io.github.trickhook.shadowzap.settings.Switches

/**
 * Forces chat photos and videos to download automatically so they appear inline without the user tapping the
 * download arrow.
 *
 * WA 2.26.39.11 funnels autodownload decisions through `X.C18X` ("MediaAutoDownloadUtils") — static methods that
 * read the ABProps killswitch (`A03`), the WAProxy guard (`A04`) and compute per-message gates (`A05`, `A08`). We
 * force every boolean the class produces to the "download it" value. The decision then feeds into
 * `X.C181357sq.A04` ("MainMessageObserver.shouldAutoDownloadMedia") which we also pin to `true` as a belt and
 * braces hook.
 *
 * No ban exposure: this changes WHEN WhatsApp fetches media, not whether — Meta's servers cannot distinguish an
 * autofetched media chunk from a manually-tapped one.
 */
internal object MediaAutoPreviewFeature : Feature {
    override val id: String = "media-autopreview"

    override fun isEnabled(env: Env): Boolean = SwitchStore.isEnabled(env, Switches.MEDIA_AUTOPREVIEW)

    override fun install(ctx: FeatureContext) {
        ctx.onContext { context ->
            val loader = context.classLoader ?: ctx.env.hostClassLoader
            var touched = 0

            WaReflect.loadClass(ctx, "X.18X", loader)?.let { c18x ->
                val boolType = Boolean::class.javaPrimitiveType!!
                // All boolean-returning methods become "allow download".
                val boolMethods = WaReflect.methodsMatching(c18x) { it.returnType == boolType }
                boolMethods.forEach { m ->
                    ctx.hooks.before(m) { call -> call.result = true }
                    touched++
                }
                ctx.log.i("media-autopreview: forced ${boolMethods.size} C18X boolean gates to true")
            } ?: ctx.log.w("media-autopreview: X.18X not found; MainMessageObserver hook still tries.")

            WaReflect.loadClass(ctx, "X.7sq", loader)?.let { cls ->
                val boolType = Boolean::class.javaPrimitiveType!!
                val overloads = WaReflect.methodsMatching(cls) { m ->
                    m.name == "A04" && m.returnType == boolType
                }
                overloads.forEach { m ->
                    ctx.hooks.before(m) { call -> call.result = true }
                    touched++
                }
                ctx.log.i("media-autopreview: C181357sq.A04 overloads pinned to true (${overloads.size})")
            }

            ctx.log.i("media-autopreview: $touched hook(s) installed total")
        }
    }
}
