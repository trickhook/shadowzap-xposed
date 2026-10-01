package io.github.trickhook.shadowzap.whatsapp

import android.app.Activity
import android.content.ContentValues
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import io.github.trickhook.shadowzap.core.Feature
import io.github.trickhook.shadowzap.core.FeatureContext
import io.github.trickhook.shadowzap.extras.ActivityEvents
import io.github.trickhook.shadowzap.extras.watchActivities
import io.github.trickhook.shadowzap.settings.StrokedIcon
import io.github.trickhook.shadowzap.settings.SwitchStore
import io.github.trickhook.shadowzap.settings.Switches
import io.github.trickhook.shadowzap.settings.WaDesign
import io.github.trickhook.shadowzap.settings.dp
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.Executors

/**
 * Adds a floating save button to WhatsApp's Status viewer ([StatusPlaybackActivity]) that copies the currently
 * cached status media to the user's gallery under `Pictures/Shadowzap/`.
 *
 * The approach doesn't need to hook the obfuscated playback internals: WhatsApp writes every status it is about to
 * show to a public, non-scoped-storage directory
 * (`/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/.Statuses/`). When the user taps the save button
 * we pick the most-recently-modified file in that directory — the one the viewer is on — and copy it to the public
 * `Pictures/Shadowzap/` collection through MediaStore.
 *
 * The button is a circular overlay added to the activity's `android.R.id.content` view, positioned bottom-right so
 * it does not cover the reply field or the swipe-up chevron. The hook is idempotent across configuration changes
 * (view tag).
 */
internal object StatusDownloadFeature : Feature {
    override val id: String = "status-download"

    private const val STATUS_ACTIVITY_NAME = "com.whatsapp.status.playback.StatusPlaybackActivity"
    private const val OVERLAY_TAG = "io.github.trickhook.shadowzap.status-save"

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "shadowzap-status-save").apply { isDaemon = true }
    }

    override fun install(ctx: FeatureContext) {
        ctx.watchActivities(object : ActivityEvents() {
            override fun onActivityResumed(activity: Activity) {
                if (activity.javaClass.name != STATUS_ACTIVITY_NAME) return
                if (!SwitchStore.isEnabled(ctx.env, Switches.STATUS_DOWNLOAD)) return
                try {
                    attachOverlay(ctx, activity)
                } catch (t: Throwable) {
                    ctx.log.e("Could not attach the Status save button", t)
                }
            }

            override fun onActivityPaused(activity: Activity) {
                if (activity.javaClass.name != STATUS_ACTIVITY_NAME) return
                detachOverlay(activity)
            }
        })
    }

    private fun attachOverlay(ctx: FeatureContext, activity: Activity) {
        val content = activity.findViewById<ViewGroup?>(android.R.id.content) ?: return
        if (content.findViewWithTag<View?>(OVERLAY_TAG) != null) return
        val button = buildButton(ctx, activity)
        button.tag = OVERLAY_TAG
        content.addView(button)
    }

    private fun detachOverlay(activity: Activity) {
        val content = activity.findViewById<ViewGroup?>(android.R.id.content) ?: return
        val existing = content.findViewWithTag<View?>(OVERLAY_TAG) ?: return
        try {
            content.removeView(existing)
        } catch (_: Throwable) {
        }
    }

    private fun buildButton(ctx: FeatureContext, activity: Activity): View {
        val d = WaDesign.of(activity)
        val size = activity.dp(48)
        val iconSize = activity.dp(22)
        val button = ImageView(activity).apply {
            setImageDrawable(StrokedIcon.download(iconSize, Color.WHITE))
            background = roundBackground(d.accent, size / 2)
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = "Save status"
            isClickable = true
            isFocusable = true
            layoutParams = FrameLayout.LayoutParams(size, size, Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = activity.dp(16)
                bottomMargin = activity.dp(96)
            }
        }
        button.setOnClickListener {
            button.isEnabled = false
            io.execute {
                val saved = try {
                    saveLatestStatus(activity)
                } catch (t: Throwable) {
                    ctx.log.e("Could not save the status", t)
                    null
                }
                activity.runOnUiThread {
                    button.isEnabled = true
                    val message = when {
                        saved == null -> "Could not save"
                        saved -> "Status saved to Pictures/Shadowzap"
                        else -> "No status to save yet"
                    }
                    Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
                }
            }
        }
        return button
    }

    private fun roundBackground(color: Int, radiusPx: Int): Drawable =
        ShapeDrawable(OvalShape()).apply {
            paint.color = color
            intrinsicWidth = radiusPx * 2
            intrinsicHeight = radiusPx * 2
        }

    /**
     * Copies the newest file in WhatsApp's public cache of recently-viewed statuses into `Pictures/Shadowzap/`
     * through MediaStore. Returns `true` on success, `false` when the cache has no file to save and `null` on error.
     */
    private fun saveLatestStatus(activity: Activity): Boolean? {
        val latest = findLatestStatusFile() ?: return false
        val extension = latest.extension.lowercase()
        val isVideo = extension in VIDEO_EXTENSIONS
        val mime = MIME_TYPES[extension] ?: if (isVideo) "video/mp4" else "image/jpeg"
        val displayName = "status_${latest.lastModified()}.$extension"
        val collection = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(activity, latest, collection, displayName, mime)
        } else {
            saveViaPublicDir(latest, isVideo, displayName)
        }
    }

    private fun saveViaMediaStore(
        activity: Activity,
        source: File,
        collection: Uri,
        displayName: String,
        mime: String,
    ): Boolean? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            val root = if (mime.startsWith("video/")) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$root/Shadowzap")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = activity.contentResolver
        val uri = resolver.insert(collection, values) ?: return null
        try {
            resolver.openOutputStream(uri)?.use { out ->
                FileInputStream(source).use { it.copyTo(out) }
            } ?: return null
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return true
        } catch (t: Throwable) {
            try { resolver.delete(uri, null, null) } catch (_: Throwable) {}
            throw t
        }
    }

    private fun saveViaPublicDir(source: File, isVideo: Boolean, displayName: String): Boolean? {
        val root = Environment.getExternalStoragePublicDirectory(
            if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES,
        )
        val dir = File(root, "Shadowzap").apply { if (!exists() && !mkdirs()) return null }
        val target = File(dir, displayName)
        FileInputStream(source).use { input ->
            target.outputStream().use { input.copyTo(it) }
        }
        return true
    }

    /** The newest file inside WhatsApp's `.Statuses/` cache — that's the one currently in view. */
    private fun findLatestStatusFile(): File? {
        val root = Environment.getExternalStorageDirectory() ?: return null
        val dir = File(root, "Android/media/com.whatsapp/WhatsApp/Media/.Statuses")
        if (!dir.isDirectory) return null
        return dir.listFiles { file ->
            file.isFile && file.extension.lowercase() in ALLOWED_EXTENSIONS && !file.name.startsWith(".")
        }?.maxByOrNull { it.lastModified() }
    }

    private val VIDEO_EXTENSIONS = setOf("mp4", "3gp", "mov")
    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
    private val ALLOWED_EXTENSIONS = IMAGE_EXTENSIONS + VIDEO_EXTENSIONS
    private val MIME_TYPES = mapOf(
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "png" to "image/png",
        "webp" to "image/webp",
        "mp4" to "video/mp4",
        "3gp" to "video/3gpp",
        "mov" to "video/quicktime",
    )
}
