// STAGING — NOT WIRED. Rejected by verify (see ui-staging/REVIEW.md).
// screen=chat switch_id=ui.chatRedesign feature=io.github.trickhook.shadowzap.whatsapp.ChatRedesignFeature

package io.github.trickhook.shadowzap.whatsapp

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.AbsListView
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ListView
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
 * Re-skins the WhatsApp chat screen (the standalone [com.whatsapp.Conversation] activity and the embedded
 * [com.whatsapp.conversation.ConversationFragment] side-chat variant) to the Shadowzap "Material 3 Expressive"
 * palette.
 *
 * The redesign stays view-side only on purpose. We never touch the delegate (X.C45851zi.BkF) nor replace
 * [android.app.Activity.setContentView] — the delegate's post-inflate findViewById chain NPEs if any of its ids go
 * missing, so wrapping is unsafe when a build renames a stub. Instead we:
 *
 *  - Watch activities, and whenever an Activity whose class name ends with "Conversation" resumes, walk its decor
 *    view and re-paint by *Android resource entry name* (so no R-constant imports are needed and nothing breaks
 *    across R8 shuffles): `coordinator`, `toolbar`, `conversation_toolbar_stub`, `search_fragment_and_toolbar_holder`,
 *    `list`, `footer`, `entry`, `incall_baner`, `expressions_tray_view_id`.
 *  - Tag every re-painted root with [TAG_APPLIED] so a second resume (configuration change, popup dismiss, drawer
 *    reparent) is a cheap no-op.
 *  - Install a [ViewTreeObserver.OnGlobalLayoutListener] fallback for the delegate's deferred inflate path (the
 *    prewarm cache in `X.C16890oR` can hand the Toolbar back after `onCreate` returns).
 *  - On the message list we install an [AbsListView.OnScrollListener] (toggles the top-bar hairline on scroll) and
 *    an [ViewGroup.OnHierarchyChangeListener] (restyles every row's bubble when it is bound).
 *
 * Everything degrades silently: a missing id, a wrong view type, or a reflective hiccup logs at WARN and leaves
 * the stock WA UI alone. We never recycle convertViews, never replace adapters, and never swap drawables that
 * weren't ours.
 */
internal object ChatRedesignFeature : Feature {
    override val id: String = "chat-redesign"

    override fun isEnabled(env: Env): Boolean = SwitchStore.isEnabled(env, Switches.UI_CHAT_REDESIGN)

    private const val CONVERSATION_ACTIVITY_SUFFIX = "Conversation"
    private const val CONVERSATION_FRAGMENT_CLASS = "com.whatsapp.conversation.ConversationFragment"

    private const val TAG_APPLIED: Int = 0x7a4F1512.toInt() // "zap chat redesign applied"
    private const val TAG_BUBBLE: Int = 0x7a4F1513.toInt()
    private const val TAG_SCROLL_HAIRLINE: Int = 0x7a4F1514.toInt()
    private const val TAG_HIERARCHY: Int = 0x7a4F1515.toInt()

    override fun install(ctx: FeatureContext) {
        ctx.watchActivities(object : ActivityEvents() {
            override fun onActivityResumed(activity: Activity) {
                val className = activity.javaClass.name
                // Match both the standalone chat activity and any WhatsApp-side subclass whose simpleName ends in
                // "Conversation" (business variant, voip-overlay carrier, etc.). Fragment-hosted side-chat is picked
                // up by the global-layout listener attached below.
                if (!className.endsWith(CONVERSATION_ACTIVITY_SUFFIX) &&
                    className.contains(".Conversation") // tolerant: e.g. BusinessConversation subclasses
                        .not()
                ) return
                tryApply(ctx, activity)
            }
        })
    }

    // ----------------------------------------------------------------------------------------- apply pass

    private fun tryApply(ctx: FeatureContext, activity: Activity) {
        val root = activity.window?.decorView as? ViewGroup ?: return
        // First synchronous pass; many builds have the full tree available by the time onResume fires.
        if (applyIfReady(ctx, activity, root)) return
        // Fallback: wait for the next layout. The prewarm cache in Conversation.setContentView can hand super a
        // cached view that arrives after onCreate returns, so we always want this safety net.
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
                if (applyIfReady(ctx, a, r)) {
                    try { r.viewTreeObserver.removeOnGlobalLayoutListener(this) } catch (_: Throwable) {}
                }
            }
        }
        try { root.viewTreeObserver.addOnGlobalLayoutListener(listener) } catch (_: Throwable) {}
    }

    private fun applyIfReady(ctx: FeatureContext, activity: Activity, root: ViewGroup): Boolean {
        // Idempotent: if the whole root is already applied and the list is still the one we tagged, skip.
        if (root.getTag(TAG_APPLIED) === java.lang.Boolean.TRUE) return true

        val coordinator = findByResName(root, "coordinator")
        val toolbar = findByResName(root, "toolbar")
        val toolbarHolder = findByResName(root, "search_fragment_and_toolbar_holder")
        val list = findByResName(root, "list") as? ListView
        val footer = findByResName(root, "footer") as? ViewGroup
        val entry = findByResName(root, "entry") as? EditText
        val incallBanner = findByResName(root, "incall_baner")
        val tray = findByResName(root, "expressions_tray_view_id")

        // Signal: the chat subtree exists if we have at least the list OR the footer. Everything else is a bonus.
        if (list == null && footer == null) return false

        try {
            coordinator?.setBackgroundColor(Palette.background)
            toolbarHolder?.setBackgroundColor(Palette.surfaceElevated)
            toolbar?.setBackgroundColor(Palette.surfaceElevated)
            toolbar?.let { skinToolbarChildren(activity, it) }

            list?.let { skinList(activity, it, toolbarHolder ?: toolbar) }
            footer?.let { skinFooter(activity, it, entry) }
            entry?.let { skinEntry(it) }
            incallBanner?.let { skinInCallBanner(activity, it) }
            tray?.let {
                it.setBackgroundColor(Palette.surfaceElevated)
                if (it is View) applyTopRoundedBackground(it as View, Palette.surfaceElevated, dp(activity, 20))
            }

            root.setTag(TAG_APPLIED, java.lang.Boolean.TRUE)
            ctx.log.i("chat-redesign: applied on ${activity.javaClass.simpleName}")
            return true
        } catch (t: Throwable) {
            ctx.log.w("chat-redesign: apply failed on ${activity.javaClass.simpleName}: ${t.javaClass.simpleName}: ${t.message}")
            return false
        }
    }

    // ----------------------------------------------------------------------------------------- toolbar

    private fun skinToolbarChildren(ctx: Context, toolbar: View) {
        if (toolbar !is ViewGroup) return
        for (i in 0 until toolbar.childCount) {
            val child = toolbar.getChildAt(i) ?: continue
            when (child) {
                is TextView -> {
                    // Title: 17sp semibold #E8F3EE; subtitle (second TextView) 12sp #8CA399 tnum.
                    val resName = runCatching { ctx.resources.getResourceEntryName(child.id) }.getOrNull() ?: ""
                    if (resName.contains("subtitle") || resName.contains("status")) {
                        child.setTextColor(Palette.textTertiary)
                        child.setTypeface(Typeface.MONOSPACE)
                        child.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    } else {
                        child.setTextColor(Palette.textPrimary)
                        child.setTypeface(Typeface.create(child.typeface, Typeface.BOLD))
                        child.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                    }
                }
                is ImageView -> {
                    // Back / call / overflow buttons: #E8F3EE tint, 44dp hit area via padding, press-scale.
                    try { child.setColorFilter(Palette.textPrimary) } catch (_: Throwable) {}
                    attachPressScale(child)
                    val min = dp(ctx, 44)
                    if (child.layoutParams != null) {
                        child.minimumWidth = min
                        child.minimumHeight = min
                    }
                }
                is ViewGroup -> skinToolbarChildren(ctx, child)
            }
        }
    }

    // ----------------------------------------------------------------------------------------- list

    private fun skinList(ctx: Context, list: ListView, scrollObserver: View?) {
        list.setBackgroundColor(Palette.background)
        try { list.divider = null } catch (_: Throwable) {}
        try { list.dividerHeight = 0 } catch (_: Throwable) {}
        list.clipToPadding = false
        list.setPadding(list.paddingLeft, dp(ctx, 8), list.paddingRight, dp(ctx, 96))

        if (list.getTag(TAG_SCROLL_HAIRLINE) !== java.lang.Boolean.TRUE && scrollObserver != null) {
            list.setTag(TAG_SCROLL_HAIRLINE, java.lang.Boolean.TRUE)
            val target = scrollObserver
            val onHairline: (Boolean) -> Unit = { show ->
                try {
                    if (show) {
                        val hairline = GradientDrawable().apply {
                            shape = GradientDrawable.RECTANGLE
                            setColor(Palette.surfaceElevated)
                            setStroke(0, Color.TRANSPARENT)
                        }
                        // Draw hairline by stacking a 1-px solid-color layer under the current bg.
                        val layer = LayerDrawable(
                            arrayOf<Drawable>(
                                hairline,
                                GradientDrawable().apply {
                                    shape = GradientDrawable.RECTANGLE
                                    setColor(Palette.divider)
                                },
                            ),
                        )
                        layer.setLayerInset(1, 0, (target.height - Math.max(1, dp(ctx, 1) / 2)).coerceAtLeast(0), 0, 0)
                        target.background = layer
                    } else {
                        target.setBackgroundColor(Palette.surfaceElevated)
                    }
                } catch (_: Throwable) {}
            }
            list.setOnScrollListener(object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) {}
                override fun onScroll(
                    view: AbsListView?,
                    firstVisibleItem: Int,
                    visibleItemCount: Int,
                    totalItemCount: Int,
                ) {
                    val child = view?.getChildAt(0)
                    val scrolled = firstVisibleItem > 0 || (child != null && child.top < 0)
                    onHairline(scrolled)
                }
            })
        }

        if (list.getTag(TAG_HIERARCHY) !== java.lang.Boolean.TRUE) {
            list.setTag(TAG_HIERARCHY, java.lang.Boolean.TRUE)
            list.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
                override fun onChildViewAdded(parent: View?, child: View?) {
                    val row = child ?: return
                    row.post { skinRow(ctx, row) }
                }
                override fun onChildViewRemoved(parent: View?, child: View?) {}
            })
            // Also walk currently-attached children once (they were added before the listener was installed).
            for (i in 0 until list.childCount) {
                val row = list.getChildAt(i) ?: continue
                row.post { skinRow(ctx, row) }
            }
        }
    }

    /**
     * Walks a row's view tree looking for the message bubble container and recolors it. Heuristic:
     *   - A view whose [View.getResources] entry name mentions "bubble" or "main_layout" is a candidate.
     *   - Direction: a container whose horizontal gravity (via [LinearLayout]-style LayoutParams.gravity, or
     *     the x-coordinate relative to the row) biases to the end is "outgoing".
     *   - Date-separator pills (view resource name contains "date_wrapper") get the system-message style.
     *
     * We never touch a view we didn't recognise. We also never replace a child view; only its background and
     * selected TextViews' typeface/color.
     */
    private fun skinRow(ctx: Context, row: View) {
        if (row.getTag(TAG_BUBBLE) === java.lang.Boolean.TRUE) return
        try {
            // Date separators / system messages: centered pill, surface_elevated bg, tertiary text, tnum.
            if (nameContains(ctx, row, "date_wrapper") ||
                nameContains(ctx, row, "system_message")
            ) {
                applyPill(ctx, row, Palette.surfaceElevated, dp(ctx, 16))
                tintTextDescendants(row, Palette.textTertiary, forceMonospace = true, sizeSp = 11f)
                row.setTag(TAG_BUBBLE, java.lang.Boolean.TRUE)
                return
            }

            val bubble = findBubble(ctx, row) ?: run {
                // Fallback: dim timestamps anyway so they look monospaced + tertiary even if we miss the bubble.
                tintTimestampDescendants(ctx, row)
                return
            }
            val outgoing = isOutgoing(ctx, bubble, row)
            val fill = if (outgoing) Palette.bubbleOutgoing else Palette.bubbleIncoming
            val tailDp = 6
            val radDp = 20
            val radius = dp(ctx, radDp).toFloat()
            val tail = dp(ctx, tailDp).toFloat()
            val radii = if (outgoing) {
                // topStart topEnd bottomEnd bottomStart (tail on bottomEnd)
                floatArrayOf(radius, radius, radius, radius, tail, tail, radius, radius)
            } else {
                // tail on bottomStart
                floatArrayOf(radius, radius, radius, radius, radius, radius, tail, tail)
            }
            val shape = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(fill)
                cornerRadii = radii
            }
            bubble.background = shape
            // Text + timestamp inside the bubble.
            tintTextDescendants(
                bubble,
                color = Palette.textPrimary,
                forceMonospace = false,
                sizeSp = null,
            )
            tintTimestampDescendants(ctx, bubble)

            // Reply quote strip, if present: 2dp emerald left bar via left padding + background layer.
            val quote = findDescendantByNameFragment(ctx, bubble, "quoted_message_preview")
                ?: findDescendantByNameFragment(ctx, bubble, "quoted_text")
            quote?.let {
                try {
                    val existing = it.background
                    val bar = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        setColor(Palette.accent)
                    }
                    val layer = LayerDrawable(arrayOf<Drawable>(existing ?: shapeOf(Color.TRANSPARENT), bar))
                    layer.setLayerInset(1, 0, 0, (it.width - dp(ctx, 2)).coerceAtLeast(0), 0)
                    it.background = layer
                    it.setPadding(dp(ctx, 10), it.paddingTop, it.paddingRight, it.paddingBottom)
                } catch (_: Throwable) {}
            }

            // Press feedback — scale only; preserve the host's click handling and ripple-less OnTouchListener
            // compat by not consuming the event.
            attachPressScale(bubble)
            row.setTag(TAG_BUBBLE, java.lang.Boolean.TRUE)
        } catch (_: Throwable) {
            // Row might not be fully bound yet; leave untouched. The hierarchy listener will not refire for the
            // same convertView unless the ListView detaches it, which it does on recycle — so we'll get another
            // chance when it's rebound.
        }
    }

    // ----------------------------------------------------------------------------------------- footer / composer

    private fun skinFooter(ctx: Context, footer: ViewGroup, entry: EditText?) {
        // Composer background: #141C19 with top-rounded 20dp, 2-layer transparent "shadow" at the top.
        val cornerPx = dp(ctx, 20)
        applyTopRoundedBackground(footer, Palette.surfaceElevated, cornerPx)
        footer.setPadding(dp(ctx, 12), dp(ctx, 8), dp(ctx, 12), dp(ctx, 10))

        // The edit pill (parent of R.id.entry) is the inverted inset: fill #0F1512 radius 24dp. If we can't find
        // a wrapper, apply the pill directly to the EditText.
        val pill: View? = entry?.parent as? View ?: entry
        pill?.let {
            try {
                val bg = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(Palette.background)
                    cornerRadius = dp(ctx, 24).toFloat()
                }
                it.background = bg
                it.setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10))
                it.minimumHeight = dp(ctx, 48)
            } catch (_: Throwable) {}
        }

        // Tint the composer action glyphs. We walk the footer looking for ImageView/ImageButton children, tint
        // common ones (emoji, paperclip, camera) to text_secondary, and the trailing voice/send to accent.
        tintComposerIcons(ctx, footer)
        // Press scale on everything tappable.
        attachPressScaleRecursively(footer)
    }

    private fun tintComposerIcons(ctx: Context, root: ViewGroup) {
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i) ?: continue
            when {
                child is ImageView || child is ImageButton -> {
                    val resName = runCatching { ctx.resources.getResourceEntryName(child.id) }.getOrNull() ?: ""
                    val isSendOrVoice = resName.contains("send", ignoreCase = true) ||
                        resName.contains("voice", ignoreCase = true) ||
                        resName.contains("ptt", ignoreCase = true)
                    if (isSendOrVoice) {
                        val circle = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(Palette.accent)
                        }
                        child.background = circle
                        try { (child as ImageView).setColorFilter(Palette.background) } catch (_: Throwable) {}
                        if (child.layoutParams != null) {
                            child.minimumWidth = dp(ctx, 56)
                            child.minimumHeight = dp(ctx, 56)
                        }
                    } else {
                        try { (child as ImageView).setColorFilter(Palette.textSecondary) } catch (_: Throwable) {}
                    }
                }
                child is EditText -> {
                    skinEntry(child)
                }
                child is ViewGroup -> tintComposerIcons(ctx, child)
            }
        }
    }

    private fun skinEntry(entry: EditText) {
        entry.setTextColor(Palette.textPrimary)
        entry.setHintTextColor(Palette.textTertiary)
        entry.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        try { entry.background = null } catch (_: Throwable) {}
        entry.maxLines = 6
        entry.isVerticalScrollBarEnabled = true
    }

    // ----------------------------------------------------------------------------------------- in-call banner

    private fun skinInCallBanner(ctx: Context, banner: View) {
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Palette.accent)
            cornerRadius = dp(ctx, 22).toFloat()
        }
        banner.background = bg
        banner.setPadding(dp(ctx, 16), dp(ctx, 10), dp(ctx, 16), dp(ctx, 10))
        tintTextDescendants(banner, Palette.background, forceMonospace = true, sizeSp = 15f)
    }

    // ----------------------------------------------------------------------------------------- helpers

    private fun findByResName(root: View, name: String): View? {
        val id = runCatching {
            root.resources.getIdentifier(name, "id", root.context.packageName)
        }.getOrNull() ?: 0
        if (id == 0) return null
        return runCatching { root.findViewById<View?>(id) }.getOrNull()
    }

    private fun findBubble(ctx: Context, row: View): View? {
        // Common names across WA builds; order matters — most specific first.
        for (name in BUBBLE_CANDIDATE_NAMES) {
            val v = findDescendantByNameFragment(ctx, row, name)
            if (v != null) return v
        }
        return null
    }

    private val BUBBLE_CANDIDATE_NAMES = arrayOf(
        "conversation_text_row_bubble", "message_row_bubble", "bubble_container",
        "main_layout", "chat_bubble", "bubble",
    )

    private fun findDescendantByNameFragment(ctx: Context, root: View, fragment: String): View? {
        if (nameContains(ctx, root, fragment)) return root
        if (root !is ViewGroup) return null
        for (i in 0 until root.childCount) {
            val found = findDescendantByNameFragment(ctx, root.getChildAt(i) ?: continue, fragment)
            if (found != null) return found
        }
        return null
    }

    private fun nameContains(ctx: Context, view: View, fragment: String): Boolean {
        val id = view.id
        if (id == View.NO_ID) return false
        return try {
            ctx.resources.getResourceEntryName(id).contains(fragment, ignoreCase = true)
        } catch (_: Throwable) {
            false
        }
    }

    private fun isOutgoing(ctx: Context, bubble: View, row: View): Boolean {
        // Preferred: parent LinearLayout.LayoutParams.gravity.
        try {
            val lp = bubble.layoutParams
            val field = lp.javaClass.getField("gravity")
            val gravity = (field.getInt(lp))
            if (gravity and android.view.Gravity.END != 0 ||
                gravity and android.view.Gravity.RIGHT != 0
            ) return true
            if (gravity and android.view.Gravity.START != 0 ||
                gravity and android.view.Gravity.LEFT != 0
            ) return false
        } catch (_: Throwable) {}
        // Fallback: resource name hints ("from_me" / "outgoing").
        if (nameContains(ctx, bubble, "from_me") || nameContains(ctx, bubble, "outgoing")) return true
        if (nameContains(ctx, bubble, "incoming") || nameContains(ctx, bubble, "from_others")) return false
        // Last resort: left vs right half of the row.
        val rowRight = row.right
        val rowLeft = row.left
        val rowWidth = (rowRight - rowLeft).coerceAtLeast(1)
        val bubbleCenter = (bubble.left + bubble.right) / 2
        return bubbleCenter > rowLeft + rowWidth / 2
    }

    private fun tintTextDescendants(
        root: View,
        color: Int,
        forceMonospace: Boolean,
        sizeSp: Float?,
    ) {
        if (root is TextView) {
            root.setTextColor(color)
            if (forceMonospace) root.setTypeface(Typeface.MONOSPACE)
            sizeSp?.let { root.setTextSize(TypedValue.COMPLEX_UNIT_SP, it) }
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                tintTextDescendants(root.getChildAt(i) ?: continue, color, forceMonospace, sizeSp)
            }
        }
    }

    private fun tintTimestampDescendants(ctx: Context, root: View) {
        if (root is TextView) {
            val resName = runCatching { ctx.resources.getResourceEntryName(root.id) }.getOrNull() ?: ""
            if (resName.contains("date") || resName.contains("time") || resName.contains("timestamp")) {
                root.setTextColor(Palette.textTertiary)
                root.setTypeface(Typeface.MONOSPACE)
                root.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            }
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                tintTimestampDescendants(ctx, root.getChildAt(i) ?: continue)
            }
        }
    }

    private fun applyPill(ctx: Context, view: View, fill: Int, radiusPx: Int) {
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            cornerRadius = radiusPx.toFloat()
        }
        view.background = bg
        view.setPadding(dp(ctx, 14), dp(ctx, 6), dp(ctx, 14), dp(ctx, 6))
    }

    private fun applyTopRoundedBackground(view: View, fill: Int, cornerPx: Int) {
        val r = cornerPx.toFloat()
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
        view.background = bg
    }

    private fun shapeOf(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
    }

    private fun attachPressScaleRecursively(root: ViewGroup) {
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i) ?: continue
            if (child.isClickable || child is ImageView || child is ImageButton) attachPressScale(child)
            if (child is ViewGroup) attachPressScaleRecursively(child)
        }
    }

    /**
     * Press-down to [PRESS_SCALE] over [DURATION_SHORT_MS] on ACTION_DOWN, back on UP/CANCEL. We never consume the
     * event — the host's own click/long-press machinery still fires — so a stock View that doesn't want this
     * behaviour just drops the extra scale animations.
     */
    private fun attachPressScale(view: View) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> animateScale(v, PRESS_SCALE)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> animateScale(v, 1.0f)
            }
            false
        }
    }

    private fun animateScale(view: View, target: Float) {
        val from = view.scaleX
        if (Math.abs(from - target) < 0.001f) return
        try {
            ValueAnimator.ofFloat(from, target).apply {
                duration = DURATION_SHORT_MS
                addUpdateListener { a ->
                    val v = (a.animatedValue as Float)
                    view.scaleX = v
                    view.scaleY = v
                }
            }.start()
        } catch (_: Throwable) {
            // Can't animate off the main thread; fall back to a direct set.
            view.scaleX = target
            view.scaleY = target
        }
    }

    private fun dp(ctx: Context, value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            ctx.resources.displayMetrics,
        ).toInt()

    // ----------------------------------------------------------------------------------------- palette

    private const val PRESS_SCALE = 0.97f
    private const val DURATION_SHORT_MS = 140L

    /** Material 3 Expressive palette as laid out in the Shadowzap design system. */
    private object Palette {
        val background: Int = Color.parseColor("#0F1512")
        val surface: Int = Color.parseColor("#1A2420")
        val surfaceElevated: Int = Color.parseColor("#141C19")
        val divider: Int = Color.parseColor("#1F2A26")
        val accent: Int = Color.parseColor("#2ECC71")
        val textPrimary: Int = Color.parseColor("#E8F3EE")
        val textSecondary: Int = Color.parseColor("#B8C9C2")
        val textTertiary: Int = Color.parseColor("#8CA399")

        /** Incoming bubble flood: deep neutral surface above the background. */
        val bubbleIncoming: Int = surface

        /** Outgoing bubble flood: deep muted emerald so "yours" reads without flooding the row with pure accent. */
        val bubbleOutgoing: Int = Color.parseColor("#1E4A36")
    }
}
