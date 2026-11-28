package io.github.lengsukq.opaldesk.external

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.media.MediaRouter
import android.provider.Settings
import android.view.Display
import android.widget.Toast
import java.lang.reflect.Method

/**
 * Pure AOSP helpers for detecting system Wireless Displays (Miracast / Wi-Fi Display) via
 * [DisplayManager.DISPLAY_CATEGORY_PRESENTATION] and [MediaRouter.ROUTE_TYPE_LIVE_VIDEO], and
 * opening the device's system Wireless Cast settings without any OEM private APIs.
 */
object WirelessDisplayHelper {

    private const val ACTION_WIFI_DISPLAY_SETTINGS = "android.settings.WIFI_DISPLAY_SETTINGS"

    @Volatile
    private var getTypeChecked = false

    @Volatile
    private var getTypeMethod: Method? = null

    @Volatile
    private var modeSyntheticChecked = false

    @Volatile
    private var modeIsSyntheticMethod: Method? = null

    @Volatile
    private var modeGetFlagsMethod: Method? = null

    private const val DISPLAY_MODE_FLAG_ANISOTROPY_CORRECTION = 1 shl 2

    fun isModeSyntheticOrAnisotropy(mode: Display.Mode?): Boolean {
        if (mode == null) return false
        if (!modeSyntheticChecked) {
            modeIsSyntheticMethod = runCatching { Display.Mode::class.java.getMethod("isSynthetic") }.getOrNull()
            modeGetFlagsMethod = runCatching { Display.Mode::class.java.getMethod("getFlags") }.getOrNull()
            modeSyntheticChecked = true
        }
        val isSynth = modeIsSyntheticMethod?.let { m ->
            runCatching { m.invoke(mode) == true }.getOrDefault(false)
        } == true
        if (isSynth) return true
        val flags = modeGetFlagsMethod?.let { m ->
            runCatching { (m.invoke(mode) as? Number)?.toInt() ?: 0 }.getOrDefault(0)
        } ?: 0
        return (flags and DISPLAY_MODE_FLAG_ANISOTROPY_CORRECTION) != 0
    }

    @Suppress("DEPRECATION")
    fun queryPrimaryDisplaySizePx(dm: DisplayManager?): Pair<Int, Int> {
        val d0 = runCatching { dm?.getDisplay(Display.DEFAULT_DISPLAY) }.getOrNull() ?: return 0 to 0
        val pt = android.graphics.Point()
        runCatching { d0.getRealSize(pt) }
        if (pt.x >= 320 && pt.y >= 320) return pt.x to pt.y
        val m = d0.mode
        return if (m != null && m.physicalWidth >= 320 && m.physicalHeight >= 320) {
            m.physicalWidth to m.physicalHeight
        } else {
            0 to 0
        }
    }

    @Suppress("DEPRECATION")
    fun queryDisplayRealDpi(display: Display?): Pair<Float, Float> {
        if (display == null) return 0f to 0f
        val metrics = android.util.DisplayMetrics()
        runCatching { display.getRealMetrics(metrics) }
        return metrics.xdpi to metrics.ydpi
    }

    data class MediaRouterPresentationInfo(
        val display: Display,
        val routeName: String,
    )

    data class InspectedDisplay(
        val display: Display,
        val displayName: String,
        val displayType: Int,
        val transport: ExternalDisplayTransport,
        val isPresentationCapable: Boolean,
    )

    /**
     * Safely inspects [Display]'s AOSP `getType()` integer when accessible, falling back cleanly
     * to [ExternalDisplayPolicy.TYPE_UNKNOWN] on strict hidden-API Android releases.
     */
    fun queryDisplayTypeSafe(display: Display): Int {
        if (!getTypeChecked) {
            getTypeMethod = runCatching { Display::class.java.getMethod("getType") }.getOrNull()
            getTypeChecked = true
        }
        val method = getTypeMethod ?: return ExternalDisplayPolicy.TYPE_UNKNOWN
        return runCatching { (method.invoke(display) as? Int) ?: ExternalDisplayPolicy.TYPE_UNKNOWN }
            .getOrDefault(ExternalDisplayPolicy.TYPE_UNKNOWN)
    }

    /**
     * Queries the active public [MediaRouter.ROUTE_TYPE_LIVE_VIDEO] presentation display
     * (standard AOSP API for Miracast / Wi-Fi Display TVs).
     */
    @Suppress("DEPRECATION")
    fun queryMediaRouterPresentation(ctx: Context?): MediaRouterPresentationInfo? {
        val appCtx = ctx?.applicationContext ?: return null
        return runCatching {
            val mr = appCtx.getSystemService(Context.MEDIA_ROUTER_SERVICE) as? MediaRouter ?: return null
            val route = mr.getSelectedRoute(MediaRouter.ROUTE_TYPE_LIVE_VIDEO) ?: return null
            val disp = route.presentationDisplay ?: return null
            val routeName = route.getName(appCtx)?.toString()?.trim().orEmpty()
            MediaRouterPresentationInfo(display = disp, routeName = routeName)
        }.getOrNull()
    }

    /**
     * Scans all external display candidates by merging:
     * 1. [DisplayManager.getDisplays] with [DisplayManager.DISPLAY_CATEGORY_PRESENTATION]
     * 2. [MediaRouter.ROUTE_TYPE_LIVE_VIDEO] selected route presentation display
     * 3. All active displays from [DisplayManager.getDisplays]
     */
    fun inspectExternalDisplays(
        ctx: Context?,
        dm: DisplayManager?,
        activeRemoteDesktopDisplayId: Int = -1,
    ): List<InspectedDisplay> {
        if (dm == null) return emptyList()
        val presentationIds = HashSet<Int>()
        val byId = LinkedHashMap<Int, Display>()

        runCatching { dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION) }
            .getOrNull()
            .orEmpty()
            .forEach { d ->
                presentationIds.add(d.displayId)
                byId[d.displayId] = d
            }

        val mrPres = queryMediaRouterPresentation(ctx)
        if (mrPres != null) {
            byId.putIfAbsent(mrPres.display.displayId, mrPres.display)
        }

        runCatching { dm.displays }
            .getOrNull()
            .orEmpty()
            .forEach { d ->
                byId.putIfAbsent(d.displayId, d)
            }

        val results = ArrayList<InspectedDisplay>(byId.size)
        for ((id, d) in byId) {
            val isMr = mrPres != null && mrPres.display.displayId == id
            val isPresCat = presentationIds.contains(id)
            val dispType = queryDisplayTypeSafe(d)
            val preferredName = when {
                isMr && mrPres.routeName.isNotBlank() -> mrPres.routeName
                !d.name.isNullOrBlank() -> d.name.trim()
                else -> "External Display #$id"
            }
            val transport = ExternalDisplayPolicy.classifyExternalDisplay(
                displayId = id,
                isValid = d.isValid,
                name = preferredName,
                flags = d.flags,
                state = d.state,
                displayType = dispType,
                isPresentationCategory = isPresCat,
                isMediaRouterPresentation = isMr,
                activeRemoteDesktopDisplayId = activeRemoteDesktopDisplayId,
            ) ?: continue

            val presentationCapable = ExternalDisplayPolicy.isPublicPresentationDisplay(d.flags)
            results.add(
                InspectedDisplay(
                    display = d,
                    displayName = preferredName,
                    displayType = dispType,
                    transport = transport,
                    isPresentationCapable = presentationCapable,
                ),
            )
        }

        return results.sortedWith(
            compareBy<InspectedDisplay> {
                when (it.transport) {
                    ExternalDisplayTransport.WIRED -> 0
                    ExternalDisplayTransport.WIRELESS_TV -> 1
                    ExternalDisplayTransport.OTHER_EXTERNAL -> 2
                }
            }.thenBy { it.display.displayId },
        )
    }

    /**
     * Opens the system Wireless Cast / Miracast settings page using standard AOSP intent fallbacks.
     */
    fun openSystemWirelessCastSettings(ctx: Context): Boolean {
        val actions = listOf(
            Settings.ACTION_CAST_SETTINGS,
            ACTION_WIFI_DISPLAY_SETTINGS,
            Settings.ACTION_DISPLAY_SETTINGS,
        )
        for (action in actions) {
            val intent = Intent(action).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val launched = runCatching {
                ctx.startActivity(intent)
                true
            }.getOrDefault(false)
            if (launched) {
                Toast.makeText(
                    ctx,
                    "连接电视后返回 OpalDesk，支持独立副屏的设备将自动投射 DeX 桌面",
                    Toast.LENGTH_LONG,
                ).show()
                return true
            }
        }
        Toast.makeText(ctx, "请在系统「设置 → 更多连接 / 投屏」中连接电视", Toast.LENGTH_LONG).show()
        return false
    }

    fun formatTransportLabel(transport: ExternalDisplayTransport): String = when (transport) {
        ExternalDisplayTransport.WIRED -> "有线外接屏"
        ExternalDisplayTransport.WIRELESS_TV -> "无线电视"
        ExternalDisplayTransport.OTHER_EXTERNAL -> "其他副屏"
    }
}
