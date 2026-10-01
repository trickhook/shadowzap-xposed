// STAGING — NOT WIRED. Rejected by verify (see ui-staging/REVIEW.md).
// screen=status switch_id=ui.statusRedesign feature=io.github.trickhook.shadowzap.whatsapp.StatusRedesignFeature

package io.github.trickhook.shadowzap.whatsapp

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
import android.os.Build
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import io.github.trickhook.shadowzap.core.Env
import io.github.trickhook.shadowzap.core.Feature
import io.github.trickhook.shadowzap.core.FeatureContext
import io.github.trickhook.shadowzap.extras.ActivityEvents
import io.github.trickhook.shadowzap.extras.watchActivities
import io.github.trickhook.shadowzap.settings.SwitchStore
import io.github.trickhook.shadowzap.settings.Switches

/**
 * UI redesign for WhatsApp's Status / Updates tab, following the Shadowzap "Material 3 expressive" design system.
 *
 * We re-skin, we don't rebuild: on every HomeActivity resume we locate `R.id.updates_list` by resource NAME
 * (resource names are stable across WA builds, numeric ids are not) and repaint the RecyclerView, its parent frame,
 * section-header TextViews, FAB and secondary FAB. On StatusPlaybackActivity resume we blacken the root, tint
 * progress bars emerald and round the reply bar.
 *
 * Nothing in this feature calls into WhatsApp's R8-obfuscated internals: it walks the inflated view tree, matches
 * on resource names and view shapes, and degrades silently when a view it expected is not there (a future WA build
 * flipping the A/B flag between the XML and programmatic inflation branches is a no-op for us because we target
 * the inflated tree, not a layout resource id).
 */
internal object StatusRedesignFeature : Feature {
    override val id: String = "status-redesign"

    override fun isEnabled(env: Env): Boolean = SwitchStore.isEnabled(env, Switches.STATUS_REDESIGN)

    private const val HOME_ACTIVITY = "com.whatsapp.home.ui.HomeActivity"
    private const val STATUS_PLAYBACK_ACTIVITY = "com.whatsapp.status.playback.StatusPlaybackActivity"
    private const val SKIN_TAG_KEY = 0x535A5100           // "SZQ\0" — "already skinned" marker.
    private const val SKIN_TOUCH_TAG_KEY = 0x535A5101     // "SZQ\1" — "press scale installed" marker.

    override fun install(ctx: FeatureContext) {
        ctx.watchActivities(object : ActivityEvents() {
            override fun onActivityResumed(activity: Activity) {
                when (activity.javaClass.name) {
                    HOME_ACTIVITY -> safely(ctx, "home") { skinHome(activity) }
                    STATUS_PLAYBACK_ACTIVITY -> safely(ctx, "playback") { skinPlayback(activity) }
                }
            }

            override fun onActivityStarted(activity: Activity) {
                // Re-skin on start as well, so a tab switch back to Updates re-applies on returning fragments.
                if (activity.javaClass.name == HOME_ACTIVITY) {
                    safely(ctx, "home/start") { skinHome(activity) }
                }
            }
        })
    }

    private inline fun safely(ctx: FeatureContext, tag: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            ctx.log.w("status-redesign/$tag failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    // ---------- HomeActivity (Updates tab) ----------------------------------------------------

    private fun skinHome(activity: Activity) {
        val content = activity.findViewById<ViewGroup?>(android.R.id.content) ?: return
        val updatesList = findViewByResName(content, "updates_list") as? ViewGroup
        if (updatesList != null) {
            paintUpdatesList(updatesList)
            installRowSkin(updatesList)
        }
        skinFab(activity, "fab", primary = true)
        skinFab(activity, "fab_second", primary = false)
    }

    private fun paintUpdatesList(list: ViewGroup) {
        list.setBackgroundColor(Tokens.BG)
        (list.parent as? View)?.setBackgroundColor(Tokens.BG)
        val c = list.context
        // Horizontal padding 0dp (padding is moved inside each row), top 8dp, bottom 96dp (clears FABs).
        list.setPadding(0, c.dpI(8), 0, c.dpI(96))
        reskinSectionHeaders(list)
    }

    private fun reskinSectionHeaders(root: ViewGroup) {
        walkTextViews(root) { tv ->
            val txt = tv.text?.toString()?.trim() ?: return@walkTextViews
            if (txt in SECTION_LABELS) {
                tv.setTextColor(Tokens.TEXT_PRIMARY)
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                tv.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                val c = tv.context
                tv.setPadding(c.dpI(16), c.dpI(16), c.dpI(16), c.dpI(8))
            }
        }
    }

    /**
     * Install an [android.view.ViewGroup.OnHierarchyChangeListener] that re-skins each row as the RecyclerView
     * attaches it. We don't swap the adapter — we repaint whatever ViewHolder itemView gets handed to us.
     */
    private fun installRowSkin(list: ViewGroup) {
        if (list.getTag(SKIN_TAG_KEY) == true) return
        list.setTag(SKIN_TAG_KEY, true)

        for (i in 0 until list.childCount) {
            skinRow(list.getChildAt(i))
        }
        list.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
            override fun onChildViewAdded(parent: View, child: View) {
                try { skinRow(child) } catch (_: Throwable) {}
            }
            override fun onChildViewRemoved(parent: View, child: View) = Unit
        })
    }

    private fun skinRow(row: View) {
        if (row !is ViewGroup) {
            applyPressScale(row)
            return
        }
        row.setBackgroundColor(Tokens.BG)
        applyPressScale(row)

        val c = row.context
        walkTextViews(row) { tv ->
            val text = tv.text?.toString() ?: ""
            when {
                text.trim() in SECTION_LABELS -> {
                    tv.setTextColor(Tokens.TEXT_PRIMARY)
                    tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                    tv.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                }
                looksLikeTitle(tv) -> {
                    tv.setTextColor(Tokens.TEXT_PRIMARY)
                    tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    tv.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                }
                else -> {
                    tv.setTextColor(Tokens.TEXT_TERTIARY)
                    tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    if (looksNumeric(text)) tv.typeface = Typeface.MONOSPACE
                }
            }
        }

        findAvatar(row)?.let { avatar ->
            // Rows WA already marks activated/selected are the "unseen" rows; otherwise ring falls back to divider.
            val unseen = row.isActivated || row.isSelected
            avatar.foreground = AvatarRing(
                color = if (unseen) Tokens.ACCENT else Tokens.DIVIDER,
                strokeWidthPx = c.dpI(if (unseen) 3 else 2),
                insetPx = c.dpI(2),
                dashed = !unseen,
            )
        }

        if (row.minimumHeight < c.dpI(72)) {
            row.minimumHeight = c.dpI(76)
        }
    }

    private fun looksLikeTitle(tv: TextView): Boolean {
        val size = tv.textSize // px
        val threshold = tv.context.resources.displayMetrics.density * 15f // >= ~15sp
        return size >= threshold
    }

    private fun looksNumeric(text: String): Boolean {
        if (text.isEmpty()) return false
        var sawDigit = false
        for (ch in text) {
            when {
                ch.isDigit() -> sawDigit = true
                ch in " :,.-/" -> Unit
                else -> return false
            }
        }
        return sawDigit
    }

    private fun findAvatar(row: ViewGroup): ImageView? {
        var best: ImageView? = null
        var bestArea = 0
        walkViews(row) { v ->
            if (v is ImageView) {
                val minDp = v.context.dpI(40)
                val area = v.width * v.height
                if (v.width in minDp..v.context.dpI(80) &&
                    v.height in minDp..v.context.dpI(80) &&
                    area > bestArea
                ) {
                    best = v
                    bestArea = area
                }
            }
        }
        return best
    }

    // ---------- FABs on HomeActivity ----------------------------------------------------------

    private fun skinFab(activity: Activity, resName: String, primary: Boolean) {
        val id = resId(activity, resName) ?: return
        val fab = activity.findViewById<View?>(id) ?: return
        if (fab.getTag(SKIN_TAG_KEY) == true) return
        fab.setTag(SKIN_TAG_KEY, true)

        val c = fab.context
        val target = c.dpI(if (primary) 64 else 48)

        fab.background = if (primary) {
            ShapeDrawable(OvalShape()).apply { paint.color = Tokens.ACCENT }
        } else {
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Tokens.SURFACE)
                setStroke(c.dpI(1), Tokens.STROKE_SECONDARY_FAB)
            }
        }

        // Two-layer soft shadow on the primary FAB (API 28+ for colored shadows; elevation on all supported APIs).
        fab.elevation = if (primary) c.dp(12f) else 0f
        if (primary && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            fab.outlineAmbientShadowColor = Tokens.ACCENT
            fab.outlineSpotShadowColor = Color.BLACK
        }

        tintImageDrawable(fab, if (primary) Tokens.BG else Tokens.TEXT_PRIMARY)
        applyPressScale(fab, pressed = if (primary) 0.95f else 0.97f)

        val lp = fab.layoutParams
        if (lp != null && lp.width > 0 && lp.height > 0) {
            lp.width = target
            lp.height = target
            fab.layoutParams = lp
        }
    }

    private fun tintImageDrawable(view: View, tint: Int) {
        val img = view as? ImageView ?: findFirstImageView(view) ?: return
        val d: Drawable = img.drawable ?: return
        d.mutate().colorFilter = PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN)
    }

    private fun findFirstImageView(root: View): ImageView? {
        if (root is ImageView) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findFirstImageView(root.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    // ---------- Status playback overlay --------------------------------------------------------

    private fun skinPlayback(activity: Activity) {
        val content = activity.findViewById<ViewGroup?>(android.R.id.content) ?: return
        content.setBackgroundColor(Color.BLACK)

        walkViews(content) { v ->
            if (v is ProgressBar) {
                v.progressTintList = ColorStateList.valueOf(Tokens.ACCENT)
                v.progressBackgroundTintList =
                    ColorStateList.valueOf(Color.argb(0x3D, 0xFF, 0xFF, 0xFF))
                v.indeterminateTintList = ColorStateList.valueOf(Tokens.ACCENT)
            }
        }

        walkTextViews(content) { tv ->
            val t = tv.text?.toString() ?: return@walkTextViews
            if (t.startsWith("Reply", ignoreCase = true)) {
                tv.setTextColor(Color.argb(0xB3, 0xFF, 0xFF, 0xFF))
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                val parent = tv.parent as? View ?: return@walkTextViews
                parent.background = GradientDrawable().apply {
                    cornerRadius = tv.context.dp(28f)
                    setColor(Color.argb(0x1A, 0xFF, 0xFF, 0xFF))
                }
                applyPressScale(parent)
            }
        }
    }

    // ---------- Helpers ------------------------------------------------------------------------

    private fun resId(c: Context, name: String): Int? {
        val id = c.resources.getIdentifier(name, "id", c.packageName)
        return if (id != 0) id else null
    }

    private fun findViewByResName(root: View, name: String): View? {
        val id = resId(root.context, name) ?: return null
        return root.findViewById(id)
    }

    private fun walkTextViews(root: View, block: (TextView) -> Unit) {
        walkViews(root) { v -> if (v is TextView) block(v) }
    }

    private fun walkViews(root: View, block: (View) -> Unit) {
        block(root)
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                walkViews(root.getChildAt(i), block)
            }
        }
    }

    /**
     * Scale-only press feedback: ACTION_DOWN -> [pressed], ACTION_UP/CANCEL -> 1.0, animated over
     * [Tokens.DURATION_SHORT] ms with the StrongOut PathInterpolator. No ripple color flip.
     */
    private fun applyPressScale(view: View, pressed: Float = Tokens.PRESS_SCALE) {
        if (view.getTag(SKIN_TOUCH_TAG_KEY) == true) return
        view.setTag(SKIN_TOUCH_TAG_KEY, true)
        view.setOnTouchListener(PressScaleTouchListener(pressed))
    }

    private class PressScaleTouchListener(private val pressed: Float) : View.OnTouchListener {
        private val easing = PathInterpolator(0.2f, 0.0f, 0.0f, 1.0f)
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> animateScale(v, pressed)
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL,
                MotionEvent.ACTION_OUTSIDE,
                -> animateScale(v, 1f)
            }
            // Never consume: let WhatsApp's own click/long-press logic run as usual.
            return false
        }

        private fun animateScale(v: View, target: Float) {
            val from = v.scaleX
            ValueAnimator.ofFloat(from, target).apply {
                duration = Tokens.DURATION_SHORT.toLong()
                interpolator = easing
                addUpdateListener { a ->
                    val s = a.animatedValue as Float
                    v.scaleX = s
                    v.scaleY = s
                }
                start()
            }
        }
    }

    // ---------- Design tokens ------------------------------------------------------------------

    private object Tokens {
        val BG = Color.parseColor("#0F1512")
        val SURFACE = Color.parseColor("#1A2420")
        val SURFACE_ELEVATED = Color.parseColor("#141C19")
        val DIVIDER = Color.parseColor("#1F2A26")
        val ACCENT = Color.parseColor("#2ECC71")
        val ACCENT_PRESSED = Color.parseColor("#28B462")
        val TEXT_PRIMARY = Color.parseColor("#E8F3EE")
        val TEXT_SECONDARY = Color.parseColor("#B8C9C2")
        val TEXT_TERTIARY = Color.parseColor("#8CA399")
        val DANGER = Color.parseColor("#D64545")
        val STROKE_SECONDARY_FAB = Color.parseColor("#2A3833")

        const val DURATION_SHORT = 140
        const val DURATION_MEDIUM = 200
        const val DURATION_LONG = 260

        const val PRESS_SCALE = 0.97f
    }

    private val SECTION_LABELS = setOf(
        "Status",
        "Updates",
        "Recent updates",
        "Viewed updates",
        "Channels",
        "Find channels",
        "My status",
    )

    // ---------- Custom drawables ---------------------------------------------------------------

    /**
     * The circular ring drawn around status avatars as a Foreground (so it never clips the image bitmap).
     *
     * - [dashed] = true draws a divider-colored dashed ring (empty "no status" state).
     * - [dashed] = false draws a solid emerald ring (unseen status).
     */
    private class AvatarRing(
        private val color: Int,
        private val strokeWidthPx: Int,
        private val insetPx: Int,
        private val dashed: Boolean,
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            this.color = this@AvatarRing.color
            strokeWidth = strokeWidthPx.toFloat()
            if (dashed) {
                pathEffect = DashPathEffect(
                    floatArrayOf(strokeWidthPx * 2f, strokeWidthPx * 1.5f),
                    0f,
                )
            }
        }
        private val rect = RectF()

        override fun draw(canvas: Canvas) {
            val b = bounds
            val half = strokeWidthPx / 2f
            rect.set(
                b.left + insetPx + half,
                b.top + insetPx + half,
                b.right - insetPx - half,
                b.bottom - insetPx - half,
            )
            val r = (rect.width().coerceAtMost(rect.height())) / 2f
            canvas.drawCircle(rect.centerX(), rect.centerY(), r, paint)
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf }
        @Suppress("DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}

/** `dp` -> `px` (int) for an [android.content.Context]. Kept local so this file is self-sufficient. */
private fun Context.dpI(value: Int): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

/** `dp` -> `px` (float) for an [android.content.Context]. */
private fun Context.dp(value: Float): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)
