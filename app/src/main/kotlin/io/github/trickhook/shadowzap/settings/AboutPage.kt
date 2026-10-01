package io.github.trickhook.shadowzap.settings

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.trickhook.shadowzap.core.Kernel
import io.github.trickhook.shadowzap.core.LoaderIdentity
import io.github.trickhook.shadowzap.core.MainThread

/**
 * The Shadowzap About page: a full-screen dialog over the host activity, built from plain Views so no module
 * resources need to resolve inside WhatsApp's process. Mirrors Shadowcord's About layout: a Versions group
 * (Shadowzap, WhatsApp), a Platform group (OS, SDK, brand, model, codename) and an Info group (license).
 */
internal object AboutPage {
    fun show(activity: Activity) = MainThread.run {
        if (activity.isFinishing || activity.isDestroyed) return@run
        val log = Kernel.current?.logs?.logger("about")
        try {
            val d = WaDesign.of(activity)
            val dialog = Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar)
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            dialog.setContentView(content(activity, dialog, d))
            dialog.window?.setBackgroundDrawable(ColorDrawable(d.background))
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                @Suppress("DEPRECATION")
                dialog.window?.statusBarColor = d.background
            }
            dialog.show()
        } catch (t: Throwable) {
            log?.e("Could not show the about page", t)
        }
    }

    private fun content(context: Context, dialog: Dialog, d: WaDesign): View {
        val kernel = Kernel.current
        val hostInfo = kernel?.hostInfo
        val env = kernel?.env

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(d.background)
            setPadding(0, 0, 0, context.dp(24))
        }
        column.addView(header(context, dialog, d))

        column.addView(brandBlock(context, d))

        column.addView(sectionTitle(context, d, "Versions"))
        column.addView(
            infoRow(context, d, StrokedIcon.restart(context.dp(24), d.accent), "Shadowzap", LoaderIdentity.VERSION),
        )
        column.addView(
            infoRow(
                context, d,
                StrokedIcon.image(context.dp(24), d.secondaryText),
                "WhatsApp",
                hostInfo?.versionName ?: "unknown",
            ),
        )
        column.addView(
            infoRow(
                context, d,
                StrokedIcon.search(context.dp(24), d.secondaryText),
                "Framework",
                env?.frameworkDescription ?: "unknown",
            ),
        )

        column.addView(divider(context, d))
        column.addView(sectionTitle(context, d, "Platform"))
        column.addView(
            infoRow(
                context, d,
                StrokedIcon.restart(context.dp(24), d.secondaryText),
                "Android",
                "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            ),
        )
        column.addView(
            infoRow(context, d, StrokedIcon.image(context.dp(24), d.secondaryText), "Manufacturer", Build.MANUFACTURER),
        )
        column.addView(
            infoRow(context, d, StrokedIcon.search(context.dp(24), d.secondaryText), "Brand", Build.BRAND),
        )
        column.addView(
            infoRow(context, d, StrokedIcon.video(context.dp(24), d.secondaryText), "Model", Build.MODEL),
        )
        column.addView(
            infoRow(context, d, StrokedIcon.download(context.dp(24), d.secondaryText), "Codename", Build.DEVICE),
        )

        column.addView(divider(context, d))
        column.addView(sectionTitle(context, d, "Client ↔ Server"))
        val rows = WaProtocolInfo.probe()
        column.addView(
            infoRow(
                context, d,
                StrokedIcon.search(context.dp(24), d.accent),
                "Detected",
                WaProtocolInfo.summary(rows),
            ),
        )
        for (row in rows) {
            column.addView(
                protocolRow(
                    context, d,
                    icon = if (row.detected) StrokedIcon.download(context.dp(24), d.accent)
                    else StrokedIcon.search(context.dp(24), d.secondaryText),
                    label = row.name,
                    value = if (row.detected) "detected" else "absent",
                    detail = row.note,
                ),
            )
        }

        column.addView(divider(context, d))
        column.addView(sectionTitle(context, d, "Info"))
        column.addView(
            infoRow(
                context, d,
                StrokedIcon.search(context.dp(24), d.secondaryText),
                "License",
                "GPL-3.0",
            ),
        )
        column.addView(
            infoRow(
                context, d,
                StrokedIcon.image(context.dp(24), d.secondaryText),
                "Author",
                "trickhook",
            ),
        )

        return ScrollView(context).apply {
            isFillViewport = true
            setBackgroundColor(d.background)
            addView(
                column,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }
    }

    /** The hero block at the top: a large Shadowzap glyph, the name, and a one-line tagline. */
    private fun brandBlock(context: Context, d: WaDesign): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(context.dp(24), context.dp(16), context.dp(24), context.dp(24))
        addView(ImageView(context).apply {
            val size = context.dp(72)
            setImageDrawable(ShadowzapGlyph(size, size * 0.11f, d.accent))
            layoutParams = LinearLayout.LayoutParams(size, size)
        })
        addView(TextView(context).apply {
            text = "Shadowzap"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(d.primaryText)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, context.dp(12), 0, 0)
        })
        addView(TextView(context).apply {
            text = "An Xposed module for WhatsApp"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(d.secondaryText)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, context.dp(4), 0, 0)
        })
    }

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
            text = "About"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(d.primaryText)
            setPadding(context.dp(16), 0, 0, 0)
        })
    }

    private fun sectionTitle(context: Context, d: WaDesign, title: String): View = TextView(context).apply {
        text = title
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(d.accent)
        setPadding(context.dp(24), context.dp(20), context.dp(24), context.dp(8))
    }

    private fun divider(context: Context, d: WaDesign): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(1)).apply {
            topMargin = context.dp(8)
        }
        setBackgroundColor(d.divider)
    }

    /**
     * A two-line row for the Client ↔ Server section: outline icon, stacked label + muted detail on the left,
     * one-word status value on the right.
     */
    private fun protocolRow(
        context: Context,
        d: WaDesign,
        icon: Drawable,
        label: String,
        value: String,
        detail: String,
    ): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = context.dp(72)
        setPadding(context.dp(24), context.dp(10), context.dp(24), context.dp(10))
        val size = context.dp(24)
        addView(ImageView(context).apply {
            setImageDrawable(icon)
            layoutParams = LinearLayout.LayoutParams(size, size).apply { rightMargin = context.dp(32) }
        })
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply {
                    text = label
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    setTextColor(d.primaryText)
                })
                addView(TextView(context).apply {
                    text = detail
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setTextColor(d.secondaryText)
                    setPadding(0, context.dp(2), 0, 0)
                })
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        addView(TextView(context).apply {
            text = value
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(d.secondaryText)
            gravity = Gravity.END
            maxLines = 1
        })
    }

    /** A single About row: outline icon, left label (16 sp primary), right value (14 sp secondary), 72 dp min. */
    private fun infoRow(context: Context, d: WaDesign, icon: Drawable, label: String, value: String): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = context.dp(72)
            setPadding(context.dp(24), context.dp(12), context.dp(24), context.dp(12))
            val size = context.dp(24)
            addView(ImageView(context).apply {
                setImageDrawable(icon)
                layoutParams = LinearLayout.LayoutParams(size, size).apply { rightMargin = context.dp(32) }
            })
            addView(
                TextView(context).apply {
                    text = label
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    setTextColor(d.primaryText)
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(TextView(context).apply {
                text = value
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(d.secondaryText)
                gravity = Gravity.END
                maxLines = 1
            })
        }
}
