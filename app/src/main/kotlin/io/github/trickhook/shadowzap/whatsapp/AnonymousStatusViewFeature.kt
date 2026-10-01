package io.github.trickhook.shadowzap.whatsapp

import io.github.trickhook.shadowzap.api.hooks.before
import io.github.trickhook.shadowzap.core.Env
import io.github.trickhook.shadowzap.core.Feature
import io.github.trickhook.shadowzap.core.FeatureContext
import io.github.trickhook.shadowzap.settings.SwitchStore
import io.github.trickhook.shadowzap.settings.Switches

/**
 * Watches friends' status without sending the "played" receipt that puts your name in their viewer list.
 *
 * Hooks `com.whatsapp.messaging.receipts.jobqueue.job.SendPlayedReceiptJobV2.A0M()` (the job's run method) and
 * short-circuits it when the target JID ends with `@broadcast` — i.e. a status broadcast — leaving voice-note
 * "listened" receipts and other played-receipts intact.
 *
 * Independent of GhostMode (which hooks ReadReceiptUtils.A08 and C12E.A0V, not the played-receipt job).
 */
internal object AnonymousStatusViewFeature : Feature {
    override val id: String = "anon-status"

    private const val JOB_CLASS = "com.whatsapp.messaging.receipts.jobqueue.job.SendPlayedReceiptJobV2"

    override fun isEnabled(env: Env): Boolean = SwitchStore.isEnabled(env, Switches.ANONYMOUS_STATUS)

    override fun install(ctx: FeatureContext) {
        ctx.onContext { context ->
            val loader = context.classLoader ?: ctx.env.hostClassLoader
            val cls = WaReflect.loadClass(ctx, JOB_CLASS, loader) ?: run {
                ctx.log.w("anon-status: $JOB_CLASS not found; feature inert.")
                return@onContext
            }
            val a0m = WaReflect.methodsMatching(cls) { m ->
                m.name == "A0M" && m.parameterTypes.isEmpty() && m.returnType == Void.TYPE
            }.firstOrNull() ?: run {
                ctx.log.w("anon-status: $JOB_CLASS.A0M() not found; signature changed.")
                return@onContext
            }
            ctx.hooks.before(a0m) { call ->
                try {
                    val self = call.thisObject ?: return@before
                    // Pull the raw JID off the job instance. Field name varies by build — scan string fields and
                    // match the one ending with @broadcast or any s.whatsapp.net JID.
                    val toRaw = self.javaClass.declaredFields.asSequence()
                        .filter { it.type == String::class.java }
                        .mapNotNull { f ->
                            try { f.isAccessible = true; f.get(self) as? String } catch (_: Throwable) { null }
                        }
                        .firstOrNull { it.endsWith("@broadcast") }
                    if (toRaw != null) {
                        call.result = null
                    }
                } catch (_: Throwable) {
                }
            }
            ctx.log.i("anon-status: SendPlayedReceiptJobV2.A0M suppressed for @broadcast targets")
        }
    }
}
