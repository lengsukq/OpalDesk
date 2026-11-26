package io.github.lengsukq.opaldesk.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lengsukq.opaldesk.compositor.AppCatalogRepository
import io.github.lengsukq.opaldesk.guide.OpalGuideItem
import io.github.lengsukq.opaldesk.guide.OpalGuidePolicy
import io.github.lengsukq.opaldesk.guide.OpalGuideStore
import io.github.lengsukq.opaldesk.prefs.AppPrefs

fun openAllowedGuideUrl(ctx: Context, url: String) {
    if (!OpalGuidePolicy.isAllowedExternalLinkUrl(url)) {
        Toast.makeText(ctx, "仅支持打开受信任的官方 HTTPS 页面", Toast.LENGTH_SHORT).show()
        return
    }
    val ok = runCatching {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    }.getOrDefault(false)
    if (!ok) {
        Toast.makeText(ctx, "未检测到可用浏览器，请手动访问：$url", Toast.LENGTH_LONG).show()
    }
}

/**
 * Built-in OpalDesk Feature Guide desktop icon tile rendered alongside app shortcuts.
 */
@Composable
fun OpalGuideDesktopIconTile(
    onOpen: () -> Unit,
    onContextMenu: (Offset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val selfIcon = remember(ctx.packageName) { AppCatalogRepository.getAppIcon(ctx, ctx.packageName) }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .width(74.dp)
            .clip(RoundedCornerShape(12.dp))
            .onTapOrContextAction(
                "opaldesk_builtin_guide",
                onPrimaryTap = onOpen,
                onContextAction = onContextMenu,
            )
            .padding(vertical = 6.dp, horizontal = 4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .shadow(8.dp, RoundedCornerShape(13.dp))
                .clip(RoundedCornerShape(13.dp))
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF0A84FF), Color(0xFF38BDF8)),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selfIcon != null) {
                Image(
                    bitmap = selfIcon,
                    contentDescription = "使用指南",
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                OpalGuideVectorMark(size = 24.dp)
            }
        }
        Spacer(Modifier.height(5.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(7.dp))
                .background(OpalPalette.GlassSurface.copy(alpha = if (OpalPalette.isDark) 0.52f else 0.72f))
                .padding(horizontal = 6.dp, vertical = 1.5.dp),
        ) {
            OpalText(
                text = "使用指南",
                fontSize = 10.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = OpalPalette.TextPrimary,
                maxLines = 1,
            )
        }
    }
}

/**
 * Anchored right-click / long-press menu for the built-in desktop "使用指南" icon.
 */
@Composable
fun GuideIconContextMenu(
    onOpenGuide: () -> Unit,
    onHideFromDesktop: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .width(214.dp)
            .liquidGlassSurface(
                shape = shape,
                fill = OpalPalette.SolidPopoverFill,
                rim = OpalPalette.SolidPopoverRim,
                elevation = 22.dp,
            )
            .pointerInput(Unit) { detectTapGestures {} }
            .padding(7.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        OpalText(
            text = "OpalDesk 使用指南",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = OpalPalette.TextSecondary,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
        )
        GuideMenuActionRow(
            title = "打开使用指南与特性",
            accent = OpalPalette.AccentBlue,
            onClick = {
                onDismiss()
                onOpenGuide()
            },
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp)
                .height(0.75.dp)
                .background(OpalPalette.DividerColor),
        )
        GuideMenuActionRow(
            title = "从桌面隐藏此图标",
            accent = OpalPalette.AccentDanger,
            onClick = {
                onDismiss()
                onHideFromDesktop()
            },
        )
    }
}

@Composable
private fun GuideMenuActionRow(
    title: String,
    accent: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        OpalText(
            text = title,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Medium,
            color = accent,
            maxLines = 1,
        )
    }
}

/**
 * Centered Liquid Glass built-in Feature Guide window inside DeX Desktop (zero VirtualDisplay usage).
 */
@Composable
fun DexFeatureGuideWindow(
    maxHeight: Dp,
    onAction: (action: String, url: String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val guideState by OpalGuideStore.stateFlow.collectAsState()
    val showDesktopIcon by AppPrefs.showDesktopGuideIconFlow.collectAsState()

    LaunchedEffect(Unit) {
        AppPrefs.hydrateFlows(ctx)
        OpalGuideStore.refreshIfStale(ctx, force = false)
    }

    val doc = guideState.document
    var selectedTab by remember(doc.sections.size) { mutableIntStateOf(0) }
    val safeTab = selectedTab.coerceIn(0, (doc.sections.size - 1).coerceAtLeast(0))
    val activeSection = doc.sections.getOrNull(safeTab)
    val shape = RoundedCornerShape(22.dp)
    val cardShape = RoundedCornerShape(14.dp)

    Column(
        modifier = modifier
            .widthIn(max = 640.dp)
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .liquidGlassSurface(
                shape = shape,
                fill = OpalPalette.SolidPopoverFill,
                rim = OpalPalette.SolidPopoverRim,
                elevation = 28.dp,
            )
            .pointerInput(Unit) { detectTapGestures {} }
            .padding(14.dp),
    ) {
        // Window Title Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
                modifier = Modifier.weight(1f),
            ) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            Brush.linearGradient(listOf(Color(0xFF0A84FF), Color(0xFF38BDF8))),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    val selfIcon = remember(ctx.packageName) {
                        AppCatalogRepository.getAppIcon(ctx, ctx.packageName)
                    }
                    if (selfIcon != null) {
                        Image(bitmap = selfIcon, contentDescription = null, modifier = Modifier.fillMaxSize())
                    } else {
                        OpalGuideVectorMark(size = 16.dp)
                    }
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        OpalText(
                            text = doc.headline,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                        OpalStatusBadge(
                            label = OpalGuidePolicy.formatSourceLabel(
                                fromRemote = guideState.fromRemote,
                                updatedAt = doc.updatedAt,
                                refreshing = guideState.refreshing,
                            ),
                            color = when {
                                guideState.refreshing -> OpalPalette.AccentCyan
                                guideState.fromRemote -> OpalPalette.AccentEmerald
                                else -> OpalPalette.AccentBlue
                            },
                        )
                    }
                    OpalText(
                        text = doc.subtitle,
                        fontSize = 11.sp,
                        color = OpalPalette.TextSecondary,
                        maxLines = 1,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                OpalButton(
                    label = if (guideState.refreshing) "同步中…" else "刷新",
                    compact = true,
                    filled = false,
                    enabled = !guideState.refreshing,
                    accent = OpalPalette.AccentBlue,
                    onClick = { OpalGuideStore.refreshIfStale(ctx, force = true) },
                )
                OpalButton(
                    label = "关闭",
                    compact = true,
                    filled = false,
                    onClick = onDismiss,
                )
            }
        }

        if (guideState.lastError.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            OpalText(
                text = guideState.lastError,
                fontSize = 11.sp,
                color = OpalPalette.AccentAmber,
            )
        }

        // Dynamic Section Tabs (auto-adapts if hot-updated JSON adds/renames sections)
        if (doc.sections.size > 1) {
            Spacer(Modifier.height(10.dp))
            OpalSegmentedRow(
                options = doc.sections.map { it.title },
                selectedIndex = safeTab,
                onSelect = { selectedTab = it },
            )
        }

        Spacer(Modifier.height(10.dp))

        // Scrollable Body
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false),
        ) {
            val useTwoColumns = maxWidth >= 460.dp
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                // Announcement Banner (hot-updateable!)
                doc.announcement?.let { ann ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(cardShape)
                            .background(OpalPalette.AccentBlue.copy(alpha = if (OpalPalette.isDark) 0.16f else 0.10f))
                            .border(0.85.dp, OpalPalette.AccentBlue.copy(alpha = 0.42f), cardShape)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            OpalStatusBadge(label = ann.badge, color = OpalPalette.AccentBlue)
                            if (ann.title.isNotEmpty()) {
                                OpalText(
                                    text = ann.title,
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = OpalPalette.TextPrimary,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                        if (ann.body.isNotEmpty()) {
                            OpalText(
                                text = ann.body,
                                fontSize = 11.5.sp,
                                color = OpalPalette.TextSecondary,
                                lineHeight = 16.5.sp,
                            )
                        }
                    }
                }

                // Active Section Items (2-column grid on wide displays, 1-column on compact phone)
                val items = activeSection?.items.orEmpty()
                if (useTwoColumns) {
                    items.chunked(2).forEach { pair ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(9.dp),
                        ) {
                            pair.forEach { item ->
                                GuideFeatureCard(
                                    item = item,
                                    onTriggerAction = { onAction(item.action, "") },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            if (pair.size == 1) {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                } else {
                    items.forEach { item ->
                        GuideFeatureCard(
                            item = item,
                            onTriggerAction = { onAction(item.action, "") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }

        // Pinned Footer: Hot-updateable Links + Desktop Icon Visibility Toggle
        Spacer(Modifier.height(10.dp))
        Box(modifier = Modifier.fillMaxWidth().height(0.8.dp).background(OpalPalette.DividerColor))
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                doc.links.take(3).forEach { link ->
                    OpalButton(
                        label = link.label,
                        compact = true,
                        filled = false,
                        accent = if (link.action == OpalGuidePolicy.ACTION_JOIN_QQ) {
                            OpalPalette.AccentEmerald
                        } else {
                            OpalPalette.AccentBlue
                        },
                        onClick = { onAction(link.action, link.url) },
                    )
                }
            }
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(OpalPalette.ChipFill)
                    .border(0.75.dp, OpalPalette.BorderHairline, CircleShape)
                    .clickable {
                        val next = !showDesktopIcon
                        AppPrefs.setDesktopGuideIconVisible(ctx, next)
                        Toast.makeText(
                            ctx,
                            if (next) "已在 DeX 桌面显示「使用指南」图标" else "已隐藏桌面「使用指南」图标，可在设置中随时恢复",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    .padding(horizontal = 9.dp, vertical = 5.dp),
            ) {
                OpalText(
                    text = if (showDesktopIcon) "隐藏桌面图标" else "恢复桌面图标",
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = OpalPalette.TextSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Full-screen scrim wrapper used inside MainActivity Console when opening the Feature Guide.
 */
@Composable
fun ConsoleFeatureGuideModal(
    onAction: (action: String, url: String) -> Unit,
    onDismiss: () -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(OpalPalette.ScrimColor)
            .pointerInput(Unit) { detectTapGestures { onDismiss() } }
            .padding(horizontal = 14.dp, vertical = 28.dp),
        contentAlignment = Alignment.Center,
    ) {
        val modalMaxH = (maxHeight - 24.dp).coerceAtLeast(320.dp)
        DexFeatureGuideWindow(
            maxHeight = modalMaxH,
            onAction = onAction,
            onDismiss = onDismiss,
        )
    }
}

@Composable
private fun GuideFeatureCard(
    item: OpalGuideItem,
    onTriggerAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cardShape = RoundedCornerShape(13.dp)
    val accent = when (item.accent) {
        "emerald" -> OpalPalette.AccentEmerald
        "cyan" -> OpalPalette.AccentCyan
        "amber" -> OpalPalette.AccentAmber
        else -> OpalPalette.AccentBlue
    }
    Column(
        modifier = modifier
            .clip(cardShape)
            .background(OpalPalette.ChipFill)
            .border(0.75.dp, OpalPalette.BorderHairline, cardShape)
            .padding(horizontal = 11.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f),
            ) {
                if (item.badge.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(accent.copy(alpha = if (OpalPalette.isDark) 0.22f else 0.13f))
                            .padding(horizontal = 6.dp, vertical = 1.5.dp),
                    ) {
                        OpalText(
                            text = item.badge,
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = accent,
                            maxLines = 1,
                        )
                    }
                }
                OpalText(
                    text = item.title,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = OpalPalette.TextPrimary,
                    maxLines = 1,
                )
            }
            if (item.action.isNotEmpty() && item.actionLabel.isNotEmpty()) {
                Spacer(Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(OpalPalette.ChipActiveFill)
                        .clickable(onClick = onTriggerAction)
                        .padding(horizontal = 7.dp, vertical = 2.5.dp),
                ) {
                    OpalText(
                        text = item.actionLabel,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = accent,
                        maxLines = 1,
                    )
                }
            }
        }
        OpalText(
            text = item.body,
            fontSize = 11.5.sp,
            color = OpalPalette.TextSecondary,
            lineHeight = 16.5.sp,
        )
    }
}

@Composable
private fun OpalGuideVectorMark(size: Dp) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val sw = 1.7.dp.toPx()
        drawRoundRect(
            color = Color.White,
            topLeft = Offset(w * 0.12f, h * 0.16f),
            size = Size(w * 0.76f, h * 0.68f),
            cornerRadius = CornerRadius(3.dp.toPx()),
            style = Stroke(sw),
        )
        drawLine(Color.White, Offset(w * 0.12f, h * 0.40f), Offset(w * 0.88f, h * 0.40f), sw * 0.85f)
        drawCircle(Color.White, radius = w * 0.06f, center = Offset(w * 0.30f, h * 0.62f))
        drawLine(Color.White, Offset(w * 0.44f, h * 0.62f), Offset(w * 0.72f, h * 0.62f), sw * 0.9f)
    }
}
