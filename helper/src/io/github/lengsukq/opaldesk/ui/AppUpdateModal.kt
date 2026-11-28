package io.github.lengsukq.opaldesk.ui

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lengsukq.opaldesk.R
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import io.github.lengsukq.opaldesk.update.AppUpdateLogic
import io.github.lengsukq.opaldesk.update.AppUpdateManager
import kotlin.math.roundToInt

/**
 * Apple Liquid Glass in-app update dialog supporting changelog preview, live streaming APK
 * download progress with cancellation, download route switching (Direct / China mirrors),
 * one-tap PackageInstaller handoff, and browser fallback.
 */
@Composable
fun AppUpdateModal(
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val currentVersion = remember(ctx) { AppUpdateManager.getCurrentVersionName(ctx) }
    val state by AppUpdateManager.stateFlow.collectAsState()
    val downloadRouteIdx by AppPrefs.updateDownloadRouteFlow.collectAsState()
    val activeRoute = AppUpdateLogic.DownloadRoute.fromIndex(downloadRouteIdx)
    val release = when (val s = state) {
        is AppUpdateManager.UpdateState.UpdateAvailable -> s.release
        is AppUpdateManager.UpdateState.Downloading -> s.release
        is AppUpdateManager.UpdateState.ReadyToInstall -> s.release
        is AppUpdateManager.UpdateState.Error -> s.release
        else -> null
    }
    val isDownloading = state is AppUpdateManager.UpdateState.Downloading

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(OpalPalette.ScrimColor)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
            ) {
                if (!isDownloading) onDismiss()
            }
            .padding(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        val cardShape = RoundedCornerShape(24.dp)
        Column(
            modifier = Modifier
                .widthIn(max = 460.dp)
                .fillMaxWidth()
                .liquidGlassSurface(
                    shape = cardShape,
                    fill = OpalPalette.LiquidPopoverFill,
                    rim = OpalPalette.LiquidGlassRim,
                    elevation = 22.dp,
                )
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                ) {}
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    OpalText(
                        text = if (release != null) stringResource(R.string.update_new_ver_title, release.tagName) else stringResource(R.string.settings_update_title),
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    val meta = buildString {
                        append(ctx.getString(R.string.update_current_prefix, currentVersion))
                        if (release != null) {
                            append(" → ")
                            append(release.tagName)
                            if (release.apkSizeBytes > 0L) {
                                append(" · ")
                                append(AppUpdateLogic.formatByteSize(release.apkSizeBytes))
                            }
                            if (release.publishedDate.isNotEmpty()) {
                                append(" · ")
                                append(release.publishedDate)
                            }
                        }
                    }
                    OpalText(
                        text = meta,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = OpalPalette.TextSecondary,
                    )
                }
                if (!isDownloading) {
                    OpalGlyphButton(
                        glyph = ChromeGlyph.CLOSE,
                        size = 30.dp,
                        onClick = onDismiss,
                    )
                }
            }

            // Changelog box
            if (release != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 72.dp, max = 230.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(OpalPalette.ChipFill)
                        .border(0.75.dp, OpalPalette.BorderHairline, RoundedCornerShape(14.dp))
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                ) {
                    OpalText(
                        text = release.body,
                        fontSize = 12.5.sp,
                        lineHeight = 19.sp,
                        color = OpalPalette.TextPrimary,
                    )
                }
            }

            // Live State Body
            when (val s = state) {
                is AppUpdateManager.UpdateState.Checking -> {
                    OpalText(
                        text = stringResource(R.string.update_checking_msg),
                        fontSize = 13.sp,
                        color = OpalPalette.TextSecondary,
                    )
                }

                is AppUpdateManager.UpdateState.UpToDate -> {
                    OpalText(
                        text = stringResource(R.string.update_up_to_date_msg, s.currentVersion),
                        fontSize = 13.sp,
                        color = OpalPalette.AccentEmerald,
                        fontWeight = FontWeight.Medium,
                    )
                    OpalButton(
                        label = stringResource(R.string.update_got_it),
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                is AppUpdateManager.UpdateState.UpdateAvailable -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OpalButton(
                            label = stringResource(R.string.update_ignore_btn),
                            filled = false,
                            accent = OpalPalette.TextSecondary,
                            onClick = {
                                AppUpdateManager.ignoreRelease(ctx, s.release.tagName)
                                onDismiss()
                            },
                            modifier = Modifier.weight(1f),
                        )
                        OpalButton(
                            label = stringResource(R.string.update_download_install_btn),
                            filled = true,
                            accent = OpalPalette.AccentBlue,
                            onClick = { AppUpdateManager.startDownload(ctx, s.release) },
                            modifier = Modifier.weight(1.4f),
                        )
                    }
                }

                is AppUpdateManager.UpdateState.Downloading -> {
                    val fraction = if (s.totalBytes > 0L) {
                        (s.downloadedBytes.toFloat() / s.totalBytes.toFloat()).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                    val pct = (fraction * 100f).roundToInt()
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OpalText(
                                text = stringResource(R.string.update_downloading_label),
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Medium,
                                color = OpalPalette.TextSecondary,
                            )
                            OpalText(
                                text = if (s.totalBytes > 0L) {
                                    "${AppUpdateLogic.formatByteSize(s.downloadedBytes)} / ${AppUpdateLogic.formatByteSize(s.totalBytes)} · $pct%"
                                } else {
                                    AppUpdateLogic.formatByteSize(s.downloadedBytes)
                                },
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                                color = OpalPalette.AccentBlue,
                            )
                        }
                        // Crisp liquid progress track
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(CircleShape)
                                .background(OpalPalette.ChipFill)
                                .border(0.6.dp, OpalPalette.BorderHairline, CircleShape),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(if (s.totalBytes > 0L) fraction.coerceAtLeast(0.03f) else 0.35f)
                                    .height(8.dp)
                                    .clip(CircleShape)
                                    .background(OpalPalette.AccentBlue),
                            )
                        }
                        Spacer(Modifier.height(2.dp))
                        OpalButton(
                            label = stringResource(R.string.update_cancel_download),
                            filled = false,
                            compact = true,
                            accent = OpalPalette.AccentDanger,
                            onClick = { AppUpdateManager.cancelDownload() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                is AppUpdateManager.UpdateState.ReadyToInstall -> {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OpalText(
                            text = stringResource(R.string.update_ready_hint),
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            color = OpalPalette.TextSecondary,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            OpalButton(
                                label = stringResource(R.string.update_later),
                                filled = false,
                                accent = OpalPalette.TextSecondary,
                                onClick = onDismiss,
                                modifier = Modifier.weight(1f),
                            )
                            OpalButton(
                                label = stringResource(R.string.update_install_now),
                                filled = true,
                                accent = OpalPalette.AccentEmerald,
                                onClick = {
                                    AppUpdateManager.installDownloadedApk(ctx, s.apkFilePath)
                                },
                                modifier = Modifier.weight(1.4f),
                            )
                        }
                    }
                }

                is AppUpdateManager.UpdateState.Error -> {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OpalText(
                            text = s.message,
                            fontSize = 12.5.sp,
                            color = OpalPalette.AccentDanger,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            OpalButton(
                                label = stringResource(R.string.update_browser_download),
                                filled = false,
                                accent = OpalPalette.AccentCyan,
                                onClick = {
                                    AppUpdateManager.openReleaseInBrowser(
                                        ctx,
                                        s.release?.htmlUrl ?: AppUpdateLogic.ALL_RELEASES_WEB_URL,
                                    )
                                },
                                modifier = Modifier.weight(1f),
                            )
                            OpalButton(
                                label = stringResource(R.string.common_retry),
                                filled = true,
                                accent = OpalPalette.AccentBlue,
                                onClick = {
                                    if (s.release != null) {
                                        AppUpdateManager.startDownload(ctx, s.release)
                                    } else {
                                        AppUpdateManager.checkForUpdates(ctx, userInitiated = true)
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                AppUpdateManager.UpdateState.Idle -> {
                    OpalButton(
                        label = stringResource(R.string.update_check_now),
                        onClick = { AppUpdateManager.checkForUpdates(ctx, userInitiated = true) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // Download Route Selector (Direct / China Proxy Mirrors for faster APK downloads)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(OpalPalette.ChipFill)
                    .border(0.7.dp, OpalPalette.BorderHairline, RoundedCornerShape(14.dp))
                    .padding(horizontal = 11.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    OpalText(
                        text = stringResource(R.string.update_route_title),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = OpalPalette.TextSecondary,
                    )
                    OpalText(
                        text = activeRoute.detail,
                        fontSize = 10.5.sp,
                        color = OpalPalette.AccentCyan,
                        maxLines = 1,
                    )
                }
                OpalSegmentedRow(
                    options = listOf(
                        stringResource(R.string.update_route_direct),
                        stringResource(R.string.update_route_proxy1),
                        stringResource(R.string.update_route_proxy2),
                    ),
                    selectedIndex = activeRoute.index,
                    onSelect = { idx ->
                        if (!isDownloading) {
                            AppPrefs.setUpdateDownloadRoute(ctx, idx)
                        }
                    },
                )
            }

            // Secondary GitHub Releases browser link
            if (release != null && !isDownloading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { AppUpdateManager.openReleaseInBrowser(ctx, release.htmlUrl) }
                        .padding(vertical = 2.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    OpalText(
                        text = stringResource(R.string.update_open_releases),
                        fontSize = 11.5.sp,
                        color = OpalPalette.AccentBlue,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}
