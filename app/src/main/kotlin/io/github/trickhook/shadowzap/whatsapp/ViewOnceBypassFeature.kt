package io.github.trickhook.shadowzap.whatsapp

import io.github.trickhook.shadowzap.api.hooks.before
import io.github.trickhook.shadowzap.core.Env
import io.github.trickhook.shadowzap.core.Feature
import io.github.trickhook.shadowzap.core.FeatureContext
import io.github.trickhook.shadowzap.settings.SwitchStore
import io.github.trickhook.shadowzap.settings.Switches

/**
 * Strips the view-once flag so photos, videos and voice notes become normal, reopenable messages that can be saved
 * and forwarded.
 *
 * On WA 2.26.39.11 the view-once state lives on `InterfaceC198778hE` (DEX `X.8hE`), implemented by the four FMessage
 * subtypes that can carry view-once content: `X.Dx8` (image), `X.DxL` (video), `X.1q4` (PTV / video note) and
 * `X.6vG` (voice note). The interface exposes a getter and a setter; on this build they're `BBP() -> int` and
 * `CaN(int) -> void`. State values: `0` = normal, `1` = unopened view-once, `2` = opened view-once. Forcing BBP to
 * `0` makes the WA UI route the message through the regular media viewer, and forcing the first arg of CaN to `0`
 * stops any later code (e.g. opening the viewer) from flipping the state back.
 *
 * Method letters BBP / CaN are R8-mangled and will change across WA builds. We resolve them by name on this build
 * and shape-match (int getter + int setter) as a defensive fallback.
 */
internal object ViewOnceBypassFeature : Feature {
    override val id: String = "view-once-bypass"

    private val IMPL_CLASSES = listOf("X.Dx8", "X.DxL", "X.1q4", "X.6vG")

    override fun isEnabled(env: Env): Boolean = SwitchStore.isEnabled(env, Switches.VIEW_ONCE_BYPASS)

    override fun install(ctx: FeatureContext) {
        ctx.onContext { context ->
            val loader = context.classLoader ?: ctx.env.hostClassLoader
            val intType = Int::class.javaPrimitiveType!!
            var touched = 0

            for (name in IMPL_CLASSES) {
                val cls = WaReflect.loadClass(ctx, name, loader) ?: run {
                    ctx.log.w("view-once-bypass: $name not found; skipped")
                    continue
                }

                val getter = pickGetter(cls, intType)
                val setter = pickSetter(cls, intType)
                if (getter == null && setter == null) {
                    ctx.log.w("view-once-bypass: $name has no BBP()/CaN(int) and no shape match; skipped")
                    continue
                }
                if (getter != null) {
                    ctx.hooks.before(getter) { call -> call.result = 0 }
                }
                if (setter != null) {
                    ctx.hooks.before(setter) { call -> call.args[0] = 0 }
                }
                touched++
            }
            ctx.log.i("view-once-bypass: forced BBP/CaN to 0 on $touched/${IMPL_CLASSES.size} FMessage subtype(s)")
        }
    }

    /** Picks the `int BBP()` getter — exact name first, then any no-arg int getter. */
    private fun pickGetter(cls: Class<*>, intType: Class<*>) =
        WaReflect.method(cls, "BBP")
            ?: WaReflect.methodsMatching(cls) { m ->
                m.parameterCount == 0 && m.returnType == intType
            }.firstOrNull()

    /** Picks the `void CaN(int)` setter — exact name first, then any single-int setter returning void. */
    private fun pickSetter(cls: Class<*>, intType: Class<*>) =
        WaReflect.method(cls, "CaN", intType)
            ?: WaReflect.methodsMatching(cls) { m ->
                m.parameterCount == 1 && m.parameterTypes[0] == intType && m.returnType == Void.TYPE
            }.firstOrNull()
}
