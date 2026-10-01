// STAGING — NOT WIRED. Rejected by verify (see ui-staging/REVIEW.md).
// screen=home switch_id=ui.homeRedesign feature=io.github.trickhook.shadowzap.whatsapp.HomeRedesignFeature

package io.github.trickhook.shadowzap.whatsapp

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.trickhook.shadowzap.core.Env
import io.github.trickhook.shadowzap.core.Feature
import io.github.trickhook.shadowzap.core.FeatureContext
import io.github.trickhook.shadowzap.extras.ActivityEvents
import io.github.trickhook.shadowzap.extras.watchActivities
import io.github.trickhook.shadowzap.settings.SwitchStore
import io.github.trickhook.shadowzap.settings.Switches
import java.lang.ref.WeakReference

/**
 * Material 3 Expressive re-skin of WhatsApp's Home activity (chat list) per the Shadowzap design system.
 *
 * The hook is deliberately resource-name based rather than class-name based, so it survives the aggressive R8
 * renaming that WhatsApp ships: we watch every resumed Activity whose simple name ends with "HomeActivity",
 * walk the decor view, and look up children by [android.content.res.Resources.getResourceEntryName]. The entries
 * we care about on this build are `toolbar`, `toolbar_container`, `fab`, `fab_second`, `bottom_nav`,
 * `bottom_nav_container`, `conversation_container` and `list` (android:id/list).
 *
 * All view mutation runs after a layout pass so the activity has already attached its own children; we tag the
 * root with [REDESIGN_TAG_KEY] so a configuration change or tab switch does not re-skin on top of itself. Every
 * reflective lookup is guarded: on miss we leave the stock view alone — the module stays installed and the user
 * sees unmodified WhatsApp instead of a crash.
 */
internal object HomeRedesignFeature : Feature {
    override val id: String = "home-redesign"

    private const val HOME_ACTIVITY_SUFFIX = "HomeActivity"
    private const val REDESIGN_TAG = "io.github.trickhook.shadowzap.home-redesigned"
    private const val CHIP_ROW_TAG = "io.github.trickhook.shadowzap.home-chip-row"

    override fun isEnabled(env: Env): Boolean = SwitchStore.isEnabled(env, Switches.HOME_REDESIGN)

    override fun install(ctx: FeatureContext) {
        ctx.watchActivities(object : ActivityEvents() {
            override fun onActivityResumed(activity: Activity) {
                if (!activity.javaClass.name.endsWith(HOME_ACTIVITY_SUFFIX)) return
                scheduleApply(ctx, activity)
            }
        })
    }

    private fun scheduleApply(ctx: FeatureContext, activity: Activity) {
        val root = activity.window?.decorView as? ViewGroup ?: return
        if (readTag(root, REDESIGN_TAG_KEY) == true) return
        // First attempt — the layout may already be fully built.
        if (tryApply(ctx, activity, root)) return
        // Otherwise wait for a layout pass and try again. A single global-layout listener catches the late inflation
        // of the bottom nav + conversation list after the fragment manager has committed its transactions.
        val weakActivity = WeakReference(activity)
        val weakRoot = WeakReference(root)
        val listener = object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                val a = weakActivity.get()
                val r = weakRoot.get()
                if (a == null || r == null || a.isFinishing || a.isDestroyed) {
                    try { r?.viewTreeObserver?.removeOnGlobalLayoutListener(this) } catch (_: Throwable) {}
                    return
                }
                if (tryApply(ctx, a, r)) {
                    try { r.viewTreeObserver.removeOnGlobalLayoutListener(this) } catch (_: Throwable) {}
                }
            }
        }
        try {
            root.viewTreeObserver.addOnGlobalLayoutListener(listener)
        } catch (t: Throwable) {
            ctx.log.w("home-redesign: could not install layout listener", t)
        }
    }

    private fun tryApply(ctx: FeatureContext, activity: Activity, root: ViewGroup): Boolean {
        return try {
            applyRedesign(ctx, activity, root)
        } catch (t: Throwable) {
            ctx.log.e("home-redesign: apply pass failed; leaving stock UI in place", t)
            // Mark done so we do not spin on broken builds.
            writeTag(root, REDESIGN_TAG_KEY, true)
            true
        }
    }

    private fun applyRedesign(ctx: FeatureContext, activity: Activity, root: ViewGroup): Boolean {
        val toolbar = findByEntryName(root, "toolbar") as? ViewGroup
        val toolbarContainer = findByEntryName(root, "toolbar_container") as? ViewGroup
        val bottomNav = findByEntryName(root, "bottom_nav")
        val bottomNavContainer = findByEntryName(root, "bottom_nav_container") as? ViewGroup
        val fab = findByEntryName(root, "fab")
        val conversationContainer = findByEntryName(root, "conversation_container") as? ViewGroup

        // We need at least one of the top-level IDs or the activity has not finished inflating yet — bail and let
        // the global-layout listener retry on the next pass.
        if (toolbar == null && toolbarContainer == null && bottomNav == null && fab == null) return false

        // Root + system bars — the body background is the ground truth for every seam the top bar sits on top of.
        root.setBackgroundColor(Palette.BACKGROUND)
        activity.window?.let { w ->
            try {
                w.statusBarColor = Color.TRANSPARENT
                w.navigationBarColor = Palette.SURFACE_ELEVATED
            } catch (_: Throwable) {}
        }

        toolbarContainer?.setBackgroundColor(Palette.BACKGROUND)
        toolbar?.let { reskinTopBar(activity, it) }
        conversationContainer?.setBackgroundColor(Palette.BACKGROUND)
        conversationContainer?.let { injectSearchPill(activity, it) }
        bottomNavContainer?.setBackgroundColor(Palette.BACKGROUND)
        bottomNav?.let { reskinBottomNav(activity, it) }
        fab?.let { reskinFab(activity, it) }

        writeTag(root, REDESIGN_TAG_KEY, true)
        ctx.log.i("home-redesign: applied (toolbar=${toolbar != null}, fab=${fab != null}, nav=${bottomNav != null})")
        return true
    }

    // --- Top bar -----------------------------------------------------------------------------------------------

    private fun reskinTopBar(activity: Activity, toolbar: ViewGroup) {
        toolbar.setBackgroundColor(Palette.BACKGROUND)
        toolbar.elevation = 0f
        // The stock Material Toolbar has a child TextView for the title plus a child ActionMenuView — tint them
        // without replacing the toolbar itself, so the host's own action callbacks still fire.
        for (i in 0 until toolbar.childCount) {
            val child = toolbar.getChildAt(i) ?: continue
            when (child) {
                is TextView -> {
                    child.setTextColor(Palette.TEXT_PRIMARY)
                    child.setTypeface(child.typeface, Typeface.BOLD)
                    child.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                    child.setPadding(activity.dp(4), 0, 0, 0)
                }
                is ViewGroup -> tintActionIcons(child)
            }
        }
    }

    private fun tintActionIcons(group: ViewGroup) {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i) ?: continue
            if (child is ImageView) {
                try { child.setColorFilter(Palette.TEXT_PRIMARY) } catch (_: Throwable) {}
                attachPressScale(child)
            } else if (child is ViewGroup) {
                tintActionIcons(child)
            }
        }
    }

    // --- Search pill + filter chips ----------------------------------------------------------------------------

    private fun injectSearchPill(activity: Activity, container: ViewGroup) {
        if (container.findViewWithTag<View?>(CHIP_ROW_TAG) != null) return

        val wrapper = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            tag = CHIP_ROW_TAG
            setPadding(0, activity.dp(4), 0, activity.dp(4))
            setBackgroundColor(Palette.BACKGROUND)
        }

        wrapper.addView(buildSearchPill(activity), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            activity.dp(56),
        ).apply {
            leftMargin = activity.dp(Spacing.GUTTER)
            rightMargin = activity.dp(Spacing.GUTTER)
            topMargin = activity.dp(8)
            bottomMargin = activity.dp(8)
        })

        wrapper.addView(buildChipRow(activity), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            activity.dp(48),
        ))

        try {
            container.addView(wrapper, 0)
        } catch (_: Throwable) {
            container.addView(wrapper)
        }
    }

    private fun buildSearchPill(activity: Activity): View {
        val pill = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = pillBackground(Palette.SURFACE, radiusDp = Spacing.RADIUS_PILL, activity)
            setPadding(activity.dp(Spacing.GUTTER), 0, activity.dp(Spacing.GUTTER), 0)
            isClickable = true
            isFocusable = true
        }
        pill.addView(dot(activity, Palette.TEXT_SECONDARY).apply {
            layoutParams = LinearLayout.LayoutParams(activity.dp(Spacing.ICON_SIZE), activity.dp(Spacing.ICON_SIZE))
        })
        pill.addView(TextView(activity).apply {
            text = "Ask Meta AI or Search"
            setTextColor(Palette.TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(activity.dp(12), 0, activity.dp(12), 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            isSingleLine = true
        })
        pill.addView(dot(activity, Palette.TEXT_SECONDARY).apply {
            layoutParams = LinearLayout.LayoutParams(activity.dp(Spacing.ICON_SIZE), activity.dp(Spacing.ICON_SIZE))
        })
        attachPressScale(pill)
        return pill
    }

    private fun buildChipRow(activity: Activity): View {
        val labels = arrayOf("All", "Unread", "Favorites", "Groups")
        val scroller = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setBackgroundColor(Palette.BACKGROUND)
        }
        val strip = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(activity.dp(Spacing.GUTTER), 0, activity.dp(Spacing.GUTTER), 0)
        }
        labels.forEachIndexed { index, label ->
            strip.addView(buildChip(activity, label, selected = index == 0), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                activity.dp(36),
            ).apply {
                if (index > 0) leftMargin = activity.dp(8)
                gravity = Gravity.CENTER_VERTICAL
            })
        }
        scroller.addView(strip, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        return scroller
    }

    private fun buildChip(activity: Activity, label: String, selected: Boolean): View {
        val chip = TextView(activity).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(typeface, Typeface.NORMAL)
            gravity = Gravity.CENTER
            setPadding(activity.dp(16), 0, activity.dp(16), 0)
            minHeight = activity.dp(36)
            isClickable = true
            isFocusable = true
        }
        if (selected) {
            chip.setTextColor(Palette.BACKGROUND)
            chip.background = pillBackground(Palette.ACCENT, radiusDp = 18, activity)
        } else {
            chip.setTextColor(Palette.TEXT_PRIMARY)
            chip.background = pillBackground(Palette.SURFACE, radiusDp = 18, activity)
        }
        attachPressScale(chip)
        return chip
    }

    // --- FAB ---------------------------------------------------------------------------------------------------

    private fun reskinFab(activity: Activity, fab: View) {
        try {
            val size = activity.dp(64)
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Palette.ACCENT)
            }
            fab.background = bg
            fab.elevation = activity.dp(12).toFloat()
            if (fab is ImageView) {
                fab.setColorFilter(Palette.BACKGROUND)
            } else if (fab is ViewGroup) {
                for (i in 0 until fab.childCount) {
                    val child = fab.getChildAt(i)
                    if (child is ImageView) child.setColorFilter(Palette.BACKGROUND)
                }
            }
            val lp = fab.layoutParams
            if (lp != null) {
                lp.width = size
                lp.height = size
                fab.layoutParams = lp
            }
            attachPressScale(fab, pressScale = 0.95f)
        } catch (_: Throwable) {}
    }

    // --- Bottom navigation -------------------------------------------------------------------------------------

    private fun reskinBottomNav(activity: Activity, bottomNav: View) {
        try {
            val radius = activity.dp(16).toFloat()
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Palette.SURFACE_ELEVATED)
                cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
            }
            bottomNav.background = bg
            bottomNav.elevation = 0f
            if (bottomNav is ViewGroup) {
                tintBottomNavChildren(bottomNav)
            }
        } catch (_: Throwable) {}
    }

    private fun tintBottomNavChildren(group: ViewGroup) {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i) ?: continue
            when (child) {
                is TextView -> {
                    child.setTextColor(Palette.TEXT_TERTIARY)
                    child.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                }
                is ImageView -> {
                    try { child.setColorFilter(Palette.TEXT_TERTIARY) } catch (_: Throwable) {}
                }
                is ViewGroup -> tintBottomNavChildren(child)
            }
        }
    }

    // --- helpers -----------------------------------------------------------------------------------------------

    private fun findByEntryName(view: View, entry: String): View? {
        if (matchesEntry(view, entry)) return view
        if (view !is ViewGroup) return null
        for (i in 0 until view.childCount) {
            val child = view.getChildAt(i) ?: continue
            findByEntryName(child, entry)?.let { return it }
        }
        return null
    }

    private fun matchesEntry(view: View, entry: String): Boolean {
        val id = view.id
        if (id == View.NO_ID || id == 0) return false
        return try {
            view.resources.getResourceEntryName(id) == entry
        } catch (_: Throwable) {
            false
        }
    }

    private fun pillBackground(color: Int, radiusDp: Int, context: Context): Drawable {
        val base = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = context.dp(radiusDp).toFloat()
        }
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.WHITE)
            cornerRadius = context.dp(radiusDp).toFloat()
        }
        return try {
            RippleDrawable(colorStateList(Palette.ACCENT_PRESSED), base, mask)
        } catch (_: Throwable) {
            base
        }
    }

    private fun colorStateList(color: Int): android.content.res.ColorStateList {
        val states = arrayOf(intArrayOf())
        val colors = intArrayOf((color and 0x00FFFFFF) or 0x14000000)
        return android.content.res.ColorStateList(states, colors)
    }

    private fun dot(context: Context, color: Int): ImageView {
        val size = context.dp(Spacing.ICON_SIZE)
        val circle = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color and 0x00FFFFFF or 0x33000000)
        }
        return ImageView(context).apply {
            setImageDrawable(circle)
            layoutParams = ViewGroup.LayoutParams(size, size)
        }
    }

    /**
     * Press-feedback shared by every tappable chrome element: a short scale-only tween without a background flip,
     * matching the design system's `press_scale = 0.97` and `duration_short = 140` motion rule. We attach an
     * [View.OnTouchListener] rather than overriding dispatchTouchEvent so the host's own click handling (which may
     * be wired via setOnClickListener internally) still fires untouched — we return false to pass events through.
     */
    private fun attachPressScale(view: View, pressScale: Float = Spacing.PRESS_SCALE) {
        val duration = Motion.DURATION_SHORT.toLong()
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> animateScale(v, pressScale, duration)
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL,
                MotionEvent.ACTION_OUTSIDE -> animateScale(v, 1f, duration)
            }
            false
        }
    }

    private fun animateScale(view: View, target: Float, duration: Long) {
        val start = view.scaleX
        try {
            ValueAnimator.ofFloat(start, target).apply {
                this.duration = duration
                addUpdateListener { animator ->
                    val value = animator.animatedValue as Float
                    view.scaleX = value
                    view.scaleY = value
                }
                start()
            }
        } catch (_: Throwable) {
            view.scaleX = target
            view.scaleY = target
        }
    }

    private fun Context.dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    // --- Design tokens -----------------------------------------------------------------------------------------

    private object Palette {
        val BACKGROUND: Int = Color.parseColor("#0F1512")
        val SURFACE: Int = Color.parseColor("#1A2420")
        val SURFACE_ELEVATED: Int = Color.parseColor("#141C19")
        val DIVIDER: Int = Color.parseColor("#1F2A26")
        val ACCENT: Int = Color.parseColor("#2ECC71")
        val ACCENT_PRESSED: Int = Color.parseColor("#28B462")
        val TEXT_PRIMARY: Int = Color.parseColor("#E8F3EE")
        val TEXT_SECONDARY: Int = Color.parseColor("#B8C9C2")
        val TEXT_TERTIARY: Int = Color.parseColor("#8CA399")
        val DANGER: Int = Color.parseColor("#D64545")
    }

    private object Spacing {
        const val GUTTER: Int = 16
        const val ICON_SIZE: Int = 24
        const val RADIUS_CARD: Int = 20
        const val RADIUS_PILL: Int = 28
        const val RADIUS_FAB: Int = 32
        const val PRESS_SCALE: Float = 0.97f
    }

    private object Motion {
        const val DURATION_SHORT: Int = 140
        const val DURATION_MEDIUM: Int = 200
        const val DURATION_LONG: Int = 260
    }

    // A keyed-tag id so a configuration change or tab switch does not re-skin on top of itself. Android requires
    // keys of the form 0xRRRRTTTT with the top byte > 1 — using 0x7E in the top byte keeps us safely inside the
    // application-id range the platform accepts.
    private val REDESIGN_TAG_KEY: Int = (REDESIGN_TAG.hashCode() and 0x00FFFFFF) or (0x7E shl 24)

    private fun readTag(view: View, key: Int): Any? =
        try { view.getTag(key) } catch (_: Throwable) { null }

    private fun writeTag(view: View, key: Int, value: Any) {
        try { view.setTag(key, value) } catch (_: Throwable) {}
    }
}
