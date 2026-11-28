package io.github.lengsukq.opalcast.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.lengsukq.opalcast.R
import io.github.lengsukq.opalcast.compat.AndroidApiCompat
import io.github.lengsukq.opalcast.net.OpalCastClient
import io.github.lengsukq.opalcast.prefs.ReceiverPrefs

internal data class CastViewerChromeViews(
    val collapsedHandle: TextView,
    val expandedToolbar: HorizontalScrollView,
    val statusLabel: TextView,
    val modeBtn: TextView,
    val floatMirrorBtn: TextView,
    val bitrateBtn: TextView,
    val bitratePickerBar: HorizontalScrollView,
    val blackoutBtn: TextView,
    val imeBtn: TextView,
    val micBtn: TextView,
    val camBtn: TextView,
    val flipCamBtn: TextView,
    val fullscreenExitChip: TextView,
    val composingPill: TextView,
    val overlayCard: LinearLayout,
    val overlayTitle: TextView,
    val overlaySubtitle: TextView,
    val overlayRetryBtn: TextView,
)

internal data class CastViewerToolbarCallbacks(
    val onScheduleAutoCollapse: (Long) -> Unit,
    val onExpandToolbar: () -> Unit,
    val onCollapseToolbar: () -> Unit,
    val onBack: () -> Unit,
    val onHome: () -> Unit,
    val onToggleDisplayMode: () -> Unit,
    val onPopOutMirror: () -> Unit,
    val onSelectBitrateKbps: (Int) -> Unit,
    val onToggleBlackout: () -> Unit,
    val onToggleIme: () -> Unit,
    val onToggleMic: () -> Unit,
    val onToggleCamera: () -> Unit,
    val onFlipCamera: () -> Unit,
    val onOpenAppPicker: () -> Unit,
    val onRefreshKeyFrame: () -> Unit,
    val onDisconnect: () -> Unit,
    val onExitFullscreen: () -> Unit,
    val onRetryConnect: () -> Unit,
)

/**
 * Pure Framework Views fullscreen toolbar, overlay card, and status state helper for [CastViewerActivity].
 */
internal object CastViewerToolbarHelper {
    fun attachChromeViews(
        activity: Activity,
        rootFrame: FrameLayout,
        initialPeerName: String,
        callbacks: CastViewerToolbarCallbacks,
    ): CastViewerChromeViews {
        val pal = OpalCastTheme.resolve(activity)
        val dp = { v: Int -> OpalCastTheme.dp(activity, v) }

        val collapsedHandle = TextView(activity).apply {
            text = activity.getString(R.string.viewer_toolbar_handle)
            setTextColor(0xE6E0FBF5.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            background = OpalCastTheme.roundedDrawable(activity, 0xCC091820.toInt(), 99, 0x552DD4BF, 1f)
            setPadding(dp(14), dp(5), dp(14), dp(5))
            visibility = View.GONE
            setOnClickListener { callbacks.onExpandToolbar() }
        }
        rootFrame.addView(
            collapsedHandle,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            ).apply { topMargin = dp(10) },
        )

        val barInner = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = OpalCastTheme.roundedDrawable(activity, 0xE60B1D26.toInt(), 18, 0x552DD4BF, 1f)
            setPadding(dp(10), dp(7), dp(10), dp(7))
        }

        val statusLabel = TextView(activity).apply {
            text = initialPeerName
            setTextColor(0xFFF0FDF9.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(4), 0, dp(8), 0)
        }
        barInner.addView(statusLabel)

        fun addToolChip(
            label: String,
            tintBg: Int = 0x1FFFFFFF,
            tintText: Int = 0xFFE6FFFA.toInt(),
            onClick: () -> Unit,
        ): TextView {
            val tv = TextView(activity).apply {
                text = label
                setTextColor(tintText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                gravity = Gravity.CENTER
                background = OpalCastTheme.roundedDrawable(activity, tintBg, 10, 0x2EFFFFFF, 0.8f)
                setPadding(dp(10), dp(6), dp(10), dp(6))
                setOnClickListener {
                    callbacks.onScheduleAutoCollapse(5_000L)
                    onClick()
                }
            }
            barInner.addView(
                tv,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    leftMargin = dp(6)
                },
            )
            return tv
        }

        addToolChip(activity.getString(R.string.common_back), onClick = callbacks.onBack)
        addToolChip(activity.getString(R.string.common_home), onClick = callbacks.onHome)
        val modeBtn = addToolChip(activity.getString(R.string.viewer_chip_to_mirror), onClick = callbacks.onToggleDisplayMode)
        val floatMirrorBtn = addToolChip(activity.getString(R.string.viewer_chip_float_window), onClick = callbacks.onPopOutMirror)
        lateinit var bitratePickerBar: HorizontalScrollView
        val bitrateBtn = addToolChip("码率: 自动") {
            bitratePickerBar.visibility = if (bitratePickerBar.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        val blackoutBtn = addToolChip(activity.getString(R.string.viewer_chip_screen_off), onClick = callbacks.onToggleBlackout)
        val imeBtn = addToolChip(activity.getString(R.string.viewer_chip_keyboard), onClick = callbacks.onToggleIme)
        val micBtn = addToolChip(activity.getString(R.string.viewer_chip_mic), onClick = callbacks.onToggleMic)
        val camBtn = addToolChip(activity.getString(R.string.viewer_chip_camera), onClick = callbacks.onToggleCamera)
        val flipCamBtn = addToolChip(activity.getString(R.string.viewer_chip_flip_camera), onClick = callbacks.onFlipCamera)
            .apply { visibility = View.GONE }
        addToolChip(activity.getString(R.string.viewer_chip_app_window), onClick = callbacks.onOpenAppPicker)
        addToolChip(activity.getString(R.string.common_refresh), onClick = callbacks.onRefreshKeyFrame)
        addToolChip(activity.getString(R.string.viewer_chip_collapse), onClick = callbacks.onCollapseToolbar)
        addToolChip(
            activity.getString(R.string.viewer_chip_disconnect),
            tintBg = 0x38EF4444,
            tintText = 0xFFFCA5A5.toInt(),
            onClick = callbacks.onDisconnect,
        )

        val expandedToolbar = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            addView(barInner)
        }
        rootFrame.addView(
            expandedToolbar,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            ).apply {
                topMargin = dp(10)
                leftMargin = dp(12)
                rightMargin = dp(12)
            },
        )

        val bitrateRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = OpalCastTheme.roundedDrawable(activity, 0xE60B1D26.toInt(), 16, 0x552DD4BF, 1f)
            setPadding(dp(8), dp(5), dp(8), dp(5))
        }
        ReceiverPrefs.BITRATE_PRESETS_KBPS.forEachIndexed { idx, kbps ->
            val tv = TextView(activity).apply {
                text = if (kbps == 0) "自动" else "${kbps / 1000}M"
                setTextColor(0xFFE6FFFA.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                gravity = Gravity.CENTER
                background = OpalCastTheme.roundedDrawable(activity, 0x1FFFFFFF, 10, 0x2EFFFFFF, 0.8f)
                setPadding(dp(10), dp(5), dp(10), dp(5))
                setOnClickListener {
                    callbacks.onScheduleAutoCollapse(5_000L)
                    bitratePickerBar.visibility = View.GONE
                    callbacks.onSelectBitrateKbps(kbps)
                }
            }
            bitrateRow.addView(
                tv,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    if (idx > 0) leftMargin = dp(6)
                },
            )
        }
        bitratePickerBar = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
            addView(bitrateRow)
        }
        rootFrame.addView(
            bitratePickerBar,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            ).apply { topMargin = dp(56); leftMargin = dp(12); rightMargin = dp(12) },
        )

        val fullscreenExitChip = TextView(activity).apply {
            text = activity.getString(R.string.viewer_exit_fullscreen)
            setTextColor(0xFFF0FDF9.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            background = OpalCastTheme.roundedDrawable(activity, 0xD90D9488.toInt(), 99, 0x66FFFFFF, 1f)
            setPadding(dp(14), dp(7), dp(14), dp(7))
            visibility = View.GONE
            setOnClickListener { callbacks.onExitFullscreen() }
        }
        rootFrame.addView(
            fullscreenExitChip,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END,
            ).apply {
                topMargin = dp(60)
                rightMargin = dp(14)
            },
        )

        val composingPill = TextView(activity).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            background = OpalCastTheme.roundedDrawable(activity, 0xE60D9488.toInt(), 12, 0x66FFFFFF, 1f)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            visibility = View.GONE
        }
        rootFrame.addView(
            composingPill,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ).apply { bottomMargin = dp(18) },
        )

        val overlayCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = OpalCastTheme.roundedDrawable(activity, 0xEB0B1C24.toInt(), 20, pal.borderStrong, 1.1f)
            setPadding(dp(24), dp(22), dp(24), dp(22))
        }
        val overlayTitle = TextView(activity).apply {
            text = initialPeerName
            setTextColor(0xFFF0FDF9.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        val overlaySubtitle = TextView(activity).apply {
            text = activity.getString(R.string.viewer_connecting_tls)
            setTextColor(0xFFA7D3CC.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
        }
        val overlayBtnRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val overlayRetryBtn = OpalCastTheme.buildPillButton(
            activity,
            activity.getString(R.string.common_reconnect),
            pal.accent,
            Color.WHITE,
        ) { callbacks.onRetryConnect() }.apply { visibility = View.GONE }
        val backBtn = OpalCastTheme.buildPillButton(
            activity,
            activity.getString(R.string.viewer_back_home),
            0x22FFFFFF,
            0xFFE6FFFA.toInt(),
            0x33FFFFFF,
        ) { callbacks.onDisconnect() }
        overlayBtnRow.addView(
            overlayRetryBtn,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = dp(10)
            },
        )
        overlayBtnRow.addView(backBtn)

        overlayCard.addView(overlayTitle)
        overlayCard.addView(
            overlaySubtitle,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
                bottomMargin = dp(16)
            },
        )
        overlayCard.addView(overlayBtnRow)
        rootFrame.addView(
            overlayCard,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
        )

        AndroidApiCompat.observeTopCutoutInset(rootFrame) { cutoutTop ->
            val topMargin = maxOf(dp(14), cutoutTop + dp(8))
            (collapsedHandle.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                if (lp.topMargin != topMargin) {
                    lp.topMargin = topMargin
                    collapsedHandle.layoutParams = lp
                }
            }
            (expandedToolbar.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                if (lp.topMargin != topMargin) {
                    lp.topMargin = topMargin
                    expandedToolbar.layoutParams = lp
                }
            }
            (bitratePickerBar.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                val pickerTop = topMargin + dp(46)
                if (lp.topMargin != pickerTop) {
                    lp.topMargin = pickerTop
                    bitratePickerBar.layoutParams = lp
                }
            }
            (fullscreenExitChip.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                val chipTop = topMargin + dp(48)
                if (lp.topMargin != chipTop) {
                    lp.topMargin = chipTop
                    fullscreenExitChip.layoutParams = lp
                }
            }
        }

        return CastViewerChromeViews(
            collapsedHandle = collapsedHandle,
            expandedToolbar = expandedToolbar,
            statusLabel = statusLabel,
            modeBtn = modeBtn,
            floatMirrorBtn = floatMirrorBtn,
            bitrateBtn = bitrateBtn,
            bitratePickerBar = bitratePickerBar,
            blackoutBtn = blackoutBtn,
            imeBtn = imeBtn,
            micBtn = micBtn,
            camBtn = camBtn,
            flipCamBtn = flipCamBtn,
            fullscreenExitChip = fullscreenExitChip,
            composingPill = composingPill,
            overlayCard = overlayCard,
            overlayTitle = overlayTitle,
            overlaySubtitle = overlaySubtitle,
            overlayRetryBtn = overlayRetryBtn,
        )
    }

    fun refreshToolbarStates(
        activity: Activity,
        chrome: CastViewerChromeViews,
        displayMode: Int,
        remoteBlackout: Boolean,
        imeVis: Boolean,
        micEnabled: Boolean,
        cameraEnabled: Boolean,
        bitrateKbps: Int = 0,
    ) {
        val isMirror = (displayMode == OpalCastClient.DISPLAY_MODE_MIRROR)
        chrome.modeBtn.text = activity.getString(if (isMirror) R.string.viewer_chip_to_desktop else R.string.viewer_chip_to_mirror)
        chrome.floatMirrorBtn.visibility = if (isMirror) View.VISIBLE else View.GONE
        chrome.bitrateBtn.text = if (bitrateKbps <= 0) "码率: 自动" else "码率: ${bitrateKbps / 1000}M"
        chrome.bitrateBtn.background = OpalCastTheme.roundedDrawable(
            activity,
            if (bitrateKbps > 0) 0x4514B8A6 else 0x1FFFFFFF,
            10,
            if (bitrateKbps > 0) 0x882DD4BF.toInt() else 0x2EFFFFFF,
            0.8f,
        )
        chrome.blackoutBtn.text = activity.getString(if (remoteBlackout) R.string.viewer_chip_screen_on else R.string.viewer_chip_screen_off)
        chrome.blackoutBtn.background = OpalCastTheme.roundedDrawable(
            activity,
            if (remoteBlackout) 0x45F59E0B else 0x1FFFFFFF,
            10,
            if (remoteBlackout) 0x88F59E0B.toInt() else 0x2EFFFFFF,
            0.8f,
        )
        chrome.imeBtn.text = activity.getString(if (imeVis) R.string.viewer_chip_hide_keyboard else R.string.viewer_chip_keyboard)
        chrome.micBtn.text = activity.getString(if (micEnabled) R.string.viewer_chip_mute_mic else R.string.viewer_chip_mic)
        chrome.micBtn.background = OpalCastTheme.roundedDrawable(
            activity,
            if (micEnabled) 0x4514B8A6 else 0x1FFFFFFF,
            10,
            if (micEnabled) 0x882DD4BF.toInt() else 0x2EFFFFFF,
            0.8f,
        )
        chrome.camBtn.text = activity.getString(if (cameraEnabled) R.string.viewer_chip_stop_camera else R.string.viewer_chip_camera)
        chrome.flipCamBtn.visibility = if (cameraEnabled) View.VISIBLE else View.GONE
    }
}
