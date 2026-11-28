package io.github.lengsukq.opaldesk.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lengsukq.opaldesk.R

internal const val OPALDESK_QQ_GROUP_NUMBER = "1042317917"

internal fun joinOpalDeskQqGroup(ctx: Context) {
    runCatching {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("OpalDesk QQ Group", OPALDESK_QQ_GROUP_NUMBER))
    }
    val uri = Uri.parse(
        "mqqapi://card/show_pslcard?src_type=internal&version=1&uin=$OPALDESK_QQ_GROUP_NUMBER&card_type=group&source=qrcode",
    )
    val launched = runCatching {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
    val msg = if (launched) {
        ctx.getString(R.string.nav_qq_copied_opening, OPALDESK_QQ_GROUP_NUMBER)
    } else {
        ctx.getString(R.string.nav_qq_copied_manual, OPALDESK_QQ_GROUP_NUMBER)
    }
    Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
}

private data class ConsoleNavItem(
    val index: Int,
    val labelRes: Int,
)

private val CONSOLE_NAV_ITEMS = listOf(
    ConsoleNavItem(0, R.string.nav_tab_workstation),
    ConsoleNavItem(1, R.string.nav_tab_display),
    ConsoleNavItem(2, R.string.nav_tab_settings),
    ConsoleNavItem(3, R.string.nav_tab_logs),
)

/**
 * Modern iOS/macOS-inspired large-title console header that adapts its title and subtitle to the
 * active bottom navigation tab while keeping engine status & update actions one tap away.
 */
@Composable
fun OpalConsoleHeader(
    selectedTab: Int,
    currentVersion: String,
    connected: Boolean,
    rootEnhanced: Boolean,
    availableReleaseTag: String?,
    readyToInstallUpdate: Boolean,
    onOpenActivation: () -> Unit,
    onOpenUpdate: () -> Unit,
    onOpenSearch: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val (title, subtitle) = when (selectedTab) {
        0 -> stringResource(R.string.app_name) to stringResource(R.string.nav_header_workstation_subtitle)
        1 -> stringResource(R.string.nav_header_display_title) to stringResource(R.string.nav_header_display_subtitle)
        2 -> stringResource(R.string.nav_header_settings_title) to stringResource(R.string.nav_header_settings_subtitle)
        else -> stringResource(R.string.nav_header_logs_title) to stringResource(R.string.nav_header_logs_subtitle)
    }
    val isDark = OpalPalette.isDark
    val ctx = LocalContext.current

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                OpalText(
                    text = title,
                    fontSize = 27.sp,
                    fontWeight = FontWeight.Bold,
                    color = OpalPalette.TextPrimary,
                    maxLines = 1,
                )
                if (selectedTab == 0) {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(OpalPalette.ChipFill)
                            .border(0.75.dp, OpalPalette.BorderSubtle, CircleShape)
                            .padding(horizontal = 8.dp, vertical = 2.5.dp),
                    ) {
                        OpalText(
                            text = "v$currentVersion",
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = OpalPalette.TextSecondary,
                            maxLines = 1,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(OpalPalette.AccentBlue.copy(alpha = if (isDark) 0.20f else 0.12f))
                            .border(0.75.dp, OpalPalette.AccentBlue.copy(alpha = 0.42f), CircleShape)
                            .clickable { joinOpalDeskQqGroup(ctx) }
                            .padding(horizontal = 8.dp, vertical = 2.5.dp),
                    ) {
                        OpalText(
                            text = stringResource(R.string.nav_qq_group_badge),
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = OpalPalette.AccentBlue,
                            maxLines = 1,
                        )
                    }
                }
            }
            OpalText(
                text = subtitle,
                fontSize = 12.5.sp,
                color = OpalPalette.TextSecondary,
                maxLines = 1,
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (availableReleaseTag != null) {
                val badgeColor = if (readyToInstallUpdate) OpalPalette.AccentEmerald else OpalPalette.AccentBlue
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(badgeColor.copy(alpha = if (isDark) 0.22f else 0.14f))
                        .border(0.8.dp, badgeColor.copy(alpha = 0.45f), CircleShape)
                        .clickable(onClick = onOpenUpdate)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                ) {
                    OpalText(
                        text = if (readyToInstallUpdate) stringResource(R.string.nav_install_tag, availableReleaseTag) else availableReleaseTag,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = badgeColor,
                        maxLines = 1,
                    )
                }
            }

            if (onOpenSearch != null) {
                ConsoleHeaderSearchButton(onClick = onOpenSearch)
            }

            val statusColor = if (connected) OpalPalette.AccentEmerald else OpalPalette.AccentAmber
            val statusLabel = when {
                connected && rootEnhanced -> stringResource(R.string.nav_status_root)
                connected -> stringResource(R.string.nav_status_ready)
                else -> stringResource(R.string.common_not_activated)
            }
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(statusColor.copy(alpha = if (isDark) 0.20f else 0.13f))
                    .border(0.8.dp, statusColor.copy(alpha = 0.42f), CircleShape)
                    .clickable(onClick = onOpenActivation)
                    .padding(horizontal = 11.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(statusColor),
                )
                OpalText(
                    text = statusLabel,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = statusColor,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Subtle Apple Settings-style section label placed outside grouped glass cards to establish clean
 * visual hierarchy and breathing room.
 */
@Composable
fun ConsoleSectionTitle(
    title: String,
    modifier: Modifier = Modifier,
) {
    OpalText(
        text = title,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = OpalPalette.TextMuted,
        modifier = modifier.padding(start = 8.dp, top = 4.dp),
    )
}

/**
 * Floating Liquid Glass Bottom Navigation Bar for the OpalDesk Console.
 * Floats cleanly above the system navigation bar with custom minimalist Canvas vector icons and
 * smooth pill selection highlights.
 */
@Composable
fun OpalConsoleBottomBar(
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isDark = OpalPalette.isDark
    val barShape = RoundedCornerShape(30.dp)
    val barFill = if (isDark) {
        Brush.verticalGradient(listOf(Color(0xEE1D2434), Color(0xE6131824)))
    } else {
        Brush.verticalGradient(listOf(Color(0xF5FFFFFF), Color(0xEBF4F7FC)))
    }
    val barRim = if (isDark) {
        Brush.verticalGradient(listOf(Color(0x40FFFFFF), Color(0x14FFFFFF)))
    } else {
        Brush.verticalGradient(listOf(Color(0xE6FFFFFF), Color(0x280F172A)))
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .widthIn(max = 440.dp)
                .fillMaxWidth()
                .liquidGlassSurface(
                    shape = barShape,
                    fill = barFill,
                    rim = barRim,
                    elevation = if (isDark) 22.dp else 18.dp,
                )
                .padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CONSOLE_NAV_ITEMS.forEach { item ->
                val selected = item.index == selectedTab
                val pillColor by animateColorAsState(
                    targetValue = if (selected) {
                        OpalPalette.AccentBlue.copy(alpha = if (isDark) 0.24f else 0.13f)
                    } else {
                        Color.Transparent
                    },
                    animationSpec = tween(durationMillis = 180),
                    label = "navPillBg",
                )
                val contentColor by animateColorAsState(
                    targetValue = if (selected) OpalPalette.AccentBlue else OpalPalette.TextMuted,
                    animationSpec = tween(durationMillis = 180),
                    label = "navTint",
                )
                val interactionSource = remember { MutableInteractionSource() }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(24.dp))
                        .background(pillColor)
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                        ) { onSelectTab(item.index) }
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    ConsoleNavIcon(
                        index = item.index,
                        tint = contentColor,
                        selected = selected,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.height(3.dp))
                    OpalText(
                        text = stringResource(item.labelRes),
                        fontSize = 11.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        color = contentColor,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConsoleHeaderSearchButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isDark = OpalPalette.isDark
    val fill = if (isDark) {
        Brush.verticalGradient(listOf(Color(0xD8242C3F), Color(0xCC161B28)))
    } else {
        Brush.verticalGradient(listOf(Color(0xF0FFFFFF), Color(0xD8EDF3FC)))
    }
    val rim = if (isDark) {
        Brush.verticalGradient(listOf(Color(0x48FFFFFF), Color(0x16FFFFFF)))
    } else {
        Brush.verticalGradient(listOf(Color(0xF5FFFFFF), Color(0x300F172A)))
    }
    val tint = OpalPalette.TextPrimary
    Box(
        modifier = modifier
            .size(32.dp)
            .liquidGlassSurface(
                shape = CircleShape,
                fill = fill,
                rim = rim,
                elevation = if (isDark) 8.dp else 6.dp,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(15.dp)) {
            val w = size.width
            val h = size.height
            val stroke = Stroke(
                width = w * 0.11f,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            )
            drawCircle(
                color = tint.copy(alpha = if (isDark) 0.14f else 0.08f),
                radius = w * 0.30f,
                center = Offset(w * 0.43f, h * 0.43f),
            )
            drawCircle(
                color = tint,
                radius = w * 0.30f,
                center = Offset(w * 0.43f, h * 0.43f),
                style = stroke,
            )
            drawLine(
                color = tint,
                start = Offset(w * 0.65f, h * 0.65f),
                end = Offset(w * 0.88f, h * 0.88f),
                strokeWidth = stroke.width * 1.05f,
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun ConsoleNavIcon(
    index: Int,
    tint: Color,
    selected: Boolean,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(
            width = if (selected) w * 0.095f else w * 0.082f,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )
        when (index) {
            0 -> {
                // Workstation / multi-window icon (2x2 rounded panes)
                val gap = w * 0.10f
                val cell = (w - gap) / 2f
                val r = CornerRadius(w * 0.14f, w * 0.14f)
                if (selected) {
                    drawRoundRect(tint.copy(alpha = 0.22f), Offset(0f, 0f), Size(cell, cell), r)
                    drawRoundRect(tint.copy(alpha = 0.22f), Offset(cell + gap, cell + gap), Size(cell, cell), r)
                }
                drawRoundRect(tint, Offset(w * 0.04f, h * 0.04f), Size(cell - w * 0.04f, cell - h * 0.04f), r, style = stroke)
                drawRoundRect(tint, Offset(cell + gap, h * 0.04f), Size(cell - w * 0.04f, cell - h * 0.04f), r, style = stroke)
                drawRoundRect(tint, Offset(w * 0.04f, cell + gap), Size(cell - w * 0.04f, cell - h * 0.04f), r, style = stroke)
                drawRoundRect(tint, Offset(cell + gap, cell + gap), Size(cell - w * 0.04f, cell - h * 0.04f), r, style = stroke)
            }
            1 -> {
                // Display & peripherals icon (sleek monitor with stand)
                val screenH = h * 0.64f
                val r = CornerRadius(w * 0.14f, w * 0.14f)
                if (selected) {
                    drawRoundRect(tint.copy(alpha = 0.18f), Offset(w * 0.06f, h * 0.10f), Size(w * 0.88f, screenH), r)
                }
                drawRoundRect(tint, Offset(w * 0.06f, h * 0.10f), Size(w * 0.88f, screenH), r, style = stroke)
                drawLine(tint, Offset(w * 0.50f, h * 0.74f), Offset(w * 0.50f, h * 0.90f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, Offset(w * 0.28f, h * 0.90f), Offset(w * 0.72f, h * 0.90f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            }
            2 -> {
                // Modern settings sliders icon (3 horizontal sliders with thumbs)
                val ys = floatArrayOf(h * 0.24f, h * 0.50f, h * 0.76f)
                val thumbXs = floatArrayOf(w * 0.34f, w * 0.68f, w * 0.42f)
                val r = w * 0.10f
                for (i in 0..2) {
                    val y = ys[i]
                    val tx = thumbXs[i]
                    drawLine(tint, Offset(w * 0.08f, y), Offset(tx - r, y), strokeWidth = stroke.width, cap = StrokeCap.Round)
                    drawLine(tint, Offset(tx + r, y), Offset(w * 0.92f, y), strokeWidth = stroke.width, cap = StrokeCap.Round)
                    if (selected) {
                        drawCircle(tint.copy(alpha = 0.25f), r, Offset(tx, y))
                    }
                    drawCircle(tint, r, Offset(tx, y), style = stroke)
                }
            }
            else -> {
                // Diagnostic log / terminal document icon
                val r = CornerRadius(w * 0.16f, w * 0.16f)
                if (selected) {
                    drawRoundRect(tint.copy(alpha = 0.18f), Offset(w * 0.10f, h * 0.08f), Size(w * 0.80f, h * 0.84f), r)
                }
                drawRoundRect(tint, Offset(w * 0.10f, h * 0.08f), Size(w * 0.80f, h * 0.84f), r, style = stroke)
                drawLine(tint, Offset(w * 0.28f, h * 0.34f), Offset(w * 0.72f, h * 0.34f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, Offset(w * 0.28f, h * 0.52f), Offset(w * 0.68f, h * 0.52f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, Offset(w * 0.28f, h * 0.70f), Offset(w * 0.54f, h * 0.70f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            }
        }
    }
}
