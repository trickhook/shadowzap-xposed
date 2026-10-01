// STAGING — NOT WIRED. Rejected by verify (see ui-staging/REVIEW.md).
// screen=settings switch_id=ui.settingsRedesign feature=io.github.trickhook.shadowzap.whatsapp.SettingsRedesignFeature

package io.github.trickhook.shadowzap.whatsapp

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.trickhook.shadowzap.core.Env
import io.github.trickhook.shadowzap.core.Feature
import io.github.trickhook.shadowzap.core.FeatureContext
import io.github.trickhook.shadowzap.extras.ActivityEvents
import io.github.trickhook.shadowzap.extras.watchActivities
import io.github.trickhook.shadowzap.settings.SwitchStore
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * Material-3-expressive dark reskin of WhatsApp's Settings surface (SettingsTabActivity + embedded SettingsFragment).
 *
 * We never replace a WhatsApp layout — the hook risk against obfuscated R entries is too high across WA bumps —
 * instead we wait for the activity to resume, walk the decor view, and re-paint the already-inflated views in
 * place: toolbar, AppBarLayout + MotionLayout cover, profile photo (adds a 3 dp emerald ring via a wrapper
 * [FrameLayout]), every WDSListItem-looking row, and append a "Shadowzap vXX - from Meta" footer. The pass is
 * idempotent (one entry per activity in a weak map) and re-runs on hierarchy changes so late `ViewStub` inflations
 * (linked-devices banner, payments entry) still land in our style.
 *
 * The emerald #2ECC71 Shadowzap signature is reserved for actionable chrome (icon chips, unread pills, the
 * profile ring) — row surfaces stay on the quiet #141C19 against the #0F1512 base so the eye reads
 * "controlled surface" without the UI flooding with pure accent.
 */
internal object SettingsRedesignFeature : Feature {
    override val id: String = "settings-redesign"

    override fun isEnabled(env: Env): Boolean = SwitchStore.isEnabled(env, "ui.settingsRedesign")

    override fun install(ctx: FeatureContext) {
        ctx.watchActivities(object : ActivityEvents() {
            override fun onActivityResumed(activity: Activity) {
                val name = activity.javaClass.name
                if (!name.endsWith(SETTINGS_ACTIVITY_SUFFIX)) return
                schedule(ctx, activity)
            }
        })
    }

    // ----- plumbing -----

    private const val SETTINGS_ACTIVITY_SUFFIX = "SettingsTabActivity"

    private val applied = WeakHashMap<Activity, Boolean>()

    private fun schedule(ctx: FeatureContext, activity: Activity) {
        if (applied[activity] == true) {
            val root = activity.window?.decorView as? ViewGroup ?: return
            reskinRowsOnly(ctx, activity, root)
            return
        }
        val root = activity.window?.decorView as? ViewGroup ?: return
        if (tryApply(ctx, activity, root)) return
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
        try { root.viewTreeObserver.addOnGlobalLayoutListener(listener) } catch (t: Throwable) {
            ctx.log.w("Could not watch the Settings view tree", t)
        }
    }

    private fun tryApply(ctx: FeatureContext, activity: Activity, root: ViewGroup): Boolean {
        val container = findRowHost(root) ?: return false
        return try {
            applyAll(ctx, activity, root, container)
            applied[activity] = true
            true
        } catch (t: Throwable) {
            ctx.log.e("Settings redesign pass failed", t)
            false
        }
    }

    private fun reskinRowsOnly(ctx: FeatureContext, activity: Activity, root: ViewGroup) {
        try {
            val host = findRowHost(root) ?: return
            styleRows(activity, host)
        } catch (t: Throwable) {
            ctx.log.w("Settings redesign rescan failed", t)
        }
    }

    private fun applyAll(ctx: FeatureContext, activity: Activity, root: ViewGroup, rowHost: ViewGroup) {
        paintWindow(activity)
        paintToolbar(root)
        paintAppBar(root, activity)
        paintProfileHeader(root, activity)
        rowHost.setBackgroundColor(Palette.BACKGROUND)
        rowHost.clipToPadding = false
        rowHost.setPadding(
            rowHost.paddingLeft,
            dp(activity, 8),
            rowHost.paddingRight,
            rowHost.paddingBottom,
        )
        styleRows(activity, rowHost)
        installChildWatcher(ctx, activity, rowHost)
        appendFooter(activity, rowHost, ctx.env.loaderVersion)
    }

    // ----- window / toolbar / header -----

    private fun paintWindow(activity: Activity) {
        try {
            activity.window?.decorView?.setBackgroundColor(Palette.BACKGROUND)
            activity.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Palette.BACKGROUND))
            activity.window?.statusBarColor = Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val decor = activity.window?.decorView ?: return
                var flags = decor.systemUiVisibility
                // Light icons on a dark bar: we want the DEFAULT (white) icons, so clear the "light status bar" bit.
                flags = flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
                decor.systemUiVisibility = flags
            }
        } catch (_: Throwable) {
            // Status-bar tweaks are nice to have, never fatal.
        }
    }

    private fun paintToolbar(root: ViewGroup) {
        val toolbar = findByEntryName(root, "toolbar") ?: return
        try {
            toolbar.setBackgroundColor(Palette.BACKGROUND)
            toolbar.elevation = 0f
            // WDSToolbar paints its own divider; mask it by overlaying a background strip in #0F1512 — a 0-height
            // divider isn't exposed in a portable way, so we just rely on background matching the body.
            tintChildTexts(toolbar as? ViewGroup ?: return, Palette.TEXT_PRIMARY, Palette.TEXT_SECONDARY)
            tintChildImages(toolbar, Palette.TEXT_PRIMARY)
        } catch (_: Throwable) {}
    }

    private fun paintAppBar(root: ViewGroup, activity: Activity) {
        try {
            val appBar = findByEntryName(root, "me_tab_appbar_layout") as? ViewGroup
            appBar?.setBackgroundColor(Palette.BACKGROUND)
            appBar?.elevation = 0f
            val motion = findByEntryName(root, "me_tab_motion_layout") as? ViewGroup
            motion?.setBackgroundColor(Palette.BACKGROUND)
            // Cover: null the stock image, drop in a vertical gradient #1E4A36 -> #0F1512.
            val cover = findByEntryName(root, "me_tab_cover_photo")
            if (cover is ImageView) {
                cover.setImageDrawable(null)
                cover.scaleType = ImageView.ScaleType.FIT_XY
            }
            cover?.background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Palette.COVER_TOP, Palette.BACKGROUND),
            )
            // Pin the WDSSearchBar to our surface and the pill radius.
            val search = findByEntryName(root, "wds_search_bar")
            if (search != null) {
                val bg = GradientDrawable().apply {
                    cornerRadius = dp(activity, 28).toFloat()
                    setColor(Palette.SURFACE)
                }
                search.background = bg
                tintChildTexts(search as? ViewGroup ?: return, Palette.TEXT_PRIMARY, Palette.TEXT_SECONDARY)
                tintChildImages(search, Palette.TEXT_SECONDARY)
            }
        } catch (_: Throwable) {}
    }

    private fun paintProfileHeader(root: ViewGroup, activity: Activity) {
        try {
            val photo = findByEntryName(root, "me_tab_profile_info_photo") ?: findByEntryName(root, "profile_info_photo")
            if (photo != null) {
                val size = dp(activity, 104)
                val lp = photo.layoutParams
                if (lp != null) {
                    lp.width = size
                    lp.height = size
                    photo.layoutParams = lp
                }
                // Add a 3 dp emerald ring without reparenting: use a stroked GradientDrawable as the view's
                // background; WDSProfilePhoto draws its own circle on top so our ring shows only in the 1 dp inset.
                val ring = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.TRANSPARENT)
                    setStroke(dp(activity, 3), Palette.ACCENT)
                }
                photo.background = ring
            }
            val name = findByEntryName(root, "me_tab_profile_info_name") ?: findByEntryName(root, "profile_info_name")
            if (name is TextView) {
                name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                name.setTypeface(name.typeface, Typeface.BOLD)
                name.setTextColor(Palette.TEXT_PRIMARY)
                name.gravity = Gravity.CENTER_HORIZONTAL
            }
            val username = findByEntryName(root, "me_tab_username")
            if (username is TextView) {
                username.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                username.setTextColor(Palette.TEXT_SECONDARY)
                username.gravity = Gravity.CENTER_HORIZONTAL
            }
            val about = findByEntryName(root, "me_tab_about_bubble") ?: findByEntryName(root, "profile_info_status")
            if (about is TextView) {
                about.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                about.setTextColor(Palette.TEXT_PRIMARY)
                about.setTypeface(about.typeface, Typeface.NORMAL)
            }
        } catch (_: Throwable) {}
    }

    // ----- row styling -----

    private fun styleRows(activity: Activity, host: ViewGroup) {
        val groups = computeGroups(host)
        for (g in groups) {
            val size = g.endExclusive - g.start
            if (size <= 0) continue
            for (idx in g.start until g.endExclusive) {
                val child = host.getChildAt(idx) ?: continue
                val local = idx - g.start
                val top = local == 0
                val bottom = local == size - 1
                styleRow(activity, child, top, bottom)
            }
        }
    }

    /** A contiguous block of clickable children that will share one visual card. */
    private class Group(val start: Int, val endExclusive: Int)

    private fun computeGroups(host: ViewGroup): List<Group> {
        val groups = ArrayList<Group>()
        var start = -1
        for (i in 0 until host.childCount) {
            val c = host.getChildAt(i) ?: continue
            if (isRowCandidate(c)) {
                if (start == -1) start = i
            } else if (start != -1) {
                groups += Group(start, i)
                start = -1
            }
        }
        if (start != -1) groups += Group(start, host.childCount)
        return groups
    }

    private fun isRowCandidate(v: View): Boolean {
        // We treat anything clickable with a sensible height as a row. Non-clickable section headers and raw
        // ViewStubs become group breakers.
        if (v.visibility == View.GONE) return false
        if (!v.isClickable) return false
        val mh = v.minimumHeight
        if (mh > 0 && mh < dp(v.context, 36)) return false
        if (v.height in 1 until dp(v.context, 36)) return false
        return true
    }

    private fun styleRow(activity: Activity, row: View, topOfGroup: Boolean, bottomOfGroup: Boolean) {
        try {
            val outer = dp(activity, Spacing.RADIUS_CARD)
            val radii = floatArrayOf(
                if (topOfGroup) outer.toFloat() else 0f, if (topOfGroup) outer.toFloat() else 0f,
                if (topOfGroup) outer.toFloat() else 0f, if (topOfGroup) outer.toFloat() else 0f,
                if (bottomOfGroup) outer.toFloat() else 0f, if (bottomOfGroup) outer.toFloat() else 0f,
                if (bottomOfGroup) outer.toFloat() else 0f, if (bottomOfGroup) outer.toFloat() else 0f,
            )
            val card = GradientDrawable().apply {
                setColor(Palette.SURFACE_ELEVATED)
                cornerRadii = radii
            }
            // Draw a 1 dp hairline at the bottom (inset 56 dp from start) for all rows except the last of a group.
            val bg = if (!bottomOfGroup) {
                val hair = GradientDrawable().apply { setColor(Palette.DIVIDER) }
                val layers = arrayOf<android.graphics.drawable.Drawable>(card, hair)
                android.graphics.drawable.LayerDrawable(layers).apply {
                    setLayerInset(1, dp(activity, 56), 0, 0, 0)
                    setLayerInsetTop(1, 0)
                    // Thin hairline anchored to the bottom by giving layer 1 a huge top inset — we instead rely on
                    // the layer having size equal to the row; use setLayerGravity on API 23+.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        setLayerGravity(1, Gravity.BOTTOM)
                        setLayerHeight(1, dp(activity, 1))
                    } else {
                        // Older API fallback: 1 px hairline via inset-from-top (very large) is impossible without
                        // knowing the row height, so we just omit it. Grouping still reads via the 76 dp rhythm.
                    }
                }
            } else card

            row.background = bg

            // Horizontal margin (16 dp) via layout params if available.
            val lp = row.layoutParams
            if (lp is ViewGroup.MarginLayoutParams) {
                val side = dp(activity, Spacing.GUTTER)
                lp.leftMargin = side
                lp.rightMargin = side
                lp.topMargin = if (topOfGroup) dp(activity, 8) else 0
                lp.bottomMargin = if (bottomOfGroup) dp(activity, 8) else 0
                row.layoutParams = lp
            }

            row.minimumHeight = maxOf(row.minimumHeight, dp(activity, 64))

            val isLogout = findLogoutMarker(row)
            val titleColor = if (isLogout) Palette.DANGER else Palette.TEXT_PRIMARY
            val chipFill = if (isLogout) withAlpha(Palette.DANGER, 0.14f) else withAlpha(Palette.ACCENT, 0.14f)
            val iconTint = if (isLogout) Palette.DANGER else Palette.ACCENT

            repaintRowContent(row, activity, titleColor, chipFill, iconTint, isLogout)
            installPressScale(row)
        } catch (_: Throwable) {}
    }

    private fun repaintRowContent(
        row: View,
        activity: Activity,
        titleColor: Int,
        chipFill: Int,
        iconTint: Int,
        hideChevron: Boolean,
    ) {
        if (row !is ViewGroup) return
        val texts = ArrayList<TextView>()
        collectTextViews(row, texts)
        if (texts.isNotEmpty()) {
            // Title: the first non-empty TextView, body-ish size.
            val title = texts.first()
            title.setTextColor(titleColor)
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            title.setTypeface(Typeface.create(title.typeface, Typeface.NORMAL), Typeface.NORMAL)
            // Subtitle: second TextView if present and shorter sizing.
            if (texts.size >= 2) {
                val sub = texts[1]
                sub.setTextColor(Palette.TEXT_SECONDARY)
                sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            }
            // Any additional small TextViews (badges, counters): monospace + tnum stand-in.
            for (i in 2 until texts.size) {
                val badge = texts[i]
                val t = (badge.text ?: "").toString().trim()
                if (t.length <= 4 && t.any { it.isDigit() }) {
                    badge.setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                    badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    badge.setTextColor(Palette.BACKGROUND)
                    val pill = GradientDrawable().apply {
                        setColor(Palette.ACCENT); cornerRadius = dp(activity, 11).toFloat()
                    }
                    badge.background = pill
                    val padX = dp(activity, 8)
                    badge.setPadding(padX, 0, padX, 0)
                    badge.minHeight = dp(activity, 22)
                    badge.minWidth = dp(activity, 22)
                    badge.gravity = Gravity.CENTER
                }
            }
        }

        val images = ArrayList<ImageView>()
        collectImageViews(row, images)
        if (images.isNotEmpty()) {
            // Leading icon chip: first ImageView.
            val leading = images.first()
            val size = dp(activity, 40)
            val lp = leading.layoutParams
            if (lp != null) {
                lp.width = size
                lp.height = size
                leading.layoutParams = lp
            }
            val chip = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(chipFill) }
            leading.background = chip
            val pad = dp(activity, 8)
            leading.setPadding(pad, pad, pad, pad)
            try { leading.imageTintList = android.content.res.ColorStateList.valueOf(iconTint) } catch (_: Throwable) {}
            // Trailing icon (chevron): last ImageView.
            if (images.size >= 2) {
                val trailing = images.last()
                if (hideChevron) {
                    trailing.visibility = View.GONE
                } else {
                    try { trailing.imageTintList = android.content.res.ColorStateList.valueOf(Palette.TEXT_SECONDARY) } catch (_: Throwable) {}
                }
            }
        }
    }

    private fun findLogoutMarker(row: View): Boolean {
        if (row !is ViewGroup) return false
        val texts = ArrayList<TextView>()
        collectTextViews(row, texts)
        for (t in texts) {
            val s = (t.text ?: "").toString().lowercase()
            if (s == "log out" || s == "logout" || s.startsWith("log out") || s.startsWith("sign out")) return true
        }
        return false
    }

    private fun installPressScale(row: View) {
        val oldListener = row.getTag(TAG_KEY_PRESS)
        if (oldListener == true) return
        row.setTag(TAG_KEY_PRESS, true)
        val interp = PathInterpolator(0.2f, 0f, 0f, 1f)
        val duration = Motion.DURATION_SHORT.toLong()
        val existing = row.getOnFocusChangeListener()
        val base = row
        base.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> animateScale(v, 0.995f, duration, interp)
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL,
                MotionEvent.ACTION_OUTSIDE -> animateScale(v, 1f, duration, interp)
            }
            // Don't consume — pass through to the row's actual click/ripple handling.
            false
        }
        // Keep the previous focus listener alive if any (defensive; usually null).
        existing?.let { base.onFocusChangeListener = it }
    }

    private fun animateScale(v: View, target: Float, duration: Long, interp: PathInterpolator) {
        try {
            val animatorTag = v.getTag(TAG_KEY_ANIM) as? ValueAnimator
            animatorTag?.cancel()
            val anim = ValueAnimator.ofFloat(v.scaleX, target).apply {
                this.duration = duration
                this.interpolator = interp
                addUpdateListener {
                    val f = it.animatedValue as Float
                    v.scaleX = f
                    v.scaleY = f
                }
            }
            v.setTag(TAG_KEY_ANIM, anim)
            anim.start()
        } catch (_: Throwable) {
            v.scaleX = target
            v.scaleY = target
        }
    }

    private fun installChildWatcher(ctx: FeatureContext, activity: Activity, host: ViewGroup) {
        try {
            host.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
                override fun onChildViewAdded(parent: View, child: View) {
                    try { host.post { styleRows(activity, host) } } catch (_: Throwable) {}
                }
                override fun onChildViewRemoved(parent: View, child: View) = Unit
            })
        } catch (t: Throwable) {
            ctx.log.w("Could not install hierarchy watcher on settings container", t)
        }
    }

    private fun appendFooter(activity: Activity, host: ViewGroup, version: String) {
        try {
            val existing = host.findViewWithTag<View?>(FOOTER_TAG)
            if (existing != null) return
            val tv = TextView(activity).apply {
                tag = FOOTER_TAG
                text = "Shadowzap v$version · from Meta"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(Palette.TEXT_FOOTER)
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(activity, 24), 0, dp(activity, 24))
                typeface = Typeface.create(typeface, Typeface.NORMAL)
            }
            host.addView(
                tv,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        } catch (_: Throwable) {}
    }

    // ----- hierarchy helpers -----

    /** The LinearLayout the settings rows sit inside; falls back to the nested-scroll view. */
    private fun findRowHost(root: ViewGroup): ViewGroup? {
        val container = findByEntryName(root, "container") as? ViewGroup
        if (container != null) return container
        val scroll = findByEntryName(root, "settings_nested_scroll_view") as? ViewGroup
            ?: findByEntryName(root, "settings_scroll_view") as? ViewGroup
        if (scroll != null) {
            for (i in 0 until scroll.childCount) {
                val c = scroll.getChildAt(i)
                if (c is LinearLayout) return c
            }
        }
        return null
    }

    private fun findByEntryName(root: View, entry: String): View? {
        if (matchesEntry(root, entry)) return root
        if (root !is ViewGroup) return null
        for (i in 0 until root.childCount) {
            val found = findByEntryName(root.getChildAt(i) ?: continue, entry)
            if (found != null) return found
        }
        return null
    }

    private fun matchesEntry(view: View, entry: String): Boolean {
        val id = view.id
        if (id == View.NO_ID) return false
        return try { view.resources.getResourceEntryName(id) == entry } catch (_: Throwable) { false }
    }

    private fun collectTextViews(root: View, out: MutableList<TextView>) {
        if (root is TextView && (root.text?.isNotEmpty() == true || root.hint?.isNotEmpty() == true)) out += root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) collectTextViews(root.getChildAt(i) ?: continue, out)
        }
    }

    private fun collectImageViews(root: View, out: MutableList<ImageView>) {
        if (root is ImageView) out += root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) collectImageViews(root.getChildAt(i) ?: continue, out)
        }
    }

    private fun tintChildTexts(root: ViewGroup, primary: Int, secondary: Int) {
        val all = ArrayList<TextView>()
        collectTextViews(root, all)
        if (all.isEmpty()) return
        all.first().setTextColor(primary)
        for (i in 1 until all.size) all[i].setTextColor(secondary)
    }

    private fun tintChildImages(root: View, tint: Int) {
        val all = ArrayList<ImageView>()
        collectImageViews(root, all)
        val cs = android.content.res.ColorStateList.valueOf(tint)
        for (iv in all) try { iv.imageTintList = cs } catch (_: Throwable) {}
    }

    private fun dp(ctx: Context, value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), ctx.resources.displayMetrics).toInt()

    private fun withAlpha(color: Int, fraction: Float): Int {
        val a = (fraction.coerceIn(0f, 1f) * 255f).toInt()
        return (a shl 24) or (color and 0x00FFFFFF)
    }

    // ----- design tokens -----

    private object Palette {
        val BACKGROUND = Color.parseColor("#0F1512")
        val SURFACE = Color.parseColor("#1A2420")
        val SURFACE_ELEVATED = Color.parseColor("#141C19")
        val DIVIDER = Color.parseColor("#1F2A26")
        val ACCENT = Color.parseColor("#2ECC71")
        val TEXT_PRIMARY = Color.parseColor("#E8F3EE")
        val TEXT_SECONDARY = Color.parseColor("#B8C9C2")
        val TEXT_FOOTER = Color.parseColor("#4A5A54")
        val DANGER = Color.parseColor("#D64545")
        val COVER_TOP = Color.parseColor("#1E4A36")
    }

    private object Spacing {
        const val GUTTER = 16
        const val RADIUS_CARD = 20
    }

    private object Motion {
        const val DURATION_SHORT = 140
    }

    private val TAG_KEY_PRESS = "io.github.trickhook.shadowzap.settings-redesign.press".hashCode() and 0x00FFFFFF
    private val TAG_KEY_ANIM = "io.github.trickhook.shadowzap.settings-redesign.anim".hashCode() and 0x00FFFFFF
    private const val FOOTER_TAG = "io.github.trickhook.shadowzap.settings-redesign.footer"
}
