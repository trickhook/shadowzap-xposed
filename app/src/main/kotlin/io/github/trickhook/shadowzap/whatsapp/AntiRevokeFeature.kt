package io.github.trickhook.shadowzap.whatsapp

import io.github.trickhook.shadowzap.api.hooks.before
import io.github.trickhook.shadowzap.core.Env
import io.github.trickhook.shadowzap.core.Feature
import io.github.trickhook.shadowzap.core.FeatureContext
import io.github.trickhook.shadowzap.settings.SwitchStore
import io.github.trickhook.shadowzap.settings.Switches

/**
 * Keeps messages the sender tried to delete for everyone.
 *
 * WA 2.26.39.11 routes every incoming "revoke for everyone" through `X.C18H.A00(C18H, C1AE, int, boolean) → C168467Rh`
 * which clones the original FMessage via `FMessageRevokedFactory.cloneIncomingRevokeMessage`, replaces the row in the
 * message store, and broadcasts a UI "replaced-by-revoke" event. If we short-circuit that DAO for incoming revokes we
 * stop the original message from being overwritten in the DB, so the chat row keeps rendering with the original body.
 *
 * Short-circuit value: return a `C168467Rh(X.8FO.A00, false)` — `X.8FO` is a singleton `InterfaceC196108cv` meaning
 * "no result" / "None"; `false` is the "did we actually ack" flag.
 *
 * Secondary (defensive) hook: flip `AbstractC33701en.A1A(C1AE) → boolean` (the canonical `isRevoke` predicate) to
 * `false` when the message still has a non-null body — this neutralizes any UI that already pulled a type-15/64
 * message before the DAO hook landed.
 *
 * We intentionally do nothing when `c1ae.A0j.A02` is true (i.e. the user themselves tapped "delete for everyone");
 * your own self-revokes still work locally.
 */
internal object AntiRevokeFeature : Feature {
    override val id: String = "anti-revoke"

    override fun isEnabled(env: Env): Boolean = SwitchStore.isEnabled(env, Switches.ANTI_REVOKE)

    override fun install(ctx: FeatureContext) {
        ctx.onContext { context ->
            val loader = context.classLoader ?: ctx.env.hostClassLoader
            hookRevokeDao(ctx, loader)
            hookIsRevokePredicate(ctx, loader)
        }
    }

    /** The main DAO: short-circuit incoming revokes so the DB keeps the original row. */
    private fun hookRevokeDao(ctx: FeatureContext, loader: ClassLoader) {
        val c18h = WaReflect.loadClass(ctx, "X.18H", loader) ?: run {
            ctx.log.w("anti-revoke: X.18H not found; DAO short-circuit disabled.")
            return
        }
        val c1ae = WaReflect.loadClass(ctx, "X.1AE", loader) ?: run {
            ctx.log.w("anti-revoke: X.1AE not found; DAO short-circuit disabled.")
            return
        }
        val longType = Int::class.javaPrimitiveType!!
        val boolType = Boolean::class.javaPrimitiveType!!
        val a00 = WaReflect.methodsMatching(c18h) { m ->
            m.name == "A00" && m.parameterTypes.size == 4 &&
                m.parameterTypes[0] == c18h && m.parameterTypes[1] == c1ae &&
                m.parameterTypes[2] == longType && m.parameterTypes[3] == boolType
        }.firstOrNull() ?: run {
            ctx.log.w("anti-revoke: C18H.A00(C18H, C1AE, int, boolean) not found; signature changed.")
            return
        }

        val c7rh = WaReflect.loadClass(ctx, "X.7Rh", loader)
        val c8foSingleton: Any? = WaReflect.loadClass(ctx, "X.8FO", loader)?.let { WaReflect.staticField(it, "A00") }
        val skipResult: Any? = if (c7rh != null && c8foSingleton != null) {
            try {
                val ctor = c7rh.declaredConstructors.firstOrNull { it.parameterCount == 2 }
                ctor?.isAccessible = true
                ctor?.newInstance(c8foSingleton, false)
            } catch (t: Throwable) {
                ctx.log.w("anti-revoke: Could not build the empty C168467Rh: ${t.javaClass.simpleName}: ${t.message}")
                null
            }
        } else {
            null
        }

        ctx.hooks.before(a00) { call ->
            try {
                val arg = call.args[1] ?: return@before
                val a0j = arg.javaClass.getField("A0j").get(arg) ?: return@before
                val fromMe = a0j.javaClass.getField("A02").getBoolean(a0j)
                if (fromMe) return@before
                // Not from me: suppress the DAO. Return a benign C168467Rh when we have one, otherwise null — the
                // caller tolerates null (every callsite immediately reads .A00 which stays null) because we only
                // save a few DAO sites that then do fire-and-forget actions on the result.
                call.result = skipResult
            } catch (t: Throwable) {
                ctx.log.w("anti-revoke: DAO hook threw ${t.javaClass.simpleName}: ${t.message}")
            }
        }
        ctx.log.i(
            if (skipResult != null) "anti-revoke: DAO short-circuit installed (returns empty C168467Rh)"
            else "anti-revoke: DAO short-circuit installed (returns null — build the C168467Rh for best compatibility)",
        )
    }

    /**
     * Flip `AbstractC33701en.A1A(C1AE)` to false when the message still has a readable body, so any UI path that
     * special-cases revoked messages (notifications, chat-list snippets, biz events) keeps rendering the original.
     */
    private fun hookIsRevokePredicate(ctx: FeatureContext, loader: ClassLoader) {
        val cls = WaReflect.loadClass(ctx, "X.1en", loader) ?: run {
            ctx.log.w("anti-revoke: X.1en (AbstractC33701en) not found; predicate flip disabled.")
            return
        }
        val c1ae = WaReflect.loadClass(ctx, "X.1AE", loader) ?: return
        val candidates = WaReflect.methodsMatching(cls) { m ->
            m.name == "A1A" && m.parameterTypes.size == 1 && m.parameterTypes[0] == c1ae &&
                m.returnType == Boolean::class.javaPrimitiveType
        }
        if (candidates.isEmpty()) {
            ctx.log.w("anti-revoke: AbstractC33701en.A1A(C1AE) not found; predicate flip disabled.")
            return
        }
        candidates.forEach { m ->
            ctx.hooks.before(m) { call ->
                try {
                    val msg = call.args[0] ?: return@before
                    // Only flip if the message still carries a readable body (otherwise it may be a true backup
                    // revoke with no original to show).
                    val body = runCatching { msg.javaClass.getMethod("A05").invoke(msg) }.getOrNull()
                    if (body != null) call.result = false
                } catch (_: Throwable) {
                }
            }
        }
        ctx.log.i("anti-revoke: isRevoke predicate flipped for messages that still carry a body")
    }
}
