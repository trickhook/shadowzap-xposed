package io.github.trickhook.shadowzap.whatsapp

import android.view.Window
import android.view.WindowManager
import io.github.trickhook.shadowzap.api.hooks.before
import io.github.trickhook.shadowzap.api.hooks.requireMethod
import io.github.trickhook.shadowzap.core.Env
import io.github.trickhook.shadowzap.core.Feature
import io.github.trickhook.shadowzap.core.FeatureContext
import io.github.trickhook.shadowzap.settings.SwitchStore
import io.github.trickhook.shadowzap.settings.Switches

/**
 * Strips `FLAG_SECURE` from every Window inside WhatsApp so screenshots and screen recordings work on screens WA
 * usually blocks — view-once media, chat lock (biometric), 2FA setup, Payments.
 *
 * Hooks the framework method `android.view.Window.setFlags(int flags, int mask)` and clears the FLAG_SECURE bit
 * from the flags argument when it is set, before the window applies them. Also hooks `addFlags(int)` for symmetry.
 *
 * No ban exposure — this is a client-local flag; Meta cannot observe its state.
 */
internal object FlagSecureStripFeature : Feature {
    override val id: String = "strip-flag-secure"

    override fun isEnabled(env: Env): Boolean = SwitchStore.isEnabled(env, Switches.STRIP_FLAG_SECURE)

    override fun install(ctx: FeatureContext) {
        val setFlags = Window::class.java.requireMethod(
            "setFlags",
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
        )
        val addFlags = Window::class.java.requireMethod(
            "addFlags",
            Int::class.javaPrimitiveType!!,
        )
        val secure = WindowManager.LayoutParams.FLAG_SECURE
        ctx.hooks.before(setFlags) { call ->
            val flags = call.args[0] as? Int ?: return@before
            if (flags and secure != 0) call.args[0] = flags and secure.inv()
        }
        ctx.hooks.before(addFlags) { call ->
            val flags = call.args[0] as? Int ?: return@before
            if (flags and secure != 0) call.args[0] = flags and secure.inv()
        }
        ctx.log.i("strip-flag-secure: FLAG_SECURE will be stripped from every WA Window")
    }
}
