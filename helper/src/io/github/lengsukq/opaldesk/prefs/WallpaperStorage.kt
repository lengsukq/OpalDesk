package io.github.lengsukq.opaldesk.prefs

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/**
 * Downsamples, persists, and caches user-selected custom gallery wallpapers so all screens
 * (Console, Local Desktop, Trackpad, and External Monitor Presentation) render at 120fps
 * with zero per-frame disk I/O and bounded memory footprint.
 */
object WallpaperStorage {
    private const val TAG = "OpalWallpaper"
    private const val MAX_EDGE_PX = 2560
    private const val FILE_PREFIX = "opal_custom_wp_"

    @Volatile
    private var cachedPath: String = ""

    @Volatile
    private var cachedImage: ImageBitmap? = null

    fun importWallpaperFromUri(ctx: Context, uri: Uri): String? {
        return try {
            val cr = ctx.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val rawW = bounds.outWidth
            val rawH = bounds.outHeight
            if (rawW <= 0 || rawH <= 0) return null

            var sample = 1
            var longest = max(rawW, rawH)
            while (longest / sample > MAX_EDGE_PX) {
                sample *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, decodeOpts) }
                ?: return null

            // Clean old wallpaper files so storage never grows
            ctx.filesDir.listFiles()?.forEach { f ->
                if (f.name.startsWith(FILE_PREFIX)) {
                    try {
                        f.delete()
                    } catch (_: Throwable) {
                    }
                }
            }

            val outFile = File(ctx.filesDir, "$FILE_PREFIX${System.currentTimeMillis()}.jpg")
            FileOutputStream(outFile).use { fos ->
                decoded.compress(Bitmap.CompressFormat.JPEG, 92, fos)
            }
            val path = outFile.absolutePath
            cachedPath = path
            cachedImage = decoded.asImageBitmap()
            AppPrefs.setCustomWallpaperPath(ctx, path)
            path
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to import custom wallpaper: ${t.message}")
            null
        }
    }

    /** O(1) non-blocking RAM lookup safe to call directly on the UI thread during composition. */
    fun getCachedWallpaperFast(path: String): ImageBitmap? {
        if (path.isBlank()) return null
        return if (path == cachedPath) cachedImage else null
    }

    fun loadCachedWallpaper(path: String): ImageBitmap? {
        if (path.isBlank()) return null
        if (path == cachedPath) {
            cachedImage?.let { return it }
        }
        val file = File(path)
        if (!file.isFile) return null
        return try {
            val bmp = BitmapFactory.decodeFile(path) ?: return null
            val img = bmp.asImageBitmap()
            cachedPath = path
            cachedImage = img
            img
        } catch (_: Throwable) {
            null
        }
    }

    fun clearCustomWallpaper(ctx: Context) {
        cachedPath = ""
        cachedImage = null
        try {
            ctx.filesDir.listFiles()?.forEach { f ->
                if (f.name.startsWith(FILE_PREFIX)) f.delete()
            }
        } catch (_: Throwable) {
        }
        AppPrefs.clearCustomWallpaper(ctx)
    }
}
