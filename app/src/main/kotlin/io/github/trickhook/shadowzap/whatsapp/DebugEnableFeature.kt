package io.github.trickhook.shadowzap.whatsapp

import android.content.pm.ApplicationInfo
import android.os.Debug
import io.github.trickhook.shadowzap.api.hooks.before
import io.github.trickhook.shadowzap.api.hooks.requireMethod
import io.github.trickhook.shadowzap.core.Env
import io.github.trickhook.shadowzap.core.Feature
import io.github.trickhook.shadowzap.core.FeatureContext
import io.github.trickhook.shadowzap.settings.SwitchStore
import io.github.trickhook.shadowzap.settings.Switches

/**
 * Lets JDWP / frida / gdb attach to WhatsApp by neutralizing WhatsApp's Java-level anti-debug and marking the
 * process debuggable at launch.
 *
 * Scope and limits
 * ----------------
 * Recon of WA 2.26.39.11 found three `Debug.isDebuggerConnected()` call sites and no `(flags & 2)` self-check that
 * would exit the process:
 *   - `X.AbstractActivityC26717Bn0.A40()` on the registration/VerifyPhoneNumber screen gates `A01`, which makes
 *     `dispatchTouchEvent`/`dispatchKeyEvent` swallow synthetic input when a debugger is attached (anti-automation,
 *     not a kill). Already registered users never hit this path.
 *   - `SigquitBasedANRDetector.anrDetected()` early-returns when a debugger is attached (suppresses ANR uploads).
 *   - `X.C52244NLy` (Facebook Profilo sampler) skips its periodic runnable when a debugger is attached.
 *
 * One hook on `Debug.isDebuggerConnected` (and `Debug.waitingForDebugger` as a defensive sibling) covers all three.
 *
 * Flipping `appInfo.flags |= FLAG_DEBUGGABLE` on the already-attached WA process is cheap and makes any WA code
 * path that reads `ApplicationInfo.flags` believe the install is debuggable. It will NOT make `adb jdwp` list
 * WhatsApp's PID on its own — JDWP arming is latched by ART at process specialization, before this module runs.
 * For a real JDWP attach you also need `ro.debuggable=1`, a userdebug build, or a Zygisk-side helper that forces
 * the flag during specialization. On the target M52 the user already runs frida-server (companion module in
 * memory), which does not depend on JDWP arming — this feature mainly stops WA from reacting to the attach.
 *
 * Native-side anti-debug in libwasafe (WhatsApp Client Attestation) is OUT OF SCOPE: any native TracerPid /
 * ptrace / /proc/self/status scan happens outside this hook's reach, and the attestation report is sent to Meta
 * regardless. Expect increased ban risk on accounts used for real registration while an attached debugger is
 * active. Burner numbers only.
 */
internal object DebugEnableFeature : Feature {
    override val id: String = "allow-debug"

    override fun isEnabled(env: Env): Boolean = SwitchStore.isEnabled(env, Switches.ALLOW_DEBUG)

    override fun install(ctx: FeatureContext) {
        val isDebuggerConnected = Debug::class.java.requireMethod("isDebuggerConnected")
        ctx.hooks.before(isDebuggerConnected) { call -> call.result = false }

        try {
            val waitingForDebugger = Debug::class.java.requireMethod("waitingForDebugger")
            ctx.hooks.before(waitingForDebugger) { call -> call.result = false }
        } catch (t: Throwable) {
            ctx.log.w("Debug.waitingForDebugger not hookable: ${t.javaClass.simpleName}")
        }

        try {
            val appInfo: ApplicationInfo = ctx.env.appInfo
            val before = appInfo.flags
            appInfo.flags = before or ApplicationInfo.FLAG_DEBUGGABLE
            ctx.log.i(
                "ApplicationInfo.flags: 0x${before.toString(16)} -> 0x${appInfo.flags.toString(16)} (FLAG_DEBUGGABLE set)",
            )
        } catch (t: Throwable) {
            ctx.log.w("Could not set FLAG_DEBUGGABLE on ApplicationInfo: ${t.javaClass.simpleName}: ${t.message}")
        }

        ctx.log.i("Allow debugging: Debug.isDebuggerConnected forced to false; see docs for native-anti-debug limits")
    }
}
