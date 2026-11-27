package io.github.lengsukq.opalcast.ui

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import io.github.lengsukq.opalcast.compat.AndroidApiCompat
import io.github.lengsukq.opalcast.net.OpalCastClient
import io.github.lengsukq.opalcast.net.UsbTetherDiscovery
import io.github.lengsukq.opalcast.prefs.ReceiverPrefs

/**
 * Pure-View modal dialog that fetches the remote OpalDesk host's installed launcher app catalog
 * and launches any chosen app into a standalone [FloatingCastService] window.
 */
internal class RemoteAppPickerDialog(
    private val activity: Activity,
    private val host: String,
    private val port: Int,
    private val peerLabel: String,
    private val preloadedApps: List<OpalCastClient.RemoteAppEntry>? = null,
    private val onLaunched: () -> Unit = {},
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var dialog: Dialog? = null
    private var catalogClient: OpalCastClient? = null
    private var allApps: List<OpalCastClient.RemoteAppEntry> = preloadedApps.orEmpty()
    private var query: String = ""
    private var statusTextView: TextView? = null
    private var listContainer: LinearLayout? = null
    private var handoverToken: String = ""

    fun show() {
        val pal = OpalCastTheme.resolve(activity)
        val dlg = Dialog(activity).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setCanceledOnTouchOutside(true)
            setOnDismissListener { cleanup() }
        }
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = OpalCastTheme.roundedDrawable(activity, pal.bgCard, 20, pal.borderStrong, 1.1f)
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }

        // Header
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleCol = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        titleCol.addView(TextView(activity).apply {
            text = "打开远端应用小窗"
            setTextColor(pal.inkPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })
        titleCol.addView(TextView(activity).apply {
            text = "来自 $peerLabel · 点击即以独立悬浮小窗运行"
            setTextColor(pal.inkSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        })
        header.addView(titleCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(
            OpalCastTheme.buildPillButton(activity, "关闭", pal.chipBg, pal.inkSecondary, pal.border, bold = false) {
                dlg.dismiss()
            },
        )
        root.addView(header)

        // Search input
        val searchBox = EditText(activity).apply {
            hint = "搜索应用名称或包名…"
            setHintTextColor(pal.inkMuted)
            setTextColor(pal.inkPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            isSingleLine = true
            background = OpalCastTheme.roundedDrawable(activity, pal.inputBg, 12, pal.border, 1f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    query = s?.toString().orEmpty().trim()
                    renderApps()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        root.addView(searchBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
            bottomMargin = dp(10)
        })

        // Status hint
        val statusTv = TextView(activity).apply {
            text = if (allApps.isEmpty()) "正在获取远端应用列表…" else "共 ${allApps.size} 个应用"
            setTextColor(pal.inkSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        statusTextView = statusTv
        root.addView(statusTv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(8)
        })

        // Scrollable app list
        val scroll = ScrollView(activity)
        val container = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        listContainer = container
        scroll.addView(container)
        val maxListH = (activity.resources.displayMetrics.heightPixels * 0.52f).toInt().coerceAtLeast(dp(240))
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxListH))

        dlg.setContentView(root)
        dlg.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val dlgW = (activity.resources.displayMetrics.widthPixels * 0.90f).toInt().coerceAtMost(dp(460))
            setLayout(dlgW, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog = dlg
        dlg.show()

        if (allApps.isNotEmpty()) {
            renderApps()
        } else {
            fetchRemoteCatalog()
        }
    }

    private fun fetchRemoteCatalog() {
        val boundNet = if (ReceiverPrefs.isUsbTetherEnabled(activity)) {
            UsbTetherDiscovery.resolveBoundNetworkForHost(host)
        } else {
            null
        }
        val c = OpalCastClient(
            host = host,
            port = port,
            savedAuthToken = ReceiverPrefs.getPeerAuthToken(activity, host),
            clientId = ReceiverPrefs.getOrCreateClientId(activity),
            reqWidth = 720,
            reqHeight = 1280,
            reqDpi = 240,
            reqFps = 60,
            sessionRole = OpalCastClient.SESSION_ROLE_APP_CATALOG,
            audioEnabled = false,
            targetNetwork = boundNet,
            listener = object : OpalCastClient.Listener {
                override fun onWaitingForApproval() {
                    mainHandler.post { statusTextView?.text = "等待对方设备点击「允许」…" }
                }

                override fun onAuthenticated(peerName: String, authToken: String, permanent: Boolean, certFingerprint: String) {
                    if (permanent && authToken.isNotBlank()) {
                        ReceiverPrefs.savePeerAuthToken(activity, host, authToken)
                    } else if (authToken.isNotBlank()) {
                        handoverToken = authToken
                    }
                    mainHandler.post { statusTextView?.text = "已连接 $peerName，正在加载应用列表…" }
                    catalogClient?.requestAppList()
                }

                override fun onAppListReceived(apps: List<OpalCastClient.RemoteAppEntry>) {
                    mainHandler.post {
                        allApps = apps
                        statusTextView?.text = "共 ${apps.size} 个远端应用"
                        renderApps()
                    }
                    catalogClient?.stop()
                }

                override fun onVideoSizeChanged(width: Int, height: Int, fps: Int) = Unit

                override fun onError(message: String) {
                    mainHandler.post { statusTextView?.text = "加载失败：$message" }
                }

                override fun onDisconnected(reason: String) = Unit
            },
        )
        catalogClient = c
        c.start()
    }

    private fun renderApps() {
        val container = listContainer ?: return
        container.removeAllViews()
        val pal = OpalCastTheme.resolve(activity)
        val filtered = allApps
            .filter {
                query.isEmpty() ||
                    it.label.contains(query, ignoreCase = true) ||
                    it.pkg.contains(query, ignoreCase = true)
            }
            .sortedWith(
                compareByDescending<OpalCastClient.RemoteAppEntry> {
                    ReceiverPrefs.getAppLaunchCount(activity, it.pkg) * 3 + it.openCount
                }.thenBy { it.label.lowercase() },
            )

        if (filtered.isEmpty()) {
            container.addView(TextView(activity).apply {
                text = if (allApps.isEmpty()) "等待应用目录…" else "未找到匹配的远端应用"
                setTextColor(pal.inkMuted)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                gravity = Gravity.CENTER
                setPadding(0, dp(24), 0, dp(24))
            })
            return
        }

        for (entry in filtered) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = OpalCastTheme.roundedDrawable(activity, pal.bgElevated, 12, pal.border, 0.8f)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                isClickable = true
                setOnClickListener { launchFloatingApp(entry) }
            }
            val badge = TextView(activity).apply {
                text = entry.label.take(1).uppercase()
                setTextColor(pal.accent)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                background = OpalCastTheme.roundedDrawable(activity, pal.accentSoft, 10, pal.accentBorder, 0.8f)
            }
            row.addView(badge, LinearLayout.LayoutParams(dp(34), dp(34)).apply { rightMargin = dp(10) })

            val textCol = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            textCol.addView(TextView(activity).apply {
                text = entry.label
                setTextColor(pal.inkPrimary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            textCol.addView(TextView(activity).apply {
                text = entry.pkg
                setTextColor(pal.inkMuted)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            row.addView(textCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = dp(8)
            })
            row.addView(OpalCastTheme.buildBadge(activity, "小窗", pal.accentSoft, pal.accent, pal.accentBorder))

            container.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(7)
            })
        }
    }

    private fun launchFloatingApp(entry: OpalCastClient.RemoteAppEntry) {
        if (!AndroidApiCompat.canDrawOverlays(activity)) {
            Toast.makeText(activity, "请先授予 OpalCast 悬浮窗权限", Toast.LENGTH_LONG).show()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                runCatching {
                    activity.startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${activity.packageName}")),
                    )
                }
            }
            return
        }
        val started = FloatingCastService.startApp(
            ctx = activity,
            host = host,
            port = port,
            peerLabel = peerLabel,
            appPackage = entry.pkg,
            appActivity = entry.cls,
            appLabel = entry.label,
            handoverToken = handoverToken,
        )
        if (started) {
            onLaunched()
            dialog?.dismiss()
        } else {
            Toast.makeText(activity, "无法启动远端应用小窗", Toast.LENGTH_SHORT).show()
        }
    }

    private fun cleanup() {
        catalogClient?.stop()
        catalogClient = null
    }

    private fun dp(v: Int): Int = OpalCastTheme.dp(activity, v)
}
