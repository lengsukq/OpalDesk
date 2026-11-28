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
import io.github.lengsukq.opalcast.R
import io.github.lengsukq.opalcast.compat.AndroidApiCompat
import io.github.lengsukq.opalcast.net.OpalCastClient
import io.github.lengsukq.opalcast.net.UsbTetherDiscovery
import io.github.lengsukq.opalcast.policy.ReceiverPolicy
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
            text = activity.getString(R.string.picker_title)
            setTextColor(pal.inkPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })
        titleCol.addView(TextView(activity).apply {
            text = activity.getString(R.string.picker_subtitle_fmt, peerLabel)
            setTextColor(pal.inkSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        })
        header.addView(titleCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(
            OpalCastTheme.buildPillButton(activity, activity.getString(R.string.common_close), pal.chipBg, pal.inkSecondary, pal.border, bold = false) {
                dlg.dismiss()
            },
        )
        root.addView(header)

        // Search input
        val searchBox = EditText(activity).apply {
            hint = activity.getString(R.string.picker_search_hint)
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
            text = if (allApps.isEmpty()) {
                activity.getString(R.string.picker_loading_list)
            } else {
                activity.getString(R.string.picker_count_fmt, allApps.size)
            }
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
            defaultDisconnectReason = activity.getString(R.string.client_disconnected),
            defaultRejectedMessage = activity.getString(R.string.client_rejected),
            listener = object : OpalCastClient.Listener {
                override fun onWaitingForApproval() {
                    mainHandler.post { statusTextView?.text = activity.getString(R.string.viewer_waiting_approval) }
                }

                override fun onAuthenticated(peerName: String, authToken: String, permanent: Boolean, certFingerprint: String) {
                    if (permanent && authToken.isNotBlank()) {
                        ReceiverPrefs.savePeerAuthToken(activity, host, authToken)
                    } else if (authToken.isNotBlank()) {
                        handoverToken = authToken
                    }
                    mainHandler.post { statusTextView?.text = activity.getString(R.string.picker_connected_loading_fmt, peerName) }
                    catalogClient?.requestAppList()
                }

                override fun onAppListReceived(apps: List<OpalCastClient.RemoteAppEntry>) {
                    mainHandler.post {
                        allApps = apps
                        statusTextView?.text = activity.getString(R.string.picker_remote_count_fmt, apps.size)
                        renderApps()
                    }
                    catalogClient?.stop()
                }

                override fun onVideoSizeChanged(width: Int, height: Int, fps: Int) = Unit

                override fun onError(message: String) {
                    mainHandler.post { statusTextView?.text = activity.getString(R.string.picker_load_failed_fmt, message) }
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
            .filter { entry ->
                if (query.isEmpty()) return@filter true
                if (entry.label.contains(query, ignoreCase = true) || entry.pkg.contains(query, ignoreCase = true)) {
                    return@filter true
                }
                if (!entry.isSecondaryProfile) return@filter false
                val badgeText = formatProfileBadge(entry.userId, entry.profileType)
                badgeText.contains(query, ignoreCase = true) ||
                    ReceiverPolicy.profileSearchKeywords(entry.userId, entry.profileType)
                        .any { it.contains(query, ignoreCase = true) }
            }
            .sortedWith(
                compareByDescending<OpalCastClient.RemoteAppEntry> {
                    ReceiverPrefs.getAppLaunchCount(activity, it.appKey) * 3 + it.openCount
                }.thenBy { it.label.lowercase() }.thenBy { it.userId },
            )

        if (filtered.isEmpty()) {
            container.addView(TextView(activity).apply {
                text = if (allApps.isEmpty()) {
                    activity.getString(R.string.picker_waiting_catalog)
                } else {
                    activity.getString(R.string.picker_no_match)
                }
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
            val titleRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            titleRow.addView(TextView(activity).apply {
                text = entry.label
                setTextColor(pal.inkPrimary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (entry.isSecondaryProfile) {
                val profileBadgeText = formatProfileBadge(entry.userId, entry.profileType)
                val (badgeBg, badgeFg, badgeBorder) = resolveProfileBadgeColors(entry.userId, entry.profileType)
                titleRow.addView(
                    OpalCastTheme.buildBadge(activity, profileBadgeText, badgeBg, badgeFg, badgeBorder),
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        leftMargin = dp(6)
                    },
                )
            }
            textCol.addView(titleRow)
            textCol.addView(TextView(activity).apply {
                text = if (entry.isSecondaryProfile) "${entry.pkg} · u${entry.userId}" else entry.pkg
                setTextColor(pal.inkMuted)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            row.addView(textCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = dp(8)
            })
            row.addView(OpalCastTheme.buildBadge(activity, activity.getString(R.string.picker_badge_window), pal.accentSoft, pal.accent, pal.accentBorder))

            container.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(7)
            })
        }
    }

    private fun launchFloatingApp(entry: OpalCastClient.RemoteAppEntry) {
        if (!AndroidApiCompat.canDrawOverlays(activity)) {
            Toast.makeText(activity, activity.getString(R.string.perm_overlay_required), Toast.LENGTH_LONG).show()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                runCatching {
                    activity.startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${activity.packageName}")),
                    )
                }
            }
            return
        }
        val launchLabel = if (entry.isSecondaryProfile) {
            val badge = formatProfileBadge(entry.userId, entry.profileType)
            if (entry.label.contains(badge)) entry.label else "${entry.label} ($badge)"
        } else {
            entry.label
        }
        val started = FloatingCastService.startApp(
            ctx = activity,
            host = host,
            port = port,
            peerLabel = peerLabel,
            appPackage = entry.pkg,
            appActivity = entry.cls,
            appLabel = launchLabel,
            appUserId = entry.userId,
            handoverToken = handoverToken,
        )
        if (started) {
            onLaunched()
            dialog?.dismiss()
        } else {
            Toast.makeText(activity, activity.getString(R.string.picker_launch_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun formatProfileBadge(userId: Int, profileType: String): String =
        when (ReceiverPolicy.normalizeProfileType(profileType, userId)) {
            ReceiverPolicy.PROFILE_CLONE -> activity.getString(R.string.profile_badge_clone)
            ReceiverPolicy.PROFILE_WORK -> activity.getString(R.string.profile_badge_work)
            ReceiverPolicy.PROFILE_PRIVATE -> activity.getString(R.string.profile_badge_private)
            else -> activity.getString(R.string.profile_badge_secondary_fmt, userId)
        }

    private fun resolveProfileBadgeColors(userId: Int, profileType: String): Triple<Int, Int, Int> =
        when (ReceiverPolicy.normalizeProfileType(profileType, userId)) {
            ReceiverPolicy.PROFILE_WORK -> Triple(0x293B82F6, 0xFF60A5FA.toInt(), 0x5C3B82F6)
            ReceiverPolicy.PROFILE_PRIVATE -> Triple(0x298B5CF6, 0xFFA78BFA.toInt(), 0x5C8B5CF6)
            ReceiverPolicy.PROFILE_GENERIC -> Triple(0x2914B8A6, 0xFF2DD4BF.toInt(), 0x5C14B8A6)
            else -> Triple(0x29F59E0B, 0xFFF59E0B.toInt(), 0x5CF59E0B)
        }

    private fun cleanup() {
        catalogClient?.stop()
        catalogClient = null
    }

    private fun dp(v: Int): Int = OpalCastTheme.dp(activity, v)
}
