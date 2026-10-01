package io.github.trickhook.shadowzap.whatsapp

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.trickhook.shadowzap.core.Feature
import io.github.trickhook.shadowzap.core.FeatureContext
import io.github.trickhook.shadowzap.extras.ActivityEvents
import io.github.trickhook.shadowzap.extras.watchActivities
import io.github.trickhook.shadowzap.settings.SettingsPage
import java.lang.ref.WeakReference

/**
 * Adds a "Shadowzap" row to the top of WhatsApp's own Settings screen. Tapping it opens [SettingsPage].
 *
 * The hook is resource-id based so it survives WhatsApp renaming classes between builds: the Settings fragment's
 * root layout holds a `LinearLayout` whose Android resource entry name is `container` (verified in WhatsApp
 * 2.26.39.11, where `com.whatsapp.settings.ui.SettingsFragment.A20` inflates a layout with
 * `view.findViewById(R.id.container)`). We watch `com.whatsapp.settings.ui.SettingsTabActivity` and, as soon as
 * the fragment's view is attached, walk its view hierarchy for that id, then insert our row at position 0 (tagged
 * so the hook is idempotent across configuration changes and tab switches).
 */
internal object SettingsEntryFeature : Feature {
    override val id: String = "settings-entry"

    private const val SETTINGS_ACTIVITY_SUFFIX = "SettingsTabActivity"
    private const val CONTAINER_RES_NAME = "container"
    private const val ROW_TAG = "io.github.trickhook.shadowzap.settings-row"

    override fun install(ctx: FeatureContext) {
        ctx.watchActivities(object : ActivityEvents() {
            override fun onActivityResumed(activity: Activity) {
                if (!activity.javaClass.name.endsWith(SETTINGS_ACTIVITY_SUFFIX)) return
                tryInject(ctx, activity)
            }
        })
    }

    private fun tryInject(ctx: FeatureContext, activity: Activity) {
        val root = activity.window?.decorView as? ViewGroup ?: return
        if (findContainer(root) != null) {
            inject(ctx, activity, root)
            return
        }
        // The container is in a fragment that attaches later; retry once the view tree changes.
        val weakActivity = WeakReference(activity)
        val weakRoot = WeakReference(root)
        val listener = object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                val a = weakActivity.get()
                val r = weakRoot.get()
                if (a == null || r == null || a.isFinishing || a.isDestroyed) {
                    try {
                        r?.viewTreeObserver?.removeOnGlobalLayoutListener(this)
                    } catch (_: Throwable) {
                    }
                    return
                }
                val container = findContainer(r) ?: return
                try {
                    r.viewTreeObserver.removeOnGlobalLayoutListener(this)
                } catch (_: Throwable) {
                }
                try {
                    insertRow(ctx, a, container)
                } catch (t: Throwable) {
                    ctx.log.e("Could not insert the Shadowzap row", t)
                }
            }
        }
        try {
            root.viewTreeObserver.addOnGlobalLayoutListener(listener)
        } catch (t: Throwable) {
            ctx.log.w("Could not watch the Settings view tree", t)
        }
    }

    private fun inject(ctx: FeatureContext, activity: Activity, root: ViewGroup) {
        val container = findContainer(root) ?: return
        try {
            insertRow(ctx, activity, container)
        } catch (t: Throwable) {
            ctx.log.e("Could not insert the Shadowzap row", t)
        }
    }

    private fun insertRow(ctx: FeatureContext, activity: Activity, container: ViewGroup) {
        if (container.findViewWithTag<View?>(ROW_TAG) != null) return
        val row = buildRow(activity) { SettingsPage.show(activity) }
        row.tag = ROW_TAG
        container.addView(row, 0)
        ctx.log.i("Added the Shadowzap row to ${activity.javaClass.simpleName}")
    }

    /** Depth-first walk for the first [ViewGroup] whose Android resource entry name is [CONTAINER_RES_NAME]. */
    private fun findContainer(view: View): ViewGroup? {
        if (view is ViewGroup && isContainer(view)) return view
        if (view !is ViewGroup) return null
        for (i in 0 until view.childCount) {
            val child = view.getChildAt(i) ?: continue
            findContainer(child)?.let { return it }
        }
        return null
    }

    private fun isContainer(view: View): Boolean {
        val id = view.id
        if (id == View.NO_ID) return false
        return try {
            view.resources.getResourceEntryName(id) == CONTAINER_RES_NAME
        } catch (_: Throwable) {
            false
        }
    }

    private fun buildRow(activity: Activity, onClick: () -> Unit): View {
        val dp = { value: Int ->
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), activity.resources.displayMetrics).toInt()
        }
        val night = activity.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val accent = if (night) Color.rgb(0x21, 0xC0, 0x63) else Color.rgb(0x00, 0x8A, 0x3E)
        val primaryText = if (night) Color.rgb(0xE9, 0xED, 0xEF) else Color.rgb(0x11, 0x1B, 0x21)
        val secondaryText = if (night) Color.rgb(0x8D, 0x99, 0xA0) else Color.rgb(0x66, 0x77, 0x81)

        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            minimumHeight = dp(72)
            isClickable = true
            isFocusable = true
            background = selectableBackground(activity)
            setOnClickListener { onClick() }
            contentDescription = "Shadowzap"
        }

        val iconHolder = FrameLayout(activity).apply {
            val size = dp(40)
            layoutParams = LinearLayout.LayoutParams(size, size).apply { rightMargin = dp(16) }
            background = roundBackground(accent, dp(20))
        }
        iconHolder.addView(ImageView(activity).apply {
            setImageDrawable(bolt(accent, Color.WHITE, dp(22)))
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            layoutParams = FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER)
        })
        row.addView(iconHolder)

        row.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(activity).apply {
                text = "Shadowzap"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTypeface(typeface, Typeface.NORMAL)
                setTextColor(primaryText)
            })
            addView(TextView(activity).apply {
                text = "Baixar status, enviar em HD"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(secondaryText)
                setPadding(0, dp(2), 0, 0)
            })
        })
        return row
    }

    private fun selectableBackground(activity: Activity) = TypedValue().let { value ->
        activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
        activity.getDrawable(value.resourceId)
    }

    /** Solid circle used behind the Shadowzap glyph. */
    private fun roundBackground(color: Int, radiusPx: Int): android.graphics.drawable.Drawable =
        android.graphics.drawable.ShapeDrawable(android.graphics.drawable.shapes.OvalShape()).apply {
            paint.color = color
            intrinsicWidth = radiusPx * 2
            intrinsicHeight = radiusPx * 2
        }

    /** A lightning-bolt glyph drawn with a Path, so no module resources are needed inside the host. */
    private fun bolt(bg: Int, fg: Int, sizePx: Int): android.graphics.drawable.Drawable {
        val path = android.graphics.Path().apply {
            moveTo(sizePx * 0.58f, 0f)
            lineTo(sizePx * 0.18f, sizePx * 0.56f)
            lineTo(sizePx * 0.44f, sizePx * 0.56f)
            lineTo(sizePx * 0.34f, sizePx * 1.0f)
            lineTo(sizePx * 0.82f, sizePx * 0.42f)
            lineTo(sizePx * 0.52f, sizePx * 0.42f)
            lineTo(sizePx * 0.68f, 0f)
            close()
        }
        return object : android.graphics.drawable.Drawable() {
            private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = fg
                style = android.graphics.Paint.Style.FILL
            }

            override fun draw(canvas: android.graphics.Canvas) {
                canvas.drawPath(path, paint)
            }

            override fun setAlpha(alpha: Int) {
                paint.alpha = alpha
            }

            override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
                paint.colorFilter = colorFilter
            }

            @Deprecated("API 29+")
            override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT

            override fun getIntrinsicWidth(): Int = sizePx
            override fun getIntrinsicHeight(): Int = sizePx
        }
    }
}
