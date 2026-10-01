package io.github.trickhook.shadowzap.settings

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.util.TypedValue

/**
 * The WhatsApp settings design tokens Shadowzap targets, so the module reads as "part of WhatsApp" instead of a
 * dropped-in overlay. All drawing is procedural — no module resources inflate inside the host process.
 *
 * Measured off WhatsApp 2.26.39.11:
 *   dark bg #0B141A, primary text #E9EDEF, secondary #8696A0, divider #222D34, accent #00A884
 *   light bg #FFFFFF, primary text #111B21, secondary #54656F, divider #E9EDEF, accent #008069
 *   rows: 72dp min, 24dp outline icon left, 32dp gap, title 16sp, subtitle 14sp
 */
internal data class WaDesign(
    val isDark: Boolean,
    val background: Int,
    val surface: Int,
    val primaryText: Int,
    val secondaryText: Int,
    val divider: Int,
    val accent: Int,
    val switchOffThumb: Int,
    val switchOffTrack: Int,
    val switchOnThumb: Int,
    val switchOnTrack: Int,
) {
    val switchThumb: ColorStateList
        get() = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(switchOnThumb, switchOffThumb),
        )

    val switchTrack: ColorStateList
        get() = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(switchOnTrack, switchOffTrack),
        )

    val iconTint: ColorStateList get() = ColorStateList.valueOf(secondaryText)

    companion object {
        fun of(context: Context): WaDesign {
            val night = context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            return if (night) {
                WaDesign(
                    isDark = true,
                    background = 0xFF0B141A.toInt(),
                    surface = 0xFF111B21.toInt(),
                    primaryText = 0xFFE9EDEF.toInt(),
                    secondaryText = 0xFF8696A0.toInt(),
                    divider = 0xFF222D34.toInt(),
                    accent = 0xFF00A884.toInt(),
                    switchOffThumb = 0xFFCFD5DA.toInt(),
                    switchOffTrack = 0xFF3B454C.toInt(),
                    switchOnThumb = 0xFF00A884.toInt(),
                    switchOnTrack = 0x6600A884,
                )
            } else {
                WaDesign(
                    isDark = false,
                    background = 0xFFFFFFFF.toInt(),
                    surface = 0xFFF7F8FA.toInt(),
                    primaryText = 0xFF111B21.toInt(),
                    secondaryText = 0xFF54656F.toInt(),
                    divider = 0xFFE9EDEF.toInt(),
                    accent = 0xFF008069.toInt(),
                    switchOffThumb = 0xFFFFFFFF.toInt(),
                    switchOffTrack = 0xFF8696A0.toInt(),
                    switchOnThumb = 0xFF008069.toInt(),
                    switchOnTrack = 0x66008069,
                )
            }
        }
    }
}

/** `dp` -> `px` on the host display. */
internal fun Context.dp(value: Int): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

/** The platform's touch-feedback drawable, so taps on Shadowzap rows ripple like native WhatsApp rows. */
internal fun Context.selectableBackground(): Drawable? = TypedValue().let { value ->
    theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
    getDrawable(value.resourceId)
}

/**
 * The Shadowzap glyph: a stroked lightning bolt sized to the WhatsApp settings icon grid, drawn at [sizePx]x[sizePx].
 * [strokeWidthPx] matches the hairline of WhatsApp's own outline icons (about 2 dp at mdpi).
 */
internal class ShadowzapGlyph(
    private val sizePx: Int,
    private val strokeWidthPx: Float,
    color: Int,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthPx
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private val path = Path().apply {
        val s = sizePx.toFloat()
        // A simple, clean bolt that reads at 24 dp.
        moveTo(s * 0.56f, s * 0.08f)
        lineTo(s * 0.26f, s * 0.52f)
        lineTo(s * 0.48f, s * 0.52f)
        lineTo(s * 0.40f, s * 0.92f)
        lineTo(s * 0.76f, s * 0.44f)
        lineTo(s * 0.54f, s * 0.44f)
        lineTo(s * 0.60f, s * 0.08f)
        close()
    }

    override fun draw(canvas: Canvas) {
        canvas.drawPath(path, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    override fun setTintList(tint: ColorStateList?) {
        tint?.defaultColor?.let { paint.color = it }
    }

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getIntrinsicWidth(): Int = sizePx
    override fun getIntrinsicHeight(): Int = sizePx
}

/** A back-arrow glyph in the WhatsApp Material style, for in-process pages. */
internal class WaBackArrow(private val sizePx: Int, color: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = sizePx * 0.09f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private val path = Path().apply {
        val s = sizePx.toFloat()
        moveTo(s * 0.78f, s * 0.50f)
        lineTo(s * 0.26f, s * 0.50f)
        moveTo(s * 0.42f, s * 0.32f)
        lineTo(s * 0.24f, s * 0.50f)
        lineTo(s * 0.42f, s * 0.68f)
    }

    override fun draw(canvas: Canvas) {
        canvas.drawPath(path, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth(): Int = sizePx
    override fun getIntrinsicHeight(): Int = sizePx
}

/**
 * Simple one-glyph drawables for the settings page icons: a filled dot pattern per feature so each row has a
 * unique silhouette without needing real icon assets. All drawn stroked to match WhatsApp's outline icon set.
 */
internal class StrokedIcon(
    private val sizePx: Int,
    color: Int,
    private val build: (Float) -> Path,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = sizePx * 0.085f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private val path = build(sizePx.toFloat())

    override fun draw(canvas: Canvas) {
        canvas.drawPath(path, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    override fun setTintList(tint: ColorStateList?) {
        tint?.defaultColor?.let { paint.color = it }
    }

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth(): Int = sizePx
    override fun getIntrinsicHeight(): Int = sizePx

    companion object {
        /** A downwards arrow into a tray — "download". */
        fun download(sizePx: Int, color: Int): StrokedIcon = StrokedIcon(sizePx, color) { s ->
            Path().apply {
                moveTo(s * 0.50f, s * 0.14f)
                lineTo(s * 0.50f, s * 0.60f)
                moveTo(s * 0.32f, s * 0.46f)
                lineTo(s * 0.50f, s * 0.64f)
                lineTo(s * 0.68f, s * 0.46f)
                moveTo(s * 0.22f, s * 0.80f)
                lineTo(s * 0.78f, s * 0.80f)
            }
        }

        /** A photo frame with a sun — "image". */
        fun image(sizePx: Int, color: Int): StrokedIcon = StrokedIcon(sizePx, color) { s ->
            Path().apply {
                addRoundRect(s * 0.16f, s * 0.20f, s * 0.84f, s * 0.80f, s * 0.08f, s * 0.08f, Path.Direction.CW)
                addCircle(s * 0.38f, s * 0.40f, s * 0.06f, Path.Direction.CW)
                moveTo(s * 0.16f, s * 0.68f)
                lineTo(s * 0.36f, s * 0.52f)
                lineTo(s * 0.56f, s * 0.68f)
                lineTo(s * 0.70f, s * 0.56f)
                lineTo(s * 0.84f, s * 0.70f)
            }
        }

        /** A play triangle inside a rounded rect — "video". */
        fun video(sizePx: Int, color: Int): StrokedIcon = StrokedIcon(sizePx, color) { s ->
            Path().apply {
                addRoundRect(s * 0.16f, s * 0.26f, s * 0.84f, s * 0.74f, s * 0.08f, s * 0.08f, Path.Direction.CW)
                moveTo(s * 0.42f, s * 0.36f)
                lineTo(s * 0.42f, s * 0.64f)
                lineTo(s * 0.64f, s * 0.50f)
                close()
            }
        }

        /** Circular arrow — "restart". */
        fun restart(sizePx: Int, color: Int): StrokedIcon = StrokedIcon(sizePx, color) { s ->
            Path().apply {
                addArc(s * 0.20f, s * 0.20f, s * 0.80f, s * 0.80f, -60f, 260f)
                moveTo(s * 0.70f, s * 0.14f)
                lineTo(s * 0.80f, s * 0.30f)
                lineTo(s * 0.62f, s * 0.34f)
            }
        }

        /** Magnifier — "find". */
        fun search(sizePx: Int, color: Int): StrokedIcon = StrokedIcon(sizePx, color) { s ->
            Path().apply {
                addCircle(s * 0.44f, s * 0.44f, s * 0.22f, Path.Direction.CW)
                moveTo(s * 0.62f, s * 0.62f)
                lineTo(s * 0.80f, s * 0.80f)
            }
        }
    }
}
