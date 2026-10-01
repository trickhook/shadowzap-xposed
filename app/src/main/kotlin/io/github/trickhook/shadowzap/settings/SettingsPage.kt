package io.github.trickhook.shadowzap.settings

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.CompoundButton
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
 * module's own resources are not available inside the host process). Switches write through [SwitchStore], so a
 * change is saved immediately; features that decide at startup follow it after "Reiniciar WhatsApp".
 */
internal object SettingsPage {
    /** Shows the page on [activity]. Safe to call from any thread; does nothing if the activity is going away. */
    fun show(activity: Activity) = MainThread.run {
        if (activity.isFinishing || activity.isDestroyed) return@run
        val kernel = Kernel.current
        val log = kernel?.logs?.logger("settings")
        try {
            val dataDir = kernel?.env?.dataDir ?: activity.dataDir
            val palette = Palette.of(activity)
            val dialog = Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar)
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            dialog.setContentView(content(activity, dialog, palette, dataDir, log))
            dialog.window?.setBackgroundDrawable(ColorDrawable(palette.background))
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                // Edge-to-edge from Android 15 on: the status bar shows the dialog's own background.
                @Suppress("DEPRECATION")
                dialog.window?.statusBarColor = palette.background
            }
            dialog.show()
        } catch (t: Throwable) {
            log?.e("Could not show the settings page", t)
        }
    }

    private fun content(context: Context, dialog: Dialog, palette: Palette, dataDir: java.io.File, log: Logger?): View {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, context.dp(24))
        }
        column.addView(header(context, dialog, palette))

        val switches = SwitchStore.forDataDir(dataDir)
        for (section in SettingsModel.sections) {
            column.addView(sectionTitle(context, palette, section.title))
            for (item in section.items) {
                column.addView(switchRow(context, palette, item, switches.isEnabled(item.id)) { enabled ->
                    try {
                        SwitchStore.set(dataDir, item.id, enabled)
                        true
                    } catch (t: Throwable) {
                        log?.e("Could not save switch ${item.id}", t)
                        Toast.makeText(context, "Não foi possível salvar", Toast.LENGTH_SHORT).show()
                        false
                    }
                })
            }
        }

        column.addView(sectionTitle(context, palette, "Sistema"))
        column.addView(actionRow(context, palette, "Reiniciar WhatsApp", "Aplica as mudanças que precisam de reinício.") {
            restart(context, log, forgetHooks = false)
        })
        column.addView(actionRow(context, palette, "Procurar hooks de novo", "Use depois de atualizar o WhatsApp se algo parou de funcionar.") {
            restart(context, log, forgetHooks = true)
        })

        return ScrollView(context).apply {
            isFillViewport = true
            addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun header(context: Context, dialog: Dialog, palette: Palette): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(context.dp(8), context.dp(12), context.dp(16), context.dp(12))
        addView(TextView(context).apply {
            text = "←"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTextColor(palette.primaryText)
            gravity = Gravity.CENTER
            minWidth = context.dp(48)
            minHeight = context.dp(48)
            contentDescription = "Voltar"
            isClickable = true
            setOnClickListener { dialog.dismiss() }
        })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(8), 0, 0, 0)
            addView(TextView(context).apply {
                text = "Shadowzap"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(palette.primaryText)
            })
            addView(TextView(context).apply {
                text = "Versão ${LoaderIdentity.VERSION}"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(palette.secondaryText)
            })
        })
    }

    private fun sectionTitle(context: Context, palette: Palette, title: String): View = TextView(context).apply {
        text = title
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(palette.accent)
        setPadding(context.dp(20), context.dp(20), context.dp(20), context.dp(8))
    }

    /** A row with title, description and a switch; [onChange] returns whether the new state was saved. */
    private fun switchRow(
        context: Context,
        palette: Palette,
        item: SettingsModel.Item,
        checked: Boolean,
        onChange: (Boolean) -> Boolean,
    ): View {
        val toggle = Switch(context).apply {
            isChecked = checked
            thumbTintList = palette.switchThumb
            trackTintList = palette.switchTrack
            contentDescription = item.title
        }
        val row = textRow(context, palette, item.title, item.description, trailing = toggle)
        var reverting = false
        toggle.setOnCheckedChangeListener { button: CompoundButton, isChecked: Boolean ->
            if (reverting) return@setOnCheckedChangeListener
            if (!onChange(isChecked)) {
                reverting = true
                button.isChecked = !isChecked
                reverting = false
            }
        }
        row.setOnClickListener { toggle.toggle() }
        return row
    }

    private fun actionRow(context: Context, palette: Palette, title: String, description: String, onClick: () -> Unit): View =
        textRow(context, palette, title, description, trailing = null).apply { setOnClickListener { onClick() } }

    private fun textRow(context: Context, palette: Palette, title: String, description: String, trailing: View?): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = context.dp(64)
            setPadding(context.dp(20), context.dp(12), context.dp(16), context.dp(12))
            isClickable = true
            isFocusable = true
            background = context.selectableBackground()
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(context).apply {
                        text = title
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                        setTextColor(palette.primaryText)
                    })
                    addView(TextView(context).apply {
                        text = description
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                        setTextColor(palette.secondaryText)
                        setPadding(0, context.dp(2), 0, 0)
                    })
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            if (trailing != null) {
                addView(trailing, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    leftMargin = context.dp(12)
                })
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
            Toast.makeText(context, "Não foi possível reiniciar", Toast.LENGTH_SHORT).show()
        }
    }

    /** Colours that follow the host's light or dark mode, with WhatsApp's green as accent. */
    private class Palette(
        val background: Int,
        val primaryText: Int,
        val secondaryText: Int,
        val accent: Int,
        val switchThumb: ColorStateList,
        val switchTrack: ColorStateList,
    ) {
        companion object {
            private val CHECKED = intArrayOf(android.R.attr.state_checked)
            private val UNCHECKED = intArrayOf()

            fun of(context: Context): Palette {
                val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                    Configuration.UI_MODE_NIGHT_YES
                val accent = if (night) Color.rgb(0x21, 0xC0, 0x63) else Color.rgb(0x00, 0x8A, 0x3E)
                val offThumb = if (night) Color.rgb(0x9E, 0xA7, 0xAD) else Color.rgb(0xF5, 0xF5, 0xF5)
                val offTrack = if (night) Color.rgb(0x37, 0x40, 0x45) else Color.rgb(0xBD, 0xBD, 0xBD)
                return Palette(
                    background = if (night) Color.rgb(0x0B, 0x14, 0x1A) else Color.WHITE,
                    primaryText = if (night) Color.rgb(0xE9, 0xED, 0xEF) else Color.rgb(0x11, 0x1B, 0x21),
                    secondaryText = if (night) Color.rgb(0x8D, 0x99, 0xA0) else Color.rgb(0x66, 0x77, 0x81),
                    accent = accent,
                    switchThumb = ColorStateList(arrayOf(CHECKED, UNCHECKED), intArrayOf(accent, offThumb)),
                    switchTrack = ColorStateList(
                        arrayOf(CHECKED, UNCHECKED),
                        intArrayOf(Color.argb(0x80, Color.red(accent), Color.green(accent), Color.blue(accent)), offTrack),
                    ),
                )
            }
        }
    }

    private fun Context.dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private fun Context.selectableBackground() = TypedValue().let { value ->
        theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
        getDrawable(value.resourceId)
    }
}
