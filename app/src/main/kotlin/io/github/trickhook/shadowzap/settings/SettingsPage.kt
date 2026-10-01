package io.github.trickhook.shadowzap.settings

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import io.github.trickhook.shadowzap.api.host.Logger
import io.github.trickhook.shadowzap.core.AppControl
import io.github.trickhook.shadowzap.core.Kernel
import io.github.trickhook.shadowzap.core.LoaderIdentity
import io.github.trickhook.shadowzap.core.MainThread

/**
 * The Shadowzap settings page: a full-screen dialog over the current WhatsApp activity, built from plain Views (the
 * module's own resources are not available inside the host process). Styled to pass for a WhatsApp settings screen:
 * same background, 72 dp rows with 24 dp outline icons on the left, a subtle 1 dp divider between sections, and
 * section headers in WA's secondary-text style. Switches write through [SwitchStore] immediately.
 */
internal object SettingsPage {
    fun show(activity: Activity) = MainThread.run {
        if (activity.isFinishing || activity.isDestroyed) return@run
        val kernel = Kernel.current
        val log = kernel?.logs?.logger("settings")
        try {
            val dataDir = kernel?.env?.dataDir ?: activity.dataDir
            val d = WaDesign.of(activity)
            val dialog = Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar)
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            dialog.setContentView(content(activity, dialog, d, dataDir, log))
            dialog.window?.setBackgroundDrawable(ColorDrawable(d.background))
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                @Suppress("DEPRECATION")
                dialog.window?.statusBarColor = d.background
            }
            dialog.show()
        } catch (t: Throwable) {
            log?.e("Could not show the settings page", t)
        }
    }

    private fun content(context: Context, dialog: Dialog, d: WaDesign, dataDir: java.io.File, log: Logger?): View {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(d.background)
            setPadding(0, 0, 0, context.dp(24))
        }
        column.addView(header(context, dialog, d))

        val switches = SwitchStore.forDataDir(dataDir)
        column.addView(statusCard(context, d, switches))
        val searchHolder = searchField(context, d) { ignored -> /* wired below */ }
        column.addView(searchHolder)
        val search = searchHolder.findViewWithTag<EditText>(TAG_SEARCH_INPUT)

        // Tag every section header + switch row with its searchable blob so the EditText can hide/show live.
        val rows = ArrayList<Pair<View, String>>()
        for (section in SettingsModel.sections) {
            val activeCount = section.items.count { switches.isEnabled(it.id) }
            val header = sectionTitle(context, d, section.title, activeCount, section.items.size)
            header.setTag(TAG_SECTION_BLOB, section.title.lowercase() + " " + section.items.joinToString(" ") { it.id.lowercase() + " " + it.title.lowercase() + " " + it.description.lowercase() })
            column.addView(header)
            rows += header to header.getTag(TAG_SECTION_BLOB) as String
            for (item in section.items) {
                val row = switchRow(
                    context, d,
                    icon = iconFor(context, d, item.id),
                    item = item,
                    checked = switches.isEnabled(item.id),
                    dataDir = dataDir, log = log,
                )
                val blob = (item.id + " " + item.title + " " + item.description).lowercase()
                row.setTag(TAG_ROW_BLOB, blob)
                column.addView(row)
                rows += row to blob
            }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim()?.lowercase().orEmpty()
                if (q.isEmpty()) {
                    rows.forEach { (view, _) -> view.visibility = View.VISIBLE }
                    return
                }
                var currentSectionView: View? = null
                var currentSectionHasMatch = false
                val sectionsAndRows = ArrayList<Triple<View, String, Boolean>>()
                for ((view, blob) in rows) {
                    val isHeader = view.getTag(TAG_SECTION_BLOB) != null
                    if (isHeader) {
                        currentSectionView?.let { sectionsAndRows += Triple(it, "", currentSectionHasMatch) }
                        currentSectionView = view
                        currentSectionHasMatch = false
                    } else {
                        val match = blob.contains(q)
                        view.visibility = if (match) View.VISIBLE else View.GONE
                        if (match) currentSectionHasMatch = true
                    }
                }
                currentSectionView?.let { sectionsAndRows += Triple(it, "", currentSectionHasMatch) }
                for ((view, _, hadMatch) in sectionsAndRows) view.visibility = if (hadMatch) View.VISIBLE else View.GONE
            }
        })

        column.addView(divider(context, d))
        column.addView(sectionTitle(context, d, "System", null, null))
        column.addView(
            actionRow(
                context, d,
                icon = StrokedIcon.restart(context.dp(24), d.secondaryText),
                title = "Restart WhatsApp",
                description = "Apply the changes that require a restart.",
            ) { restart(context, log, forgetHooks = false) },
        )
        column.addView(
            actionRow(
                context, d,
                icon = StrokedIcon.search(context.dp(24), d.secondaryText),
                title = "Rediscover hooks",
                description = "Use this after a WhatsApp update if something stopped working.",
            ) { restart(context, log, forgetHooks = true) },
        )
        column.addView(
            actionRow(
                context, d,
                icon = StrokedIcon.image(context.dp(24), d.secondaryText),
                title = "About",
                description = "Versions, platform and credits.",
            ) {
                val activity = context as? Activity
                if (activity != null) AboutPage.show(activity)
            },
        )

        column.addView(footer(context, d))

        return ScrollView(context).apply {
            isFillViewport = true
            setBackgroundColor(d.background)
            addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    /** The screen's top bar: back arrow + "Shadowzap" title, matching WhatsApp's own screen headers. */
    private fun header(context: Context, dialog: Dialog, d: WaDesign): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(context.dp(4), context.dp(8), context.dp(16), context.dp(8))
        minimumHeight = context.dp(56)
        addView(ImageView(context).apply {
            val size = context.dp(24)
            setImageDrawable(WaBackArrow(size, d.primaryText))
            layoutParams = LinearLayout.LayoutParams(context.dp(48), context.dp(48)).apply {
                leftMargin = context.dp(4)
            }
            setPadding(context.dp(12), context.dp(12), context.dp(12), context.dp(12))
            background = context.selectableBackground()
            isClickable = true
            isFocusable = true
            contentDescription = "Back"
            setOnClickListener { dialog.dismiss() }
        })
        addView(TextView(context).apply {
            text = "Shadowzap"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(d.primaryText)
            setPadding(context.dp(16), 0, 0, 0)
        })
    }

    /** Section header: shows a WA-style accent label plus a muted "N of M on" counter when the section has toggles. */
    private fun sectionTitle(context: Context, d: WaDesign, title: String, activeCount: Int? = null, total: Int? = null): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(24), context.dp(20), context.dp(24), context.dp(8))
            addView(
                TextView(context).apply {
                    text = title
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(d.accent)
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            if (activeCount != null && total != null) {
                addView(TextView(context).apply {
                    text = "$activeCount of $total on"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(d.secondaryText)
                    setTypeface(typeface, Typeface.NORMAL)
                    typeface = Typeface.MONOSPACE
                })
            }
        }

    /** The status card: framework + WA version + feature-on counter + protocol subsystems detected. */
    private fun statusCard(context: Context, d: WaDesign, switches: Switches): View {
        val kernel = Kernel.current
        val waVersion = kernel?.hostInfo?.versionName ?: "unknown"
        val framework = kernel?.env?.frameworkDescription ?: "Xposed"
        val totalFeatures = SettingsModel.sections.sumOf { it.items.size }
        val onFeatures = SettingsModel.sections.flatMap { it.items }.count { switches.isEnabled(it.id) }
        val protocolHits = try { WaProtocolInfo.probe().count { it.detected } } catch (_: Throwable) { 0 }
        val protocolTotal = try { WaProtocolInfo.probe().size } catch (_: Throwable) { 0 }

        val card = GradientDrawable().apply {
            cornerRadius = context.dp(16).toFloat()
            setColor(d.surface)
            setStroke(context.dp(1), ColorWithAlpha(d.divider, 0x80))
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = card
            val h = context.dp(16); val v = context.dp(14)
            setPadding(h, v, h, v)
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = context.dp(16); rightMargin = context.dp(16); topMargin = context.dp(4); bottomMargin = context.dp(4)
            }
            layoutParams = lp
            addView(cardRow(context, d, "WhatsApp", waVersion))
            addView(cardRow(context, d, "Framework", framework))
            addView(cardRow(context, d, "Features on", "$onFeatures of $totalFeatures"))
            addView(cardRow(context, d, "Protocols", "$protocolHits of $protocolTotal detected"))
        }
    }

    private fun cardRow(context: Context, d: WaDesign, label: String, value: String): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, context.dp(4), 0, context.dp(4))
        addView(
            TextView(context).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(d.secondaryText)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        addView(TextView(context).apply {
            text = value
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(d.primaryText)
            typeface = Typeface.MONOSPACE
        })
    }

    /** Searchable text input styled to match WA's rounded search pill. The caller attaches the TextWatcher. */
    private fun searchField(context: Context, d: WaDesign, onChange: (String) -> Unit): View {
        val input = EditText(context).apply {
            tag = TAG_SEARCH_INPUT
            hint = "Search features"
            setHintTextColor(d.secondaryText)
            setTextColor(d.primaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(context.dp(16), context.dp(10), context.dp(16), context.dp(10))
            background = GradientDrawable().apply {
                cornerRadius = context.dp(22).toFloat()
                setColor(d.surface)
            }
            isSingleLine = true
            minHeight = context.dp(44)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(16), context.dp(8), context.dp(16), context.dp(4))
            addView(
                input,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }
    }

    private const val TAG_SEARCH_INPUT: Int = 0x7A0_0001
    private const val TAG_ROW_BLOB: Int = 0x7A0_0002
    private const val TAG_SECTION_BLOB: Int = 0x7A0_0003

    /** Returns [color] with [alpha] (0..255) overriding its own alpha channel. */
    @Suppress("MagicNumber")
    private fun ColorWithAlpha(color: Int, alpha: Int): Int =
        (alpha and 0xFF shl 24) or (color and 0x00FFFFFF)

    private fun divider(context: Context, d: WaDesign): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(1)).apply {
            topMargin = context.dp(8)
        }
        setBackgroundColor(d.divider)
    }

    private fun footer(context: Context, d: WaDesign): View = TextView(context).apply {
        text = "Shadowzap · Version ${LoaderIdentity.VERSION}"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(d.secondaryText)
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(context.dp(24), context.dp(32), context.dp(24), context.dp(12))
    }

    /** Maps each switch id to its outline icon, so new switches get an icon without touching the renderer. */
    private fun iconFor(context: Context, d: WaDesign, id: String): Drawable {
        val size = context.dp(24)
        return when (id) {
            Switches.STATUS_DOWNLOAD -> StrokedIcon.download(size, d.secondaryText)
            Switches.HD_IMAGES -> StrokedIcon.image(size, d.secondaryText)
            Switches.HD_VIDEOS -> StrokedIcon.video(size, d.secondaryText)
            Switches.NO_STATUS_TRIM -> StrokedIcon.restart(size, d.secondaryText)
            Switches.ALLOW_DEBUG -> StrokedIcon.search(size, d.secondaryText)
            Switches.PROPS_UNLOCK -> StrokedIcon.restart(size, d.secondaryText)
            Switches.STRIP_FLAG_SECURE -> StrokedIcon.image(size, d.secondaryText)
            Switches.ANONYMOUS_STATUS -> StrokedIcon.search(size, d.secondaryText)
            Switches.UNLIMITED_PINS -> StrokedIcon.download(size, d.secondaryText)
            Switches.ANTI_REVOKE -> StrokedIcon.restart(size, d.secondaryText)
            Switches.SEE_EDITED -> StrokedIcon.search(size, d.secondaryText)
            Switches.VIEW_ONCE_BYPASS -> StrokedIcon.image(size, d.secondaryText)
            Switches.VIEW_ONCE_SAVE -> StrokedIcon.download(size, d.secondaryText)
            Switches.MEDIA_AUTOPREVIEW -> StrokedIcon.download(size, d.secondaryText)
            Switches.GHOST_MODE -> StrokedIcon.video(size, d.secondaryText)
            else -> StrokedIcon.search(size, d.secondaryText)
        }
    }

    private fun switchRow(
        context: Context,
        d: WaDesign,
        icon: Drawable,
        item: SettingsModel.Item,
        checked: Boolean,
        dataDir: java.io.File,
        log: Logger?,
    ): View {
        val toggle = Switch(context).apply {
            isChecked = checked
            thumbTintList = d.switchThumb
            trackTintList = d.switchTrack
            contentDescription = item.title
        }
        val row = textRow(context, d, icon, item.title, item.description, trailing = toggle)
        var reverting = false
        toggle.setOnCheckedChangeListener { button: CompoundButton, isChecked: Boolean ->
            if (reverting) return@setOnCheckedChangeListener
            try {
                SwitchStore.set(dataDir, item.id, isChecked)
            } catch (t: Throwable) {
                log?.e("Could not save switch ${item.id}", t)
                Toast.makeText(context, "Could not save", Toast.LENGTH_SHORT).show()
                reverting = true
                button.isChecked = !isChecked
                reverting = false
            }
        }
        row.setOnClickListener { toggle.toggle() }
        return row
    }

    private fun actionRow(
        context: Context,
        d: WaDesign,
        icon: Drawable,
        title: String,
        description: String,
        onClick: () -> Unit,
    ): View = textRow(context, d, icon, title, description, trailing = null).apply { setOnClickListener { onClick() } }

    /**
     * A WhatsApp-style row: 72 dp min height, 24 dp left padding, 24 dp outline icon, 32 dp gap, 16 sp title + 14 sp
     * description stacked, optional trailing control on the right.
     */
    private fun textRow(
        context: Context,
        d: WaDesign,
        icon: Drawable?,
        title: String,
        description: String,
        trailing: View?,
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = context.dp(72)
        setPadding(context.dp(24), context.dp(12), context.dp(16), context.dp(12))
        isClickable = true
        isFocusable = true
        background = context.selectableBackground()
        if (icon != null) {
            val size = context.dp(24)
            addView(ImageView(context).apply {
                setImageDrawable(icon)
                layoutParams = LinearLayout.LayoutParams(size, size).apply { rightMargin = context.dp(32) }
            })
        }
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply {
                    text = title
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    setTextColor(d.primaryText)
                })
                addView(TextView(context).apply {
                    text = description
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setTextColor(d.secondaryText)
                    setPadding(0, context.dp(2), 0, 0)
                })
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        if (trailing != null) {
            addView(
                trailing,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    leftMargin = context.dp(12)
                },
            )
        }
    }

    private fun restart(context: Context, log: Logger?, forgetHooks: Boolean) {
        val kernel = Kernel.current
        val logger = log ?: return
        try {
            if (forgetHooks && kernel != null) {
                val files = kernel.paths.hookTargetsCacheDir.listFiles { file -> file.name.startsWith("hook-targets-") }.orEmpty()
                files.forEach { if (!it.delete()) logger.w("Could not delete ${it.path}") }
                logger.i("Forgot ${files.size} hook target cache file(s)")
            }
            AppControl.reload(context, logger)
        } catch (t: Throwable) {
            logger.e("Could not restart WhatsApp", t)
            Toast.makeText(context, "Could not restart", Toast.LENGTH_SHORT).show()
        }
    }
}
