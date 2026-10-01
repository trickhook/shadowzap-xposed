package io.github.trickhook.shadowzap.whatsapp

import io.github.trickhook.shadowzap.api.hooks.before
import io.github.trickhook.shadowzap.core.Env
import io.github.trickhook.shadowzap.core.Feature
import io.github.trickhook.shadowzap.core.FeatureContext
import io.github.trickhook.shadowzap.settings.SwitchStore
import io.github.trickhook.shadowzap.settings.Switches
import java.lang.reflect.Method

/**
 * Ghost mode: no blue ticks, keep delivery at one tick, hide typing/recording and never emit "online".
 *
 * The five lowest-level emitters in WA 2.26.39.11 are on `X.C1JH` ("ConnectionThreadRequestsImpl") plus
 * `X.C235812i` ("ReadReceiptUtils") and `X.C12E` ("ReadReceipts"):
 *   - `X.12i.A08(…) -> boolean`  — read-receipt central gate (make it return false)
 *   - `X.C12E.A0V(…)`           — delivery receipt emitter (skip entirely)
 *   - `X.C1JH.A0x(…)`           — presence/available (go offline)
 *   - `X.C1JH.A0y(…)`           — presence/unavailable (skip too, no state transitions)
 *   - `X.C1JH.A0w(…)`           — compose/composing (no "typing…" / "recording audio…")
 *   - `X.C1JH.A0v(…)`           — compose/paused (match A0w so we don't send a stop-typing)
 *
 * SERVER-OBSERVABLE: suppressing the delivery ack via C12E.A0V mimics a permanently flaky device; across many
 * stanzas this is the single highest-weight ban heuristic in this feature set. Default is OFF — the user opts in.
 */
internal object GhostModeFeature : Feature {
    override val id: String = "ghost-mode"

    override fun isEnabled(env: Env): Boolean = SwitchStore.isEnabled(env, Switches.GHOST_MODE)

    override fun install(ctx: FeatureContext) {
        ctx.onContext { context ->
            val loader = context.classLoader ?: ctx.env.hostClassLoader
            var hooked = 0

            // Read receipts: suppress every A08 overload on ReadReceiptUtils that returns boolean.
            WaReflect.loadClass(ctx, "X.12i", loader)?.let { cls ->
                val boolType = Boolean::class.javaPrimitiveType!!
                val methods = WaReflect.methodsMatching(cls) { it.name == "A08" && it.returnType == boolType }
                methods.forEach { m -> ctx.hooks.before(m) { call -> call.result = false } }
                if (methods.isNotEmpty()) {
                    ctx.log.i("ghost-mode: read receipts suppressed (ReadReceiptUtils.A08 x${methods.size})")
                    hooked += methods.size
                } else {
                    ctx.log.w("ghost-mode: ReadReceiptUtils.A08 not found")
                }
            } ?: ctx.log.w("ghost-mode: X.12i (ReadReceiptUtils) not found")

            // Delivery receipts.
            WaReflect.loadClass(ctx, "X.12E", loader)?.let { cls ->
                val methods = WaReflect.methodsMatching(cls) { it.name == "A0V" }
                methods.forEach { m -> skip(ctx, m) }
                if (methods.isNotEmpty()) {
                    ctx.log.i("ghost-mode: delivery receipts suppressed (C12E.A0V x${methods.size})")
                    hooked += methods.size
                } else {
                    ctx.log.w("ghost-mode: 12E.A0V not found")
                }
            } ?: ctx.log.w("ghost-mode: X.12E (ReadReceipts) not found")

            // Presence + composing emitters live together on ConnectionThreadRequestsImpl.
            WaReflect.loadClass(ctx, "X.1JH", loader)?.let { cls ->
                val emitters = listOf("A0x", "A0y", "A0w", "A0v")
                var count = 0
                for (name in emitters) {
                    val methods = WaReflect.methodsMatching(cls) { it.name == name }
                    methods.forEach { m -> skip(ctx, m) }
                    count += methods.size
                }
                if (count > 0) {
                    ctx.log.i("ghost-mode: presence + typing emitters suppressed on 1JH (x$count)")
                    hooked += count
                } else {
                    ctx.log.w("ghost-mode: 1JH presence/typing emitters not found")
                }
            } ?: ctx.log.w("ghost-mode: X.1JH (ConnectionThreadRequestsImpl) not found")

            ctx.log.i("ghost-mode: $hooked hook(s) installed")
        }
    }

    /** Skip the original call. For non-void methods we still set null; most emitters here return void. */
    private fun skip(ctx: FeatureContext, m: Method) {
        ctx.hooks.before(m) { call -> call.result = null }
    }
}
