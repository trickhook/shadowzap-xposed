package io.github.trickhook.shadowzap.whatsapp

import io.github.trickhook.shadowzap.api.hooks.before
import io.github.trickhook.shadowzap.core.Env
import io.github.trickhook.shadowzap.core.Feature
import io.github.trickhook.shadowzap.core.FeatureContext
import io.github.trickhook.shadowzap.settings.SwitchStore
import io.github.trickhook.shadowzap.settings.Switches

/**
 * Removes the "3 pinned chats" cap on WhatsApp's chat list so the user can pin as many as they want.
 *
 * Hooks `X.1Iz.A01(X.1Iz, java.util.Set) -> boolean` — the limit comparator that returns `true` when the proposed
 * set would overflow the limit. We force the return value to `false`, so the caller allows the pin.
 *
 * Risk note: companion-device sync (WA Web/Desktop) may still enforce the server-side cap and drop extra entries
 * silently — this is a client-local override only.
 */
internal object UnlimitedPinsFeature : Feature {
    override val id: String = "unlimited-pins"

    override fun isEnabled(env: Env): Boolean = SwitchStore.isEnabled(env, Switches.UNLIMITED_PINS)

    override fun install(ctx: FeatureContext) {
        ctx.onContext { context ->
            val loader = context.classLoader ?: ctx.env.hostClassLoader
            val cls = WaReflect.loadClass(ctx, "X.1Iz", loader)
                ?: WaReflect.loadClass(ctx, "X.C1Iz", loader)
                ?: run {
                    ctx.log.w("unlimited-pins: X.1Iz pin-limit class not found; feature inert.")
                    return@onContext
                }
            val boolType = Boolean::class.javaPrimitiveType!!
            val setType = java.util.Set::class.java
            val a01 = WaReflect.methodsMatching(cls) { m ->
                m.name == "A01" && m.parameterTypes.size == 2 &&
                    m.parameterTypes[0] == cls && m.parameterTypes[1] == setType &&
                    m.returnType == boolType
            }.firstOrNull() ?: run {
                ctx.log.w("unlimited-pins: ${cls.name}.A01(_, Set) → boolean not found; signature changed.")
                return@onContext
            }
            ctx.hooks.before(a01) { call -> call.result = false }
            ctx.log.i("unlimited-pins: pin-limit comparator ${cls.name}.A01 forced to false")
        }
    }
}
