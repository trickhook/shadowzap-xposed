package io.github.trickhook.shadowzap.whatsapp

import io.github.trickhook.shadowzap.api.hooks.before
import io.github.trickhook.shadowzap.core.Env
import io.github.trickhook.shadowzap.core.Feature
import io.github.trickhook.shadowzap.core.FeatureContext
import io.github.trickhook.shadowzap.settings.SwitchStore
import io.github.trickhook.shadowzap.settings.Switches

/**
 * Force-flips a curated set of WhatsApp A/B boolean props to `true`, so client-side gated features ship on this
 * install regardless of what the server has (or has not) delivered to this account.
 *
 * **How WA looks a boolean prop up on 2.26.39.11:**
 * - Abstract base `X.C00D` holds five `ImmutableMap` tables (`A17..A1B`) built from static initializers.
 * - Three concrete subclasses — `X.C015107i` (UserAbProps catalog), `X.C02650Ci` (pre-chatd), `X.C0E0` — fill the
 *   tables.
 * - Every boolean lookup goes through the virtual `X.C00D.A12(int): boolean`. 25k+ call sites in this build.
 * - Fast path reads MobileConfig overrides first (`com.facebook.mobileconfig.*`), falling back to the ImmutableMap
 *   defaults. If the key is not in the map, a slow path throws `"Unknown BooleanField: <id>"`.
 *
 * We hook `A12(int)` virtually and, when the id is in our allowlist, set the result to `true` BEFORE the original
 * runs. Everything else falls through to WA's own logic — plumbing props (fresh-session, logging rate, etc.) stay
 * exactly as the server set them.
 *
 * **Why an allowlist instead of a blanket "always true":** props like 23048 (MobileConfig master gate), 24896
 * (framework active), 22646 (sticky overrides), 25403 (fresh session), 22647 (logging rate), 31165/32010
 * (counter/exposure logging) directly control the AbProps machinery itself. Flipping them to true blindly can
 * change rollout behaviour or crash start-up on some builds. We opt in by id only.
 */
internal object PropsUnlockFeature : Feature {
    override val id: String = "props-unlock"

    /** Curated, build-tested boolean prop ids we flip to true. */
    private val ALLOWLIST: Set<Int> = setOf(
        // Rendering / UI goodies
        15281, // Inline LaTeX / math in messages
        22221, // Rich-text span rendering in WaTextView
        18876, // Link long-press action sheet
        10747, // Expanded downloadable-media messages in chat list
        24529, // Emoji search / expressions keyboard container
        25179, // WDS wallpaper
        26177, // Audio player metadata view
        34967, // Voice-note transcription
        // Hardware-keyboard navigation
        33604,
        24725,
        29138,
        // Conversation list and composer gates
        25906,
        25907,
        25928,
        26437,
        // Contact-name date/time formatting
        11410,
    )

    override fun isEnabled(env: Env): Boolean = SwitchStore.isEnabled(env, Switches.PROPS_UNLOCK)

    override fun install(ctx: FeatureContext) {
        ctx.onContext { context ->
            val loader = context.classLoader ?: ctx.env.hostClassLoader
            // JADX file is at X/C00D.java with no "renamed from" comment, so the DEX name on disk is X.C00D;
            // older builds expose it as X.00D (digit-prefixed, JADX would then rename). Try both.
            val c00d = WaReflect.loadClass(ctx, "X.C00D", loader)
                ?: WaReflect.loadClass(ctx, "X.00D", loader)
                ?: run {
                    ctx.log.w("props-unlock: AbProps base class (X.C00D / X.00D) not found; feature inert.")
                    return@onContext
                }
            val boolType = Boolean::class.javaPrimitiveType!!
            val intType = Int::class.javaPrimitiveType!!

            val a12 = WaReflect.methodsMatching(c00d) { m ->
                m.name == "A12" && m.parameterTypes.size == 1 && m.parameterTypes[0] == intType &&
                    m.returnType == boolType
            }.firstOrNull() ?: run {
                ctx.log.w("props-unlock: X.C00D.A12(int) not found; signature changed.")
                return@onContext
            }

            ctx.hooks.before(a12) { call ->
                try {
                    val id = call.args[0] as? Int ?: return@before
                    if (id in ALLOWLIST) call.result = true
                } catch (_: Throwable) {
                }
            }

            // Belt-and-braces: the static sink used by typed C09G variants.
            val a0d = WaReflect.methodsMatching(c00d) { m ->
                m.name == "A0D" && m.parameterTypes.size == 4 &&
                    m.parameterTypes[3] == intType && m.returnType == boolType
            }.firstOrNull()
            if (a0d != null) {
                ctx.hooks.before(a0d) { call ->
                    try {
                        val id = call.args[3] as? Int ?: return@before
                        if (id in ALLOWLIST) call.result = true
                    } catch (_: Throwable) {
                    }
                }
                ctx.log.i("props-unlock: hooked A12(int) + static A0D sink; ${ALLOWLIST.size} prop id(s) force-true")
            } else {
                ctx.log.i("props-unlock: hooked A12(int); ${ALLOWLIST.size} prop id(s) force-true")
            }
        }
    }
}
