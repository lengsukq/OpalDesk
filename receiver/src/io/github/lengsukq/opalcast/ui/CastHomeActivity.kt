package io.github.lengsukq.opalcast.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import io.github.lengsukq.opalcast.BuildConfig
import io.github.lengsukq.opalcast.R
import io.github.lengsukq.opalcast.compat.AndroidApiCompat
import io.github.lengsukq.opalcast.net.DiscoveredHostPeer
import io.github.lengsukq.opalcast.net.OpalCastClient
import io.github.lengsukq.opalcast.net.PeerDiscoveryManager
import io.github.lengsukq.opalcast.net.UsbTetherDiscovery
import io.github.lengsukq.opalcast.policy.ReceiverPolicy
import io.github.lengsukq.opalcast.policy.UsbTetherPolicy
import io.github.lengsukq.opalcast.prefs.ReceiverLocalePolicy
import io.github.lengsukq.opalcast.prefs.ReceiverPrefs

/**
 * OpalCast Companion Receiver Home Console (Android 5.0–17, universal 32/64-bit).
 * Pure Android Framework Views with Emerald-Teal Liquid Glass visual identity.
 */
class CastHomeActivity : Activity() {
    companion object {
        private const val REQ_LOCAL_NET = 201
    }

    private lateinit var rootScroll: ScrollView
    private lateinit var contentColumn: LinearLayout
    private lateinit var localNetBanner: LinearLayout
    private lateinit var activeFloatingBanner: LinearLayout
    private lateinit var activeFloatingTitle: TextView
    private lateinit var peersContainer: LinearLayout
    private lateinit var usbSectionCard: LinearLayout
    private lateinit var usbPeersContainer: LinearLayout
    private lateinit var usbScanStatus: TextView
    private lateinit var manualInput: EditText
    private lateinit var recentContainer: LinearLayout
    private lateinit var codecStatusText: TextView

    private var discoveredPeers: List<DiscoveredHostPeer> = emptyList()
    private var usbPeers: List<UsbTetherPolicy.DiscoveredUsbTetherPeer> = emptyList()
    private var usbScanning = false
    private var activeFloating: FloatingCastService.ActiveFloatingInfo? = null

    private val peerListener: (List<DiscoveredHostPeer>) -> Unit = { list ->
        discoveredPeers = list
        renderDiscoveredPeers()
    }
    private val usbListener: (List<UsbTetherPolicy.DiscoveredUsbTetherPeer>, Boolean) -> Unit = { list, scanning ->
        usbPeers = list
        usbScanning = scanning
        renderUsbPeers()
    }
    private val floatingListener: (FloatingCastService.ActiveFloatingInfo?) -> Unit = { info ->
        activeFloating = info
        renderActiveFloatingBanner()
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ReceiverLocalePolicy.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildContent()
    }

    override fun onStart() {
        super.onStart()
        PeerDiscoveryManager.addListener(peerListener)
        UsbTetherDiscovery.addListener(usbListener)
        FloatingCastService.addStateListener(floatingListener)
        refreshDiscoveryState()
    }

    override fun onStop() {
        PeerDiscoveryManager.removeListener(peerListener)
        UsbTetherDiscovery.removeListener(usbListener)
        FloatingCastService.removeStateListener(floatingListener)
        PeerDiscoveryManager.stopBrowsing()
        if (!ReceiverPrefs.isUsbTetherEnabled(this)) {
            UsbTetherDiscovery.stop()
        }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        val pal = OpalCastTheme.resolve(this)
        AndroidApiCompat.applyHomeEdgeToEdge(this, pal.isDark, pal.bgCanvas)
        renderRecentPeers()
        refreshDiscoveryState()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            val pal = OpalCastTheme.resolve(this)
            AndroidApiCompat.applyHomeEdgeToEdge(this, pal.isDark, pal.bgCanvas)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        rebuildUi()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCAL_NET) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (granted) {
                refreshDiscoveryState()
            } else {
                Toast.makeText(this, getString(R.string.home_local_net_denied_toast), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun refreshDiscoveryState() {
        val hasLocalNet = AndroidApiCompat.hasLocalNetworkPermission(this)
        localNetBanner.visibility = if (hasLocalNet) View.GONE else View.VISIBLE
        if (hasLocalNet) {
            PeerDiscoveryManager.startBrowsing(this)
        }
        val usbOn = ReceiverPrefs.isUsbTetherEnabled(this)
        usbSectionCard.visibility = if (usbOn) View.VISIBLE else View.GONE
        if (usbOn) {
            UsbTetherDiscovery.start(this)
        } else {
            UsbTetherDiscovery.stop()
        }
    }

    private fun buildContent() {
        val pal = OpalCastTheme.resolve(this)

        rootScroll = ScrollView(this).apply {
            setBackgroundColor(pal.bgCanvas)
            isFillViewport = true
            // Keep clipToPadding = true so scrolled cards never collide visually with status bar icons.
            clipToPadding = true
        }
        AndroidApiCompat.applySafeAreaPadding(rootScroll)
        contentColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(28))
        }

        // ---- Hero Brand Card ----
        val heroCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = OpalCastTheme.roundedDrawable(this@CastHomeActivity, pal.bgCard, 22, pal.borderStrong, 1.1f)
            setPadding(dp(18), dp(18), dp(18), dp(16))
        }
        val brandRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val appIcon = ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        brandRow.addView(appIcon, LinearLayout.LayoutParams(dp(54), dp(54)).apply { rightMargin = dp(14) })

        val brandTextCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val titleLine = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleLine.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            setTextColor(pal.inkPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })
        titleLine.addView(
            OpalCastTheme.buildBadge(this, "v${BuildConfig.VERSION_NAME}", pal.chipBg, pal.inkSecondary, pal.border),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = dp(8)
            },
        )
        titleLine.addView(
            OpalCastTheme.buildBadge(this, getString(R.string.home_join_qq_badge), pal.secondarySoft, pal.secondaryAccent, pal.secondaryAccent).apply {
                isClickable = true
                isFocusable = true
                setOnClickListener { joinCastCommunityQqGroup(this@CastHomeActivity) }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = dp(6)
            },
        )
        brandTextCol.addView(titleLine)
        brandTextCol.addView(TextView(this).apply {
            text = getString(R.string.home_subtitle)
            setTextColor(pal.inkSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(2)
        })
        brandRow.addView(brandTextCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        heroCard.addView(brandRow)

        val hwSummary = AndroidApiCompat.probeLocalHardwareDecoders()
        val badgesScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val badgesRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val abiInfo = AndroidApiCompat.primaryAbiInfo()
        val abiLabel = when {
            abiInfo == null -> getString(R.string.home_abi_unknown)
            abiInfo.is64Bit -> getString(R.string.home_abi_64_fmt, abiInfo.abi)
            else -> getString(R.string.home_abi_32_fmt, abiInfo.abi)
        }
        val apiLabel = getString(R.string.home_api_badge_fmt, Build.VERSION.RELEASE, Build.VERSION.SDK_INT)
        val codecTag = when {
            hwSummary.hasHardwareHevc -> getString(R.string.home_codec_badge_hevc_h264)
            hwSummary.hasHardwareAvc -> getString(R.string.home_codec_badge_h264_fallback)
            else -> getString(R.string.home_codec_badge_sw)
        }
        for ((i, pair) in listOf(
            abiLabel to Pair(pal.accentSoft, pal.accent),
            apiLabel to Pair(pal.secondarySoft, pal.secondaryAccent),
            codecTag to Pair(pal.chipBg, pal.inkSecondary),
        ).withIndex()) {
            badgesRow.addView(
                OpalCastTheme.buildBadge(this, pair.first, pair.second.first, pair.second.second),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    if (i > 0) leftMargin = dp(7)
                },
            )
        }
        badgesScroll.addView(badgesRow)
        heroCard.addView(badgesScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        })
        contentColumn.addView(heroCard)

        // ---- Android 17 Local Network Permission Banner ----
        localNetBanner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = OpalCastTheme.roundedDrawable(this@CastHomeActivity, pal.amberSoft, 16, pal.amber, 1f)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            visibility = View.GONE
        }
        localNetBanner.addView(
            TextView(this).apply {
                text = getString(R.string.home_local_net_banner)
                setTextColor(pal.inkPrimary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(10) },
        )
        localNetBanner.addView(
            OpalCastTheme.buildPillButton(this, getString(R.string.home_local_net_grant), pal.amber, Color.WHITE) {
                AndroidApiCompat.requestRuntimePermissions(
                    this,
                    arrayOf(AndroidApiCompat.PERMISSION_ACCESS_LOCAL_NETWORK),
                    REQ_LOCAL_NET,
                )
            },
        )
        contentColumn.addView(localNetBanner, cardMargins())

        // ---- Active Floating Window Banner ----
        activeFloatingBanner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = OpalCastTheme.roundedDrawable(this@CastHomeActivity, pal.accentSoft, 16, pal.accentBorder, 1f)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            visibility = View.GONE
        }
        activeFloatingTitle = TextView(this).apply {
            setTextColor(pal.inkPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        activeFloatingBanner.addView(activeFloatingTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        activeFloatingBanner.addView(
            OpalCastTheme.buildPillButton(this, getString(R.string.home_floating_close), pal.dangerSoft, pal.danger, pal.danger) {
                FloatingCastService.stop(this)
            },
        )
        contentColumn.addView(activeFloatingBanner, cardMargins())

        // ---- Discovered Nearby Host Devices Card ----
        val discoverCard = buildSectionCard(pal, getString(R.string.home_discover_title), getString(R.string.home_discover_subtitle)) { headerRow ->
            headerRow.addView(
                OpalCastTheme.buildPillButton(this, getString(R.string.common_refresh), pal.chipBg, pal.inkSecondary, pal.border, bold = false) {
                    PeerDiscoveryManager.restartBrowsing(this)
                    Toast.makeText(this, getString(R.string.home_discover_rescan_toast), Toast.LENGTH_SHORT).show()
                },
            )
        }
        peersContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        discoverCard.addView(peersContainer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        })
        contentColumn.addView(discoverCard, cardMargins())

        // ---- Optional USB Tethering Beta Card ----
        usbSectionCard = buildSectionCard(pal, getString(R.string.home_usb_title), getString(R.string.home_usb_disclaimer)) { headerRow ->
            headerRow.addView(
                OpalCastTheme.buildPillButton(this, getString(R.string.home_usb_probe), pal.chipBg, pal.inkSecondary, pal.border, bold = false) {
                    UsbTetherDiscovery.triggerScanAsync()
                },
            )
        }
        usbScanStatus = TextView(this).apply {
            setTextColor(pal.inkMuted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        usbSectionCard.addView(usbScanStatus, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })
        usbPeersContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        usbSectionCard.addView(usbPeersContainer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })
        usbSectionCard.visibility = View.GONE
        contentColumn.addView(usbSectionCard, cardMargins())

        // ---- Manual IP:Port Connect Card ----
        val manualCard = buildSectionCard(pal, getString(R.string.home_manual_title), getString(R.string.home_manual_subtitle))
        manualInput = EditText(this).apply {
            hint = getString(R.string.home_manual_hint)
            setHintTextColor(pal.inkMuted)
            setTextColor(pal.inkPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            isSingleLine = true
            background = OpalCastTheme.roundedDrawable(this@CastHomeActivity, pal.inputBg, 12, pal.border, 1f)
            setPadding(dp(12), dp(11), dp(12), dp(11))
        }
        manualCard.addView(manualInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        })

        val manualActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        manualActions.addView(
            OpalCastTheme.buildPillButton(this, getString(R.string.home_action_cast_desktop), pal.accent, Color.WHITE) {
                connectManual(OpalCastClient.DISPLAY_MODE_DEX)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) },
        )
        manualActions.addView(
            OpalCastTheme.buildPillButton(this, getString(R.string.home_action_mirror), pal.secondarySoft, pal.secondaryAccent, pal.secondaryAccent) {
                connectManual(OpalCastClient.DISPLAY_MODE_MIRROR)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) },
        )
        manualActions.addView(
            OpalCastTheme.buildPillButton(this, getString(R.string.home_action_app_window), pal.chipBg, pal.inkPrimary, pal.border) {
                openManualAppPicker()
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        manualCard.addView(manualActions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        })

        recentContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        manualCard.addView(recentContainer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        })
        contentColumn.addView(manualCard, cardMargins())

        // ---- Settings & Compatibility Card ----
        val settingsCard = buildSectionCard(pal, getString(R.string.home_settings_title), getString(R.string.home_settings_subtitle))

        codecStatusText = TextView(this).apply {
            setTextColor(pal.inkSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        settingsCard.addView(codecStatusText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        })
        settingsCard.addView(buildCodecSelectorRow(pal), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })

        settingsCard.addView(TextView(this).apply {
            text = "视频码率（当前：${ReceiverPrefs.formatBitrateLabel(ReceiverPrefs.getPreferredBitrateKbps(this@CastHomeActivity))}）"
            setTextColor(pal.inkSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(14)
        })
        settingsCard.addView(buildCastBitrateSelectorRow(this, pal) { rebuildUi() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })

        settingsCard.addView(TextView(this).apply {
            text = getString(R.string.home_theme_label)
            setTextColor(pal.inkSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(14)
        })
        settingsCard.addView(buildThemeSelectorRow(pal), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })

        settingsCard.addView(TextView(this).apply {
            text = getString(R.string.home_language_label)
            setTextColor(pal.inkSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(14)
        })
        settingsCard.addView(buildLanguageSelectorRow(pal), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })

        // USB Tether Beta Toggle
        val usbToggleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = OpalCastTheme.roundedDrawable(this@CastHomeActivity, pal.bgElevated, 12, pal.border, 0.8f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val usbLabelCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        usbLabelCol.addView(TextView(this).apply {
            text = getString(R.string.home_usb_toggle_title)
            setTextColor(pal.inkPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })
        usbLabelCol.addView(TextView(this).apply {
            text = getString(R.string.home_usb_toggle_subtitle)
            setTextColor(pal.inkMuted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
        })
        val usbOn = ReceiverPrefs.isUsbTetherEnabled(this)
        usbToggleRow.addView(usbLabelCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(10) })
        usbToggleRow.addView(
            OpalCastTheme.buildPillButton(
                this,
                getString(if (usbOn) R.string.common_on else R.string.common_off),
                if (usbOn) pal.accentSoft else pal.chipBg,
                if (usbOn) pal.accent else pal.inkSecondary,
                if (usbOn) pal.accentBorder else pal.border,
            ) {
                ReceiverPrefs.setUsbTetherEnabled(this, !usbOn)
                rebuildUi()
            },
        )
        settingsCard.addView(usbToggleRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(14)
        })

        // Clear auth tokens & signing info
        val footerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val shortCert = BuildConfig.RECEIVER_CERT_SHA256.take(12).uppercase().ifBlank { "LOCAL" }
        footerRow.addView(
            TextView(this).apply {
                text = getString(R.string.home_footer_cert_fmt, BuildConfig.VERSION_NAME, shortCert)
                setTextColor(pal.inkMuted)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        footerRow.addView(
            OpalCastTheme.buildPillButton(this, getString(R.string.home_clear_trusted), pal.chipBg, pal.inkSecondary, pal.border, bold = false) {
                val n = ReceiverPrefs.clearAllPeerAuthTokens(this)
                val msg = if (n > 0) getString(R.string.home_clear_trusted_done_fmt, n) else getString(R.string.home_clear_trusted_empty)
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            },
        )
        settingsCard.addView(footerRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(14)
        })

        contentColumn.addView(settingsCard, cardMargins())

        // ---- About OpalDesk Host & Open-Source Project Card ----
        contentColumn.addView(buildCastProjectAboutCard(this, pal), cardMargins())

        rootScroll.addView(contentColumn)
        setContentView(rootScroll)
        AndroidApiCompat.applyHomeEdgeToEdge(this, pal.isDark, pal.bgCanvas)

        updateCodecStatusText()
        renderActiveFloatingBanner()
        renderDiscoveredPeers()
        renderUsbPeers()
        renderRecentPeers()
    }

    private fun buildSectionCard(
        pal: OpalCastPalette,
        title: String,
        subtitle: String,
        headerExtra: ((LinearLayout) -> Unit)? = null,
    ): LinearLayout = buildCastSectionCard(this, pal, title, subtitle, headerExtra)

    private fun buildCodecSelectorRow(pal: OpalCastPalette): LinearLayout =
        buildCastCodecSelectorRow(this, pal) { rebuildUi() }

    private fun buildThemeSelectorRow(pal: OpalCastPalette): LinearLayout =
        buildCastThemeSelectorRow(this, pal) { rebuildUi() }

    private fun buildLanguageSelectorRow(pal: OpalCastPalette): LinearLayout =
        buildCastLanguageSelectorRow(this, pal)

    private fun updateCodecStatusText() {
        val hw = AndroidApiCompat.probeLocalHardwareDecoders()
        val eff = AndroidApiCompat.resolvePreferredCastCodec(ReceiverPrefs.getVideoCodecPref(this)).uppercase()
        val avc = if (hw.hasHardwareAvc) "✓" else "✗"
        val hevc = if (hw.hasHardwareHevc) "✓" else "✗"
        codecStatusText.text = getString(R.string.home_codec_status_fmt, eff, avc, hevc)
    }

    private fun renderActiveFloatingBanner() {
        val info = activeFloating
        if (info == null) {
            activeFloatingBanner.visibility = View.GONE
        } else {
            activeFloatingTitle.text = getString(R.string.home_floating_active_fmt, info.appLabel)
            activeFloatingBanner.visibility = View.VISIBLE
        }
    }

    private fun renderDiscoveredPeers() {
        peersContainer.removeAllViews()
        val pal = OpalCastTheme.resolve(this)
        if (discoveredPeers.isEmpty()) {
            peersContainer.addView(TextView(this).apply {
                text = getString(R.string.home_discover_empty)
                setTextColor(pal.inkMuted)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                background = OpalCastTheme.roundedDrawable(this@CastHomeActivity, pal.bgElevated, 12, pal.border, 0.8f)
                setPadding(dp(12), dp(12), dp(12), dp(12))
            })
            return
        }
        for (peer in discoveredPeers) {
            val subtitle = buildString {
                append("${peer.host}:${peer.port}")
                if (peer.shortFingerprint.isNotBlank()) append(getString(R.string.home_peer_fingerprint_fmt, peer.shortFingerprint))
            }
            peersContainer.addView(
                buildPeerRow(pal, peer.displayName, subtitle, peer.host, peer.port),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(8)
                },
            )
        }
    }

    private fun renderUsbPeers() {
        usbPeersContainer.removeAllViews()
        val pal = OpalCastTheme.resolve(this)
        usbScanStatus.text = when {
            usbScanning -> getString(R.string.home_usb_scanning)
            usbPeers.isEmpty() -> getString(R.string.home_usb_empty)
            else -> getString(R.string.home_usb_found_fmt, usbPeers.size)
        }
        for (peer in usbPeers) {
            val subtitle = "${peer.host}:${peer.port} · ${peer.interfaceName}" +
                if (peer.shortFingerprint.isNotBlank()) " · ${peer.shortFingerprint}" else ""
            usbPeersContainer.addView(
                buildPeerRow(pal, peer.displayName, subtitle, peer.host, peer.port),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(8)
                },
            )
        }
    }

    private fun renderRecentPeers() {
        recentContainer.removeAllViews()
        val recents = ReceiverPrefs.getRecentPeers(this)
        if (recents.isEmpty()) return
        val pal = OpalCastTheme.resolve(this)
        recentContainer.addView(TextView(this).apply {
            text = getString(R.string.home_recent_title)
            setTextColor(pal.inkMuted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(6)
        })
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        recents.forEachIndexed { idx, item ->
            val label = if (item.label == item.host) "${item.host}:${item.port}" else "${item.label} (${item.host})"
            row.addView(
                OpalCastTheme.buildPillButton(this, label, pal.chipBg, pal.inkSecondary, pal.border, bold = false) {
                    val text = if (item.port == ReceiverPolicy.DEFAULT_CAST_PORT) item.host else "${item.host}:${item.port}"
                    manualInput.setText(text)
                    manualInput.setSelection(text.length)
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    if (idx > 0) leftMargin = dp(7)
                },
            )
        }
        scroll.addView(row)
        recentContainer.addView(scroll)
    }

    private fun buildPeerRow(pal: OpalCastPalette, title: String, subtitle: String, host: String, port: Int): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = OpalCastTheme.roundedDrawable(this@CastHomeActivity, pal.bgElevated, 14, pal.border, 0.9f)
            setPadding(dp(12), dp(11), dp(12), dp(11))
        }
        card.addView(TextView(this).apply {
            text = title
            setTextColor(pal.inkPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        card.addView(TextView(this).apply {
            text = subtitle
            setTextColor(pal.inkMuted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnRow.addView(
            OpalCastTheme.buildPillButton(this, getString(R.string.home_action_cast_desktop), pal.accent, Color.WHITE) {
                launchViewer(host, port, title, OpalCastClient.DISPLAY_MODE_DEX)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(7) },
        )
        btnRow.addView(
            OpalCastTheme.buildPillButton(this, getString(R.string.home_action_mirror), pal.secondarySoft, pal.secondaryAccent, pal.secondaryAccent) {
                launchViewer(host, port, title, OpalCastClient.DISPLAY_MODE_MIRROR)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(7) },
        )
        btnRow.addView(
            OpalCastTheme.buildPillButton(this, getString(R.string.home_action_app_window), pal.chipBg, pal.inkPrimary, pal.border) {
                RemoteAppPickerDialog(this, host, port, title).show()
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        card.addView(btnRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(9)
        })
        return card
    }

    private fun connectManual(displayMode: Int) {
        val parsed = ReceiverPolicy.parseHostPort(manualInput.text?.toString())
        if (parsed == null) {
            Toast.makeText(this, getString(R.string.home_invalid_ip_toast), Toast.LENGTH_SHORT).show()
            return
        }
        launchViewer(parsed.host, parsed.port, parsed.host, displayMode)
    }

    private fun openManualAppPicker() {
        val parsed = ReceiverPolicy.parseHostPort(manualInput.text?.toString())
        if (parsed == null) {
            Toast.makeText(this, getString(R.string.home_enter_ip_for_app_toast), Toast.LENGTH_SHORT).show()
            return
        }
        RemoteAppPickerDialog(this, parsed.host, parsed.port, parsed.host).show()
    }

    private fun launchViewer(host: String, port: Int, label: String, displayMode: Int) {
        startActivity(
            Intent(this, CastViewerActivity::class.java).apply {
                putExtra(CastViewerActivity.EXTRA_HOST, host)
                putExtra(CastViewerActivity.EXTRA_PORT, port)
                putExtra(CastViewerActivity.EXTRA_LABEL, label)
                putExtra(CastViewerActivity.EXTRA_INITIAL_DISPLAY_MODE, displayMode)
            },
        )
    }

    private fun rebuildUi() {
        val typed = manualInput.text?.toString().orEmpty()
        buildContent()
        if (typed.isNotEmpty()) {
            manualInput.setText(typed)
            manualInput.setSelection(typed.length)
        }
        refreshDiscoveryState()
    }

    private fun cardMargins(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(14)
        }

    private fun dp(v: Int): Int = OpalCastTheme.dp(this, v)
}
