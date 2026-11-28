package io.github.lengsukq.opaldesk.cast

import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lengsukq.opaldesk.R
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import io.github.lengsukq.opaldesk.ui.OpalButton
import io.github.lengsukq.opaldesk.ui.OpalPalette
import io.github.lengsukq.opaldesk.ui.OpalStatusBadge
import io.github.lengsukq.opaldesk.ui.OpalText
import io.github.lengsukq.opaldesk.ui.UnifiedAppItem
import io.github.lengsukq.opaldesk.ui.UnifiedAppLauncherPanel
import kotlinx.coroutines.delay
import kotlin.math.max

/**
 * Liquid Glass modal letting the receiver user browse installed apps on a paired remote Android
 * device and pop out any app as an independent system floating window ([PeerFloatingAppService]).
 *
 * Reuses the exact same [UnifiedAppLauncherPanel] component and usage-frequency sorting logic
 * as the Local/External DeX App Drawer.
 */
@Composable
fun RemoteAppPickerModal(
    host: String,
    port: Int,
    peerLabel: String,
    preloadedApps: List<OpalCastPeerClient.RemoteAppEntry>? = null,
    onDismiss: () -> Unit,
    onFloatingLaunched: () -> Unit = onDismiss,
) {
    val ctx = LocalContext.current
    val localLaunchCounts by AppPrefs.appLaunchCountsFlow.collectAsState()
    var remoteAppList by remember { mutableStateOf(preloadedApps ?: emptyList()) }
    var resolvedPeerName by remember { mutableStateOf(peerLabel.ifBlank { host }) }
    var statusText by remember { mutableStateOf(if (preloadedApps != null) "" else ctx.getString(R.string.picker_connecting, peerLabel)) }
    var errorText by remember { mutableStateOf("") }
    var handoverToken by remember { mutableStateOf("") }
    var retryNonce by remember { mutableIntStateOf(0) }
    var hasOverlayPerm by remember { mutableStateOf(Settings.canDrawOverlays(ctx)) }

    // Re-check overlay permission cleanly when the user returns from Android Settings
    LaunchedEffect(hasOverlayPerm) {
        if (!hasOverlayPerm) {
            while (!hasOverlayPerm) {
                delay(650L)
                if (Settings.canDrawOverlays(ctx)) {
                    hasOverlayPerm = true
                }
            }
        }
    }

    // Fetch app catalog over a lightweight `app_catalog` TLS session when not preloaded
    if (preloadedApps == null) {
        DisposableEffect(host, port, retryNonce) {
            val mainHandler = Handler(Looper.getMainLooper())
            statusText = ctx.getString(R.string.picker_connecting, peerLabel)
            errorText = ""
            var clientRef: OpalCastPeerClient? = null
            val boundNet = if (AppPrefs.isUsbTetherCastEnabled(ctx)) {
                UsbTetherPeerDetector.resolveBoundNetworkForHost(host)
            } else {
                null
            }
            val client = OpalCastPeerClient(
                host = host,
                port = port,
                savedAuthToken = AppPrefs.getPeerAuthToken(ctx, host),
                clientId = AppPrefs.getOrCreatePeerClientId(ctx),
                reqWidth = 720,
                reqHeight = 1280,
                reqDpi = 0,
                reqFps = 60,
                sessionRole = OpalCastPeerClient.SESSION_ROLE_APP_CATALOG,
                audioEnabled = false,
                targetNetwork = boundNet,
                listener = object : OpalCastPeerClient.Listener {
                    override fun onWaitingForApproval() {
                        mainHandler.post {
                            statusText = ctx.getString(R.string.picker_waiting_approval, resolvedPeerName)
                        }
                    }

                    override fun onAuthenticated(
                        peerName: String,
                        authToken: String,
                        permanent: Boolean,
                        certFingerprint: String,
                    ) {
                        if (permanent && authToken.isNotBlank()) {
                            AppPrefs.savePeerAuthToken(ctx, host, authToken)
                        }
                        mainHandler.post {
                            resolvedPeerName = peerName.ifBlank { peerLabel }
                            if (!permanent && authToken.isNotBlank()) {
                                // Single-use in-memory handover token for launching 1 chosen floating app
                                handoverToken = authToken
                            }
                            statusText = ctx.getString(R.string.picker_loading_apps)
                        }
                        clientRef?.requestAppList()
                    }

                    override fun onAppListReceived(apps: List<OpalCastPeerClient.RemoteAppEntry>) {
                        mainHandler.post {
                            remoteAppList = apps
                            statusText = ""
                        }
                    }

                    override fun onVideoSizeChanged(width: Int, height: Int, fps: Int) = Unit

                    override fun onError(message: String) {
                        mainHandler.post {
                            errorText = message
                            statusText = ""
                        }
                    }

                    override fun onDisconnected(reason: String) {
                        mainHandler.post {
                            if (remoteAppList.isEmpty() && errorText.isBlank()) {
                                errorText = reason
                                statusText = ""
                            }
                        }
                    }
                },
            )
            clientRef = client
            client.start()
            onDispose {
                client.stop()
            }
        }
    } else {
        LaunchedEffect(preloadedApps) {
            remoteAppList = preloadedApps
        }
    }

    val unifiedItems = remember(remoteAppList, localLaunchCounts) {
        remoteAppList.map { app ->
            val localCount = localLaunchCounts[app.appKey] ?: if (app.userId == 0) (localLaunchCounts[app.pkg] ?: 0) else 0
            val combinedCount = max(app.openCount, localCount)
            UnifiedAppItem(
                pkg = app.pkg,
                activityClass = app.cls,
                label = app.label,
                openCount = combinedCount,
                userId = app.userId,
                profileType = app.profileType,
            )
        }
    }

    fun openOverlayPermissionSettings() {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${ctx.packageName}"),
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
        } catch (_: Throwable) {
            Toast.makeText(ctx, ctx.getString(R.string.picker_overlay_manual_toast), Toast.LENGTH_LONG).show()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.48f))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onDismiss,
            )
            .padding(18.dp),
        contentAlignment = Alignment.Center,
    ) {
        UnifiedAppLauncherPanel(
            items = unifiedItems,
            searchPlaceholder = stringResource(R.string.picker_search_placeholder, resolvedPeerName),
            maxWidth = 520.dp,
            maxHeight = 620.dp,
            statusText = statusText,
            errorText = errorText,
            onRetry = { retryNonce++ },
            headerContent = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        OpalText(text = stringResource(R.string.picker_title), fontSize = 17.5.sp, fontWeight = FontWeight.Bold)
                        OpalText(
                            text = stringResource(R.string.picker_subtitle, resolvedPeerName),
                            fontSize = 12.sp,
                            color = OpalPalette.TextSecondary,
                            maxLines = 1,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    OpalButton(
                        label = stringResource(R.string.picker_mirror_btn),
                        accent = OpalPalette.AccentCyan,
                        compact = true,
                        onClick = {
                            if (!Settings.canDrawOverlays(ctx)) {
                                hasOverlayPerm = false
                                openOverlayPermissionSettings()
                                return@OpalButton
                            }
                            val started = PeerFloatingAppService.startMirror(
                                ctx = ctx,
                                host = host,
                                port = port,
                                peerLabel = resolvedPeerName,
                                handoverToken = handoverToken,
                            )
                            if (started) {
                                Toast.makeText(ctx, ctx.getString(R.string.picker_opening_mirror_toast, resolvedPeerName), Toast.LENGTH_SHORT).show()
                                onFloatingLaunched()
                            }
                        },
                    )
                    Spacer(Modifier.width(6.dp))
                    OpalButton(label = stringResource(R.string.common_close), compact = true, filled = false, onClick = onDismiss)
                }
            },
            bannerContent = if (!hasOverlayPerm) {
                {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(OpalPalette.AccentAmber.copy(alpha = 0.14f))
                            .border(0.8.dp, OpalPalette.AccentAmber.copy(alpha = 0.42f), RoundedCornerShape(14.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OpalStatusBadge(label = stringResource(R.string.picker_overlay_badge), color = OpalPalette.AccentAmber)
                        OpalText(
                            text = stringResource(R.string.picker_overlay_desc),
                            fontSize = 12.sp,
                            color = OpalPalette.TextSecondary,
                        )
                        OpalButton(
                            label = stringResource(R.string.picker_overlay_btn),
                            accent = OpalPalette.AccentBlue,
                            compact = true,
                            onClick = ::openOverlayPermissionSettings,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            } else {
                null
            },
            onSelectApp = { app ->
                if (!Settings.canDrawOverlays(ctx)) {
                    hasOverlayPerm = false
                    openOverlayPermissionSettings()
                    return@UnifiedAppLauncherPanel
                }
                AppPrefs.recordAppLaunch(ctx, app.appKey)
                val badge = if (app.isSecondaryProfile) {
                    io.github.lengsukq.opaldesk.compositor.AppCatalogRepository.formatProfileBadge(
                        ctx, app.profileType, app.userId,
                    )
                } else {
                    ""
                }
                val displayLabel = if (badge.isNotEmpty()) "${app.label} · $badge" else app.label
                val started = PeerFloatingAppService.start(
                    ctx = ctx,
                    host = host,
                    port = port,
                    peerLabel = resolvedPeerName,
                    appPackage = app.pkg,
                    appActivity = app.activityClass,
                    appLabel = displayLabel,
                    appUserId = app.userId,
                    handoverToken = handoverToken,
                )
                if (started) {
                    Toast.makeText(ctx, ctx.getString(R.string.picker_opening_app_toast, displayLabel), Toast.LENGTH_SHORT).show()
                    onFloatingLaunched()
                }
            },
        )
    }
}
