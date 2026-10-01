package io.github.trickhook.shadowzap.whatsapp

import android.app.Activity
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.trickhook.shadowzap.core.Feature
import io.github.trickhook.shadowzap.core.FeatureContext
import io.github.trickhook.shadowzap.extras.ActivityEvents
import io.github.trickhook.shadowzap.extras.watchActivities
import io.github.trickhook.shadowzap.settings.ShadowzapGlyph
import io.github.trickhook.shadowzap.settings.SettingsPage
import io.github.trickhook.shadowzap.settings.WaDesign
import io.github.trickhook.shadowzap.settings.dp
import io.github.trickhook.shadowzap.settings.selectableBackground
import java.lang.ref.WeakReference

/**
 * Adds a "Shadowzap" row to the top of WhatsApp's own Settings screen, styled to match the host rows (24 dp outline
 * icon in the WhatsApp accent, 72 dp row, 16 sp title, 14 sp description). Tapping it opens [SettingsPage].
 *
 * The hook is resource-id based so it survives WhatsApp renaming classes between builds: SettingsFragment's
 * `view.findViewById(R.id.container)` is the LinearLayout we add the row into. We watch SettingsTabActivity, walk
 * the decor view for the first ViewGroup whose Android resource entry name is `container`, and insert the row at
 * position 0 (tagged so the hook is idempotent across configuration changes and tab switches).
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
                val container = findContainer(r) ?: return
                try { r.viewTreeObserver.removeOnGlobalLayoutListener(this) } catch (_: Throwable) {}
                try { insertRow(ctx, a, container) } catch (t: Throwable) {
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
        try { insertRow(ctx, activity, container) } catch (t: Throwable) {
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

    /**
     * A row sized and painted like WhatsApp's own Settings rows: left icon 24 dp outline bolt tinted with the WA
     * accent, 32 dp gap, title 16 sp primary text, description 14 sp secondary. Selectable background gives the
     * platform ripple on press.
     */
    private fun buildRow(activity: Activity, onClick: () -> Unit): View {
        val d = WaDesign.of(activity)
        val iconSize = activity.dp(24)
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(activity.dp(24), activity.dp(12), activity.dp(16), activity.dp(12))
            minimumHeight = activity.dp(72)
            isClickable = true
            isFocusable = true
            background = activity.selectableBackground()
            setOnClickListener { onClick() }
            contentDescription = "Shadowzap"
        }
        row.addView(ImageView(activity).apply {
            setImageDrawable(ShadowzapGlyph(iconSize, iconSize * 0.11f, d.accent))
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                rightMargin = activity.dp(32)
            }
        })
        row.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(activity).apply {
                text = "Shadowzap"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTypeface(typeface, Typeface.NORMAL)
                setTextColor(d.primaryText)
            })
            addView(TextView(activity).apply {
                text = "Status downloads, HD media, and more"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(d.secondaryText)
                setPadding(0, activity.dp(2), 0, 0)
            })
        })
        return row
    }
}
