package io.github.lengsukq.opaldesk.compositor

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.github.lengsukq.opaldesk.BridgeClient
import io.github.lengsukq.opaldesk.R
import io.github.lengsukq.opaldesk.protocol.AppProfileKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Collator
import java.util.Locale

data class InstalledAppEntry(
    val pkg: String,
    val activityClass: String,
    val label: String,
    val userId: Int = 0,
    val profileType: String = AppProfileKey.PROFILE_PRIMARY,
) {
    val appKey: String get() = AppProfileKey.format(pkg, userId)
    val isSecondaryProfile: Boolean get() = userId > 0
}

/**
 * Queries launchable installed Android apps across both the primary user (`userId = 0`) and
 * active secondary profiles (Dual Apps / App Cloner / Work Profile / Clone Profile via shell
 * daemon `TX_GET_SECONDARY_USER_APPS`, with `LauncherApps.getProfiles()` fallback when offline),
 * caching rasterized (and profile-badged) [ImageBitmap] icons for zero-jank 120fps rendering.
 */
object AppCatalogRepository {
    private const val TARGET_ICON_PX = 112
    private val iconCache = object : LruCache<String, ImageBitmap>(640) {}

    @Volatile
    private var cachedApps: List<InstalledAppEntry> = emptyList()

    @Volatile
    private var cachedWithDaemonSecondary: Boolean = false

    fun formatProfileBadge(ctx: Context, profileType: String, userId: Int): String {
        if (userId <= 0) return ""
        return when (AppProfileKey.normalizeProfileType(profileType, userId)) {
            AppProfileKey.PROFILE_CLONE -> ctx.getString(R.string.profile_badge_clone)
            AppProfileKey.PROFILE_WORK -> ctx.getString(R.string.profile_badge_work)
            AppProfileKey.PROFILE_PRIVATE -> ctx.getString(R.string.profile_badge_private)
            else -> ctx.getString(R.string.profile_badge_secondary, userId)
        }
    }

    suspend fun loadLaunchableApps(ctx: Context, forceReload: Boolean = false): List<InstalledAppEntry> {
        val daemonReady = BridgeClient.isBinderAliveFast()
        if (!forceReload && cachedApps.isNotEmpty() && (!daemonReady || cachedWithDaemonSecondary)) {
            return cachedApps
        }
        return withContext(Dispatchers.IO) {
            val appCtx = ctx.applicationContext
            val pm = appCtx.packageManager
            val selfPkg = appCtx.packageName
            val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val resolved = pm.queryIntentActivities(query, 0)
            val collator = Collator.getInstance(Locale.getDefault())
            val seenKeys = HashSet<String>()
            val out = ArrayList<InstalledAppEntry>(resolved.size + 16)

            for (ri in resolved) {
                val ai = ri.activityInfo ?: continue
                val pkg = ai.packageName ?: continue
                if (pkg == selfPkg || pkg == "com.android.systemui") continue
                val key = AppProfileKey.format(pkg, 0)
                if (!seenKeys.add(key)) continue
                val label = ri.loadLabel(pm).toString().trim().ifEmpty { pkg }
                out.add(
                    InstalledAppEntry(
                        pkg = pkg,
                        activityClass = ai.name.orEmpty(),
                        label = label,
                        userId = 0,
                        profileType = AppProfileKey.PROFILE_PRIMARY,
                    ),
                )
            }

            // Secondary profiles (Dual Apps user 999 / 95 / 128, Work Profile, Clone Profile, Private Space):
            // 1. Prefer shell daemon uid=2000 scan (sees OEM dual-app user 999 hidden from non-default launchers).
            val daemonUsed = BridgeClient.isBinderAliveFast()
            val daemonSecondary = if (daemonUsed) {
                runCatching { AppProfileKey.parseSecondaryAppsJson(BridgeClient.fetchSecondaryUserAppsJson()) }
                    .getOrDefault(emptyList())
            } else {
                emptyList()
            }
            for (sec in daemonSecondary) {
                if (sec.pkg == selfPkg || sec.pkg == "com.android.systemui") continue
                if (!seenKeys.add(sec.appKey)) continue
                out.add(
                    InstalledAppEntry(
                        pkg = sec.pkg,
                        activityClass = sec.activityClass,
                        label = sec.label,
                        userId = sec.userId,
                        profileType = sec.profileType,
                    ),
                )
            }

            // 2. Fallback / supplement via standard AOSP LauncherApps.getProfiles() when daemon is offline
            //    or for any standard managed/clone profile exposed directly to the app.
            if (!daemonUsed || daemonSecondary.isEmpty()) {
                for (entry in querySecondaryProfilesViaLauncherApps(appCtx, selfPkg)) {
                    if (seenKeys.add(entry.appKey)) {
                        out.add(entry)
                    }
                }
            }

            out.sortWith { a, b ->
                val cmpLabel = collator.compare(a.label, b.label)
                if (cmpLabel != 0) cmpLabel else a.userId.compareTo(b.userId)
            }
            cachedApps = out
            cachedWithDaemonSecondary = daemonUsed
            out
        }
    }

    private fun querySecondaryProfilesViaLauncherApps(
        ctx: Context,
        selfPkg: String,
    ): List<InstalledAppEntry> {
        val launcherApps = ctx.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps ?: return emptyList()
        val userManager = ctx.getSystemService(Context.USER_SERVICE) as? UserManager
        val primaryHandle = Process.myUserHandle()
        val profiles = runCatching { launcherApps.profiles }.getOrNull().orEmpty()
        if (profiles.size <= 1) return emptyList()

        val out = ArrayList<InstalledAppEntry>()
        for (handle in profiles) {
            if (handle == null || handle == primaryHandle) continue
            val uid = extractUserId(handle, userManager)
            if (uid <= 0) continue
            val isQuiet = userManager?.let { runCatching { it.isQuietModeEnabled(handle) }.getOrDefault(false) } == true
            if (isQuiet) continue
            val profileType = AppProfileKey.normalizeProfileType(
                if (uid == 999 || uid == 95 || uid == 128) AppProfileKey.PROFILE_CLONE else AppProfileKey.PROFILE_WORK,
                uid,
            )
            val activities = runCatching { launcherApps.getActivityList(null, handle) }.getOrNull().orEmpty()
            for (info in activities) {
                val comp = info.componentName ?: continue
                val pkg = comp.packageName
                if (pkg.isEmpty() || pkg == selfPkg || pkg == "com.android.systemui" || pkg == "com.android.settings") continue
                val label = info.label?.toString()?.trim().orEmpty().ifEmpty { pkg }
                out.add(
                    InstalledAppEntry(
                        pkg = pkg,
                        activityClass = comp.className.orEmpty(),
                        label = label,
                        userId = uid,
                        profileType = profileType,
                    ),
                )
            }
        }
        return out
    }

    private fun extractUserId(handle: UserHandle, userManager: UserManager?): Int {
        // UserHandle.hashCode() equals mHandle (the integer userId) on AOSP Android 5–17.
        val fromHash = handle.hashCode()
        if (fromHash in 1..9999) return fromHash
        val fromSerial = userManager?.let { runCatching { it.getSerialNumberForUser(handle).toInt() }.getOrDefault(-1) } ?: -1
        return fromSerial.coerceAtLeast(0)
    }

    /** O(1) non-blocking memory lookup safe to call directly during Compose scroll frames. */
    fun getCachedIcon(pkg: String, userId: Int = 0): ImageBitmap? {
        if (pkg.isEmpty()) return null
        return iconCache.get(AppProfileKey.format(pkg, userId))
    }

    /** Decodes and caches an app icon strictly on [Dispatchers.IO] without ever blocking UI frames. */
    suspend fun loadAppIconAsync(
        ctx: Context,
        pkg: String,
        userId: Int = 0,
        profileType: String = "",
    ): ImageBitmap? {
        if (pkg.isEmpty()) return null
        val key = AppProfileKey.format(pkg, userId)
        iconCache.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            getAppIcon(ctx, pkg, userId, profileType)
        }
    }

    /** Background pre-warmer so opening & scrolling the App Drawer hits 100% RAM cache at 120fps. */
    suspend fun prewarmIcons(ctx: Context, apps: List<InstalledAppEntry>) = withContext(Dispatchers.IO) {
        val appCtx = ctx.applicationContext
        for (entry in apps) {
            if (iconCache.get(entry.appKey) == null) {
                getAppIcon(appCtx, entry.pkg, entry.userId, entry.profileType)
            }
        }
    }

    fun getAppIcon(
        ctx: Context,
        pkg: String,
        userId: Int = 0,
        profileType: String = "",
    ): ImageBitmap? {
        if (pkg.isEmpty()) return null
        val safeUserId = userId.coerceAtLeast(0)
        val cacheKey = AppProfileKey.format(pkg, safeUserId)
        iconCache.get(cacheKey)?.let { return it }
        return try {
            val appCtx = ctx.applicationContext
            val drawable = resolveBaseIconDrawable(appCtx, pkg, safeUserId) ?: return null
            val bmp = drawableToBitmap(drawable, TARGET_ICON_PX) ?: return null
            val finalBmp = if (safeUserId > 0) {
                overlayProfileBadge(bmp, AppProfileKey.normalizeProfileType(profileType, safeUserId))
            } else {
                bmp
            }
            finalBmp.asImageBitmap().also { iconCache.put(cacheKey, it) }
        } catch (_: Throwable) {
            null
        }
    }

    private fun resolveBaseIconDrawable(ctx: Context, pkg: String, userId: Int): Drawable? {
        runCatching { ctx.packageManager.getApplicationIcon(pkg) }.getOrNull()?.let { return it }
        if (userId > 0) {
            // Work-profile-only apps not installed in user 0 can still resolve via LauncherApps.
            val launcherApps = ctx.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
            val userManager = ctx.getSystemService(Context.USER_SERVICE) as? UserManager
            if (launcherApps != null) {
                val matchHandle = runCatching { launcherApps.profiles }.getOrNull().orEmpty()
                    .firstOrNull { extractUserId(it, userManager) == userId }
                if (matchHandle != null) {
                    val info = runCatching { launcherApps.getActivityList(pkg, matchHandle).firstOrNull() }.getOrNull()
                    if (info != null) {
                        return runCatching { info.getIcon(0) }.getOrNull()
                    }
                }
            }
        }
        return null
    }

    private fun drawableToBitmap(drawable: Drawable, targetPx: Int): Bitmap? {
        if (drawable is BitmapDrawable && drawable.bitmap != null && !drawable.bitmap.isRecycled) {
            val src = drawable.bitmap
            if (src.width <= targetPx && src.height <= targetPx) {
                return src.copy(Bitmap.Config.ARGB_8888, true) ?: src
            }
            return Bitmap.createScaledBitmap(src, targetPx, targetPx, true)
        }
        val w = drawable.intrinsicWidth.coerceIn(48, targetPx)
        val h = drawable.intrinsicHeight.coerceIn(48, targetPx)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, w, h)
        drawable.draw(canvas)
        return bmp
    }

    /**
     * Draws a crisp native corner profile badge (amber dual-clone glyph, blue work briefcase, or
     * purple profile badge) onto secondary-profile app icons so clones are unmistakable at 120fps.
     */
    private fun overlayProfileBadge(base: Bitmap, profileType: String): Bitmap {
        val out = if (base.isMutable && base.config == Bitmap.Config.ARGB_8888) {
            base
        } else {
            base.copy(Bitmap.Config.ARGB_8888, true) ?: return base
        }
        val w = out.width.toFloat()
        val h = out.height.toFloat()
        val size = minOf(w, h)
        val radius = (size * 0.21f).coerceAtLeast(10f)
        val cx = w - radius - size * 0.03f
        val cy = h - radius - size * 0.03f

        val badgeColor = when (profileType) {
            AppProfileKey.PROFILE_CLONE -> 0xFFF59E0B.toInt()   // Warm amber
            AppProfileKey.PROFILE_WORK -> 0xFF0A84FF.toInt()    // System blue
            AppProfileKey.PROFILE_PRIVATE -> 0xFF8B5CF6.toInt() // Violet
            else -> 0xFF14B8A6.toInt()                          // Teal
        }

        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        // Outer white ring for sharp legibility against any icon color
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        canvas.drawCircle(cx, cy, radius + size * 0.025f, paint)

        // Colored badge disc
        paint.color = badgeColor
        canvas.drawCircle(cx, cy, radius, paint)

        // Crisp inner glyph
        paint.color = Color.WHITE
        when (profileType) {
            AppProfileKey.PROFILE_CLONE -> {
                // Interlocking dual squares symbolizing app clone
                val sq = radius * 0.62f
                val corner = sq * 0.24f
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = (radius * 0.17f).coerceAtLeast(1.8f)
                val back = RectF(cx - sq * 0.72f, cy - sq * 0.72f, cx + sq * 0.28f, cy + sq * 0.28f)
                canvas.drawRoundRect(back, corner, corner, paint)
                paint.style = Paint.Style.FILL
                val front = RectF(cx - sq * 0.16f, cy - sq * 0.16f, cx + sq * 0.78f, cy + sq * 0.78f)
                canvas.drawRoundRect(front, corner, corner, paint)
            }
            AppProfileKey.PROFILE_WORK -> {
                // Minimal work briefcase glyph
                val bw = radius * 0.96f
                val bh = radius * 0.68f
                val body = RectF(cx - bw / 2f, cy - bh * 0.34f, cx + bw / 2f, cy + bh * 0.66f)
                canvas.drawRoundRect(body, radius * 0.16f, radius * 0.16f, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = (radius * 0.15f).coerceAtLeast(1.6f)
                val handle = RectF(cx - bw * 0.24f, cy - bh * 0.66f, cx + bw * 0.24f, cy - bh * 0.26f)
                canvas.drawRoundRect(handle, radius * 0.12f, radius * 0.12f, paint)
            }
            else -> {
                // Clean profile dot + arc
                canvas.drawCircle(cx, cy - radius * 0.18f, radius * 0.24f, paint)
                val body = RectF(cx - radius * 0.44f, cy + radius * 0.14f, cx + radius * 0.44f, cy + radius * 0.56f)
                canvas.drawRoundRect(body, radius * 0.22f, radius * 0.22f, paint)
            }
        }
        return out
    }
}
