package io.github.lengsukq.opalcast.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
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
                Toast.makeText(this, "未授予局域网发现权限，仍可通过手动输入 IP 连接", Toast.LENGTH_LONG).show()
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
            text = "OpalCast"
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
            OpalCastTheme.buildBadge(this, "加QQ群", pal.secondarySoft, pal.secondaryAccent, pal.secondaryAccent).apply {
                isClickable = true
                isFocusable = true
                setOnClickListener { joinCommunityQqGroup() }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = dp(6)
            },
        )
        brandTextCol.addView(titleLine)
        brandTextCol.addView(TextView(this).apply {
            text = "通用安卓被投屏端 · 适配 32/64 位与低版本系统"
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
        val abiLabel = AndroidApiCompat.primaryAbiLabel()
        val apiLabel = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        val codecTag = when {
            hwSummary.hasHardwareHevc -> "HEVC + H.264 硬解"
            hwSummary.hasHardwareAvc -> "H.264 硬解 · 自动降级"
            else -> "兼容软解模式"
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
                text = "Android 17 需要授予「局域网」权限才能自动发现附近 OpalDesk 设备"
                setTextColor(pal.inkPrimary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(10) },
        )
        localNetBanner.addView(
            OpalCastTheme.buildPillButton(this, "立即授权", pal.amber, Color.WHITE) {
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
            OpalCastTheme.buildPillButton(this, "关闭小窗", pal.dangerSoft, pal.danger, pal.danger) {
                FloatingCastService.stop(this)
            },
        )
        contentColumn.addView(activeFloatingBanner, cardMargins())

        // ---- Discovered Nearby Host Devices Card ----
        val discoverCard = buildSectionCard(pal, "附近可连接的 OpalDesk 主机", "局域网自动发现 · 需发送端开启无线投屏") { headerRow ->
            headerRow.addView(
                OpalCastTheme.buildPillButton(this, "刷新", pal.chipBg, pal.inkSecondary, pal.border, bold = false) {
                    PeerDiscoveryManager.restartBrowsing(this)
                    Toast.makeText(this, "正在重新扫描局域网设备…", Toast.LENGTH_SHORT).show()
                },
            )
        }
        peersContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        discoverCard.addView(peersContainer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        })
        contentColumn.addView(discoverCard, cardMargins())

        // ---- Optional USB Tethering Beta Card ----
        usbSectionCard = buildSectionCard(pal, "USB 有线直连主机 (Beta)", UsbTetherPolicy.BETA_DISCLAIMER) { headerRow ->
            headerRow.addView(
                OpalCastTheme.buildPillButton(this, "探测", pal.chipBg, pal.inkSecondary, pal.border, bold = false) {
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
        val manualCard = buildSectionCard(pal, "手动输入地址连接", "支持 IPv4 / IPv6，默认端口 19878，跨网段或旧路由器也能直连")
        manualInput = EditText(this).apply {
            hint = "例如 192.168.31.88 或 192.168.31.88:19878"
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
            OpalCastTheme.buildPillButton(this, "投屏桌面", pal.accent, Color.WHITE) {
                connectManual(OpalCastClient.DISPLAY_MODE_DEX)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) },
        )
        manualActions.addView(
            OpalCastTheme.buildPillButton(this, "主屏镜像", pal.secondarySoft, pal.secondaryAccent, pal.secondaryAccent) {
                connectManual(OpalCastClient.DISPLAY_MODE_MIRROR)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) },
        )
        manualActions.addView(
            OpalCastTheme.buildPillButton(this, "应用小窗", pal.chipBg, pal.inkPrimary, pal.border) {
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
        val settingsCard = buildSectionCard(pal, "解码与接收端设置", "兼容老旧 32 位与低版本安卓平板、手机和电视盒")

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
            text = "界面外观"
            setTextColor(pal.inkSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(14)
        })
        settingsCard.addView(buildThemeSelectorRow(pal), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
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
            text = "USB 网络共享有线直连 (Beta)"
            setTextColor(pal.inkPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })
        usbLabelCol.addView(TextView(this).apply {
            text = "开启后自动探测 USB-C 共享网卡并绑定高码率通道"
            setTextColor(pal.inkMuted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
        })
        val usbOn = ReceiverPrefs.isUsbTetherEnabled(this)
        usbToggleRow.addView(usbLabelCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(10) })
        usbToggleRow.addView(
            OpalCastTheme.buildPillButton(
                this,
                if (usbOn) "已开启" else "已关闭",
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
                text = "OpalCast v${BuildConfig.VERSION_NAME} · 独立密钥 $shortCert"
                setTextColor(pal.inkMuted)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        footerRow.addView(
            OpalCastTheme.buildPillButton(this, "清除已信任主机", pal.chipBg, pal.inkSecondary, pal.border, bold = false) {
                val n = ReceiverPrefs.clearAllPeerAuthTokens(this)
                Toast.makeText(this, if (n > 0) "已清除 $n 台主机免确认授权" else "当前无已保存授权", Toast.LENGTH_SHORT).show()
            },
        )
        settingsCard.addView(footerRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(14)
        })

        contentColumn.addView(settingsCard, cardMargins())

        // ---- About OpalDesk Host & Open-Source Project Card ----
        contentColumn.addView(buildProjectAboutCard(pal), cardMargins())

        rootScroll.addView(contentColumn)
        setContentView(rootScroll)
        AndroidApiCompat.applyHomeEdgeToEdge(this, pal.isDark, pal.bgCanvas)

        updateCodecStatusText()
        renderActiveFloatingBanner()
        renderDiscoveredPeers()
        renderUsbPeers()
        renderRecentPeers()
    }

    private fun buildProjectAboutCard(pal: OpalCastPalette): LinearLayout {
        val card = buildSectionCard(
            pal,
            "关于主投屏端 OpalDesk 与开源项目",
            "OpalCast 为 OpalDesk 生态的通用安卓被投屏端 · MIT 免费开源",
        )

        // Host sender (OpalDesk) introduction box
        val hostBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = OpalCastTheme.roundedDrawable(this@CastHomeActivity, pal.bgElevated, 14, pal.accentBorder, 0.9f)
            setPadding(dp(13), dp(12), dp(13), dp(12))
        }
        val hostTitleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        hostTitleRow.addView(
            TextView(this).apply {
                text = "主投屏端 · OpalDesk"
                setTextColor(pal.inkPrimary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) },
        )
        hostTitleRow.addView(
            OpalCastTheme.buildBadge(this, "Android 11–17 · 免 Root 优先", pal.accentSoft, pal.accent, pal.accentBorder),
        )
        hostBox.addView(hostTitleRow)

        hostBox.addView(
            TextView(this).apply {
                text = "全品牌安卓通用的 Wayland 式 120fps 液态毛玻璃多窗工作站（一窗一独立虚拟屏，零厂商 Freeform 魔改）。" +
                    "在发送端手机安装 OpalDesk 并开启无线投屏后，本设备即可接收完整多窗桌面、主屏镜像与单应用悬浮小窗，" +
                    "支持物理键鼠、多指触控、中文输入法桥接及音频/麦克风互通。"
                setTextColor(pal.inkSecondary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.2f)
                setLineSpacing(dp(3).toFloat(), 1f)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            },
        )
        hostBox.addView(
            TextView(this).apply {
                text = "提示：下方项目介绍页已包含主投屏端及各平台伴侣端的详细介绍与下载地址。"
                setTextColor(pal.inkMuted)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(7)
            },
        )
        card.addView(
            hostBox,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(12)
            },
        )

        // Showcase / Intro page (includes download links)
        card.addView(
            buildProjectLinkBlock(
                pal = pal,
                title = "项目介绍页（含主投屏端介绍与下载）",
                url = ReceiverPolicy.PROJECT_INTRO_URL,
                openLabel = "打开介绍页",
                openBg = pal.accent,
                openText = Color.WHITE,
                openBorder = Color.TRANSPARENT,
            ),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10)
            },
        )

        // GitHub open-source repository
        card.addView(
            buildProjectLinkBlock(
                pal = pal,
                title = "GitHub 开源仓库（lengsukq/OpalDesk · MIT）",
                url = ReceiverPolicy.PROJECT_GITHUB_URL,
                openLabel = "访问 GitHub",
                openBg = pal.secondarySoft,
                openText = pal.secondaryAccent,
                openBorder = pal.secondaryAccent,
            ),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(9)
            },
        )

        // QQ community group entry (matching host OpalDesk app)
        val qqBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = OpalCastTheme.roundedDrawable(this@CastHomeActivity, pal.bgElevated, 12, pal.border, 0.8f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        qqBox.addView(TextView(this).apply {
            text = "交流反馈 QQ 群 · ${ReceiverPolicy.COMMUNITY_QQ_GROUP_NUMBER}"
            setTextColor(pal.inkPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.8f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })
        qqBox.addView(
            TextView(this).apply {
                text = "获取最新版安装包、反馈适配问题与交流跨屏玩法"
                setTextColor(pal.inkMuted)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(2)
            },
        )
        val qqBtnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        qqBtnRow.addView(
            OpalCastTheme.buildPillButton(this, "加入 QQ 群", pal.secondarySoft, pal.secondaryAccent, pal.secondaryAccent) {
                joinCommunityQqGroup()
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) },
        )
        qqBtnRow.addView(
            OpalCastTheme.buildPillButton(this, "复制群号", pal.chipBg, pal.inkSecondary, pal.border, bold = false) {
                copyTextToClipboard("OpalDesk QQ Group", ReceiverPolicy.COMMUNITY_QQ_GROUP_NUMBER)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        qqBox.addView(
            qqBtnRow,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            },
        )
        card.addView(
            qqBox,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(9)
            },
        )

        return card
    }

    private fun joinCommunityQqGroup() {
        val groupNo = ReceiverPolicy.COMMUNITY_QQ_GROUP_NUMBER
        runCatching {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("OpalDesk QQ Group", groupNo))
        }
        val uri = Uri.parse(ReceiverPolicy.buildQqGroupDeepLink(groupNo))
        val launched = runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
        val msg = if (launched) {
            "已复制群号 $groupNo，正在打开 QQ…"
        } else {
            "已复制 QQ 群号 $groupNo，请打开 QQ 搜索加入"
        }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun buildProjectLinkBlock(
        pal: OpalCastPalette,
        title: String,
        url: String,
        openLabel: String,
        openBg: Int,
        openText: Int,
        openBorder: Int,
    ): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = OpalCastTheme.roundedDrawable(this@CastHomeActivity, pal.bgElevated, 12, pal.border, 0.8f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        box.addView(TextView(this).apply {
            text = title
            setTextColor(pal.inkPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.8f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })
        box.addView(
            TextView(this).apply {
                text = url
                setTextColor(pal.accent)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.8f)
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(3)
            },
        )
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnRow.addView(
            OpalCastTheme.buildPillButton(this, openLabel, openBg, openText, openBorder) {
                openExternalUrl(url)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) },
        )
        btnRow.addView(
            OpalCastTheme.buildPillButton(this, "复制地址", pal.chipBg, pal.inkSecondary, pal.border, bold = false) {
                copyTextToClipboard(title, url)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        box.addView(
            btnRow,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            },
        )
        return box
    }

    private fun openExternalUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (_: Throwable) {
            copyTextToClipboard("OpalDesk 链接", url)
            Toast.makeText(this, "未检测到浏览器，已复制链接到剪贴板", Toast.LENGTH_LONG).show()
        }
    }

    private fun copyTextToClipboard(label: String, text: String) {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText(label, text))
            Toast.makeText(this, "已复制：$text", Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {
            Toast.makeText(this, text, Toast.LENGTH_LONG).show()
        }
    }

    private fun buildSectionCard(
        pal: OpalCastPalette,
        title: String,
        subtitle: String,
        headerExtra: ((LinearLayout) -> Unit)? = null,
    ): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = OpalCastTheme.roundedDrawable(this@CastHomeActivity, pal.bgCard, 18, pal.border, 1f)
            setPadding(dp(16), dp(15), dp(16), dp(15))
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val textCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        textCol.addView(TextView(this).apply {
            text = title
            setTextColor(pal.inkPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })
        textCol.addView(TextView(this).apply {
            text = subtitle
            setTextColor(pal.inkMuted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
        })
        row.addView(textCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) })
        headerExtra?.invoke(row)
        card.addView(row)
        return card
    }

    private fun buildCodecSelectorRow(pal: OpalCastPalette): LinearLayout {
        val cur = ReceiverPrefs.getVideoCodecPref(this)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val items = listOf(
            ReceiverPolicy.CODEC_PREF_AUTO to "自动协商",
            ReceiverPolicy.CODEC_PREF_H264 to "优先 H.264",
            ReceiverPolicy.CODEC_PREF_HEVC to "优先 HEVC",
        )
        items.forEachIndexed { idx, (pref, label) ->
            val selected = (cur == pref)
            row.addView(
                OpalCastTheme.buildPillButton(
                    this,
                    label,
                    if (selected) pal.accentSoft else pal.bgElevated,
                    if (selected) pal.accent else pal.inkSecondary,
                    if (selected) pal.accentBorder else pal.border,
                    bold = selected,
                ) {
                    ReceiverPrefs.setVideoCodecPref(this, pref)
                    rebuildUi()
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (idx > 0) leftMargin = dp(8)
                },
            )
        }
        return row
    }

    private fun buildThemeSelectorRow(pal: OpalCastPalette): LinearLayout {
        val cur = ReceiverPrefs.getThemeMode(this)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val items = listOf(
            ReceiverPrefs.THEME_SYSTEM to "跟随系统",
            ReceiverPrefs.THEME_LIGHT to "浅色",
            ReceiverPrefs.THEME_DARK to "深色",
        )
        items.forEachIndexed { idx, (mode, label) ->
            val selected = (cur == mode)
            row.addView(
                OpalCastTheme.buildPillButton(
                    this,
                    label,
                    if (selected) pal.accentSoft else pal.bgElevated,
                    if (selected) pal.accent else pal.inkSecondary,
                    if (selected) pal.accentBorder else pal.border,
                    bold = selected,
                ) {
                    ReceiverPrefs.setThemeMode(this, mode)
                    rebuildUi()
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (idx > 0) leftMargin = dp(8)
                },
            )
        }
        return row
    }

    private fun updateCodecStatusText() {
        val hw = AndroidApiCompat.probeLocalHardwareDecoders()
        val eff = AndroidApiCompat.resolvePreferredCastCodec(ReceiverPrefs.getVideoCodecPref(this)).uppercase()
        val avc = if (hw.hasHardwareAvc) "✓" else "✗"
        val hevc = if (hw.hasHardwareHevc) "✓" else "✗"
        codecStatusText.text = "视频解码策略（当前协商：$eff · 硬解 H.264 $avc / HEVC $hevc）"
    }

    private fun renderActiveFloatingBanner() {
        val info = activeFloating
        if (info == null) {
            activeFloatingBanner.visibility = View.GONE
        } else {
            activeFloatingTitle.text = "悬浮小窗运行中：${info.appLabel}"
            activeFloatingBanner.visibility = View.VISIBLE
        }
    }

    private fun renderDiscoveredPeers() {
        peersContainer.removeAllViews()
        val pal = OpalCastTheme.resolve(this)
        if (discoveredPeers.isEmpty()) {
            peersContainer.addView(TextView(this).apply {
                text = "暂未发现附近主机。请在发送端手机打开 OpalDesk → 无线投屏并保持在同一 Wi-Fi，或下方手动输入 IP。"
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
                if (peer.shortFingerprint.isNotBlank()) append(" · 指纹 ${peer.shortFingerprint}")
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
            usbScanning -> "正在探测 USB 共享网卡网关…"
            usbPeers.isEmpty() -> "未检测到 USB 直连主机（请用数据线连接并在发送端手机开启「USB 网络共享」与无线投屏）"
            else -> "已发现 ${usbPeers.size} 台 USB 直连主机"
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
            text = "最近连接（点击填入地址）"
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
            OpalCastTheme.buildPillButton(this, "投屏桌面", pal.accent, Color.WHITE) {
                launchViewer(host, port, title, OpalCastClient.DISPLAY_MODE_DEX)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(7) },
        )
        btnRow.addView(
            OpalCastTheme.buildPillButton(this, "主屏镜像", pal.secondarySoft, pal.secondaryAccent, pal.secondaryAccent) {
                launchViewer(host, port, title, OpalCastClient.DISPLAY_MODE_MIRROR)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(7) },
        )
        btnRow.addView(
            OpalCastTheme.buildPillButton(this, "应用小窗", pal.chipBg, pal.inkPrimary, pal.border) {
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
            Toast.makeText(this, "请输入有效的主机 IP（例如 192.168.31.88）", Toast.LENGTH_SHORT).show()
            return
        }
        launchViewer(parsed.host, parsed.port, parsed.host, displayMode)
    }

    private fun openManualAppPicker() {
        val parsed = ReceiverPolicy.parseHostPort(manualInput.text?.toString())
        if (parsed == null) {
            Toast.makeText(this, "请先输入主机 IP 再打开远端应用小窗", Toast.LENGTH_SHORT).show()
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
