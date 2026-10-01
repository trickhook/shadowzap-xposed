package io.github.trickhook.shadowzap.whatsapp

import android.app.Activity
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import io.github.trickhook.shadowzap.api.hooks.HookCall
import io.github.trickhook.shadowzap.api.hooks.HookCallback
import io.github.trickhook.shadowzap.core.Env
import io.github.trickhook.shadowzap.core.Feature
import io.github.trickhook.shadowzap.core.FeatureContext
import io.github.trickhook.shadowzap.settings.SwitchStore
import io.github.trickhook.shadowzap.settings.Switches
import java.io.OutputStream
import java.util.concurrent.Executors

/**
 * Adds a "Save" entry to the 3-dot overflow menu inside WhatsApp's view-once viewer
 * (`com.whatsapp.viewonce.ui.messaging.ViewOnceViewerActivity`).
 *
 * Hooks `onCreateOptionsMenu(Menu)` on that activity (after) to append a menu item at group 1 with our stable id,
 * and hooks `onOptionsItemSelected(MenuItem)` (before) to intercept clicks on that id. The handler walks the activity's
 * fragment subtree, picks the main `ImageView` showing the view-once photo, rasterises its current drawable, and writes
 * the bitmap to the user's gallery under `Pictures/Shadowzap/`.
 *
 * Video and audio view-once content are not covered by this feature yet — the entry still appears but the handler
 * reports "No image to save" when no bitmap-backed ImageView is on screen.
 */
internal object ViewOnceSaveFeature : Feature {
    override val id: String = "view-once-save"

    private const val VIEW_ONCE_ACTIVITY = "com.whatsapp.viewonce.ui.messaging.ViewOnceViewerActivity"

    /** A stable id for our menu entry — unlikely to collide with WA's R.id.* which live in the 0x7f0b.. range. */
    private const val MENU_ITEM_ID: Int = 0x55A1DEA1

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "shadowzap-viewonce-save").apply { isDaemon = true }
    }

    override fun isEnabled(env: Env): Boolean = SwitchStore.isEnabled(env, Switches.VIEW_ONCE_SAVE)

    override fun install(ctx: FeatureContext) {
        ctx.onContext { context ->
            val loader = context.classLoader ?: ctx.env.hostClassLoader
            val cls = WaReflect.loadClass(ctx, VIEW_ONCE_ACTIVITY, loader) ?: run {
                ctx.log.w("view-once-save: $VIEW_ONCE_ACTIVITY not found; feature inert.")
                return@onContext
            }

            val onCreate = findActivityMenuMethod(cls, "onCreateOptionsMenu") ?: run {
                ctx.log.w("view-once-save: onCreateOptionsMenu(Menu) not found on ViewOnceViewerActivity.")
                return@onContext
            }
            val onSelected = findActivityMenuMethod(cls, "onOptionsItemSelected") ?: run {
                ctx.log.w("view-once-save: onOptionsItemSelected(MenuItem) not found on ViewOnceViewerActivity.")
                return@onContext
            }

            ctx.hooks.hook(onCreate, callback = object : HookCallback {
                override fun after(call: HookCall) {
                    try {
                        val menu = call.args[0] as? Menu ?: return
                        if (menu.findItem(MENU_ITEM_ID) != null) return
                        menu.add(1, MENU_ITEM_ID, 0, "Save").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
                    } catch (t: Throwable) {
                        ctx.log.w("view-once-save: could not append menu item: ${t.javaClass.simpleName}: ${t.message}")
                    }
                }
            })

            ctx.hooks.hook(onSelected, callback = object : HookCallback {
                override fun before(call: HookCall) {
                    try {
                        val item = call.args[0] as? MenuItem ?: return
                        if (item.itemId != MENU_ITEM_ID) return
                        val activity = call.thisObject as? Activity ?: return
                        call.result = true
                        onSaveClicked(ctx, activity)
                    } catch (t: Throwable) {
                        ctx.log.w("view-once-save: dispatch threw ${t.javaClass.simpleName}: ${t.message}")
                    }
                }
            })

            ctx.log.i("view-once-save: Save entry wired into ViewOnceViewerActivity's 3-dot menu")
        }
    }

    private fun findActivityMenuMethod(cls: Class<*>, name: String) = try {
        when (name) {
            "onCreateOptionsMenu" -> cls.getMethod(name, Menu::class.java)
            else -> cls.getMethod(name, MenuItem::class.java)
        }
    } catch (_: Throwable) {
        null
    }

    private fun onSaveClicked(ctx: FeatureContext, activity: Activity) {
        val bitmap = extractBitmap(activity)
        if (bitmap == null) {
            Toast.makeText(activity, "No image to save", Toast.LENGTH_SHORT).show()
            return
        }
        io.execute {
            val saved = try {
                writeBitmap(activity, bitmap)
            } catch (t: Throwable) {
                ctx.log.e("view-once-save: could not save the photo", t)
                false
            }
            activity.runOnUiThread {
                Toast.makeText(
                    activity,
                    if (saved) "Saved to Pictures/Shadowzap" else "Could not save",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    /** Walks the activity's content view and picks the largest bitmap-backed ImageView currently displayed. */
    private fun extractBitmap(activity: Activity): Bitmap? {
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return null
        var best: Bitmap? = null
        var bestArea = 0
        walk(root) { v ->
            if (v is ImageView && v.isShown && v.width > 0 && v.height > 0) {
                val bmp = drawableToBitmap(v.drawable, v.width, v.height) ?: return@walk
                val area = bmp.width * bmp.height
                if (area > bestArea) {
                    best = bmp
                    bestArea = area
                }
            }
        }
        return best
    }

    private fun walk(view: View, block: (View) -> Unit) {
        block(view)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) walk(view.getChildAt(i), block)
        }
    }

    private fun drawableToBitmap(drawable: Drawable?, w: Int, h: Int): Bitmap? {
        if (drawable == null) return null
        if (drawable is BitmapDrawable) {
            val bmp = drawable.bitmap ?: return null
            if (bmp.width < 48 || bmp.height < 48) return null
            return bmp
        }
        if (w < 48 || h < 48) return null
        val bmp = try {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        } catch (_: Throwable) {
            return null
        }
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, w, h)
        drawable.draw(canvas)
        return bmp
    }

    private fun writeBitmap(activity: Activity, bitmap: Bitmap): Boolean {
        val displayName = "viewonce_${System.currentTimeMillis()}.jpg"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            writeViaMediaStore(activity, bitmap, displayName)
        } else {
            writeViaPublicDir(bitmap, displayName)
        }
    }

    private fun writeViaMediaStore(activity: Activity, bitmap: Bitmap, displayName: String): Boolean {
        val collection: Uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Shadowzap")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = activity.contentResolver
        val uri = resolver.insert(collection, values) ?: return false
        try {
            resolver.openOutputStream(uri)?.use { out: OutputStream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            } ?: return false
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return true
        } catch (t: Throwable) {
            try { resolver.delete(uri, null, null) } catch (_: Throwable) {}
            throw t
        }
    }

    private fun writeViaPublicDir(bitmap: Bitmap, displayName: String): Boolean {
        val root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val dir = java.io.File(root, "Shadowzap").apply { if (!exists() && !mkdirs()) return false }
        val target = java.io.File(dir, displayName)
        target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        return true
    }
}
