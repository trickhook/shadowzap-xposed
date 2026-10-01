package io.github.trickhook.shadowzap.whatsapp

import io.github.trickhook.shadowzap.api.hooks.before
import io.github.trickhook.shadowzap.core.Env
import io.github.trickhook.shadowzap.core.Feature
import io.github.trickhook.shadowzap.core.FeatureContext
import io.github.trickhook.shadowzap.settings.SwitchStore
import io.github.trickhook.shadowzap.settings.Switches

/**
 * Keeps the original body of edited messages.
 *
 * Every incoming edit on WA 2.26.39.11 funnels through `X.C18G.A00(C18G, C1AE, C41311sD, boolean)` which rewrites
 * the stored message body with the new text and inserts an edit-info row. We skip this method entirely for incoming
 * edits (i.e. not our own outgoing edit) so the chat row keeps displaying the version the sender originally sent.
 *
 * Trade-off: because the DB UPDATE never happens, the "edited" marker never appears either — the chat shows the
 * original text with no indication that the sender tried to change it. A sidecar-DB implementation that stores
 * every revision and renders inline history is out of scope for v1 (see `shadowzapEditStore` in the recon plan).
 */
internal object SeeEditedFeature : Feature {
    override val id: String = "see-edited"

    override fun isEnabled(env: Env): Boolean = SwitchStore.isEnabled(env, Switches.SEE_EDITED)

    override fun install(ctx: FeatureContext) {
        ctx.onContext { context ->
            val loader = context.classLoader ?: ctx.env.hostClassLoader
            val c18g = WaReflect.loadClass(ctx, "X.18G", loader) ?: run {
                ctx.log.w("see-edited: X.18G not found; feature inert.")
                return@onContext
            }
            val c1ae = WaReflect.loadClass(ctx, "X.1AE", loader) ?: return@onContext
            val c1sd = WaReflect.loadClass(ctx, "X.1sD", loader) ?: run {
                ctx.log.w("see-edited: X.1sD not found; cannot resolve A00 signature.")
                return@onContext
            }
            val boolType = Boolean::class.javaPrimitiveType!!
            val candidates = WaReflect.methodsMatching(c18g) { m ->
                m.name == "A00" && m.parameterTypes.size == 4 &&
                    m.parameterTypes[0] == c18g &&
                    m.parameterTypes[1] == c1ae &&
                    m.parameterTypes[2] == c1sd &&
                    m.parameterTypes[3] == boolType
            }
            if (candidates.isEmpty()) {
                ctx.log.w("see-edited: C18G.A00(C18G, C1AE, 1sD, boolean) not found; signature changed.")
                return@onContext
            }
            candidates.forEach { m ->
                ctx.hooks.before(m) { call ->
                    try {
                        val arg = call.args[1] ?: return@before
                        val a0j = arg.javaClass.getField("A0j").get(arg) ?: return@before
                        val fromMe = a0j.javaClass.getField("A02").getBoolean(a0j)
                        if (fromMe) return@before
                        // Not from me: skip the edit — the stored body (= the original) stays visible to the user.
                        call.result = null
                    } catch (t: Throwable) {
                        ctx.log.w("see-edited: hook threw ${t.javaClass.simpleName}: ${t.message}")
                    }
                }
            }
            ctx.log.i("see-edited: incoming edits are ignored (${candidates.size} overload(s)); original stays visible")
        }
    }
}
