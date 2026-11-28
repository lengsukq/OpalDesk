package io.github.lengsukq.opaldesk.compositor

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lengsukq.opaldesk.BridgeClient
import io.github.lengsukq.opaldesk.R
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol
import io.github.lengsukq.opaldesk.protocol.CompositorGeometry
import io.github.lengsukq.opaldesk.protocol.CompositorScene
import io.github.lengsukq.opaldesk.protocol.CompositorWindow
import io.github.lengsukq.opaldesk.protocol.WindowTilingLayout
import io.github.lengsukq.opaldesk.ui.ChromeGlyph
import io.github.lengsukq.opaldesk.ui.OpalButton
import io.github.lengsukq.opaldesk.ui.OpalGlassCard
import io.github.lengsukq.opaldesk.ui.OpalGlyphButton
import io.github.lengsukq.opaldesk.ui.OpalGuideDesktopIconTile
import io.github.lengsukq.opaldesk.ui.OpalPalette
import io.github.lengsukq.opaldesk.ui.OpalStatusBadge
import io.github.lengsukq.opaldesk.ui.OpalText
import io.github.lengsukq.opaldesk.ui.onTapOrContextAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Single-source-of-truth multi-window tiling dispatcher shared by Top Menu Bar,
 * Control Center, Home Console Bento Grid, and physical keyboard shortcuts.
 */
fun applyWindowTilingLayout(
    layout: WindowTilingLayout,
    scene: CompositorScene,
    canvasW: Int,
    canvasH: Int,
    density: Float,
    dockInsetDp: Float = CompositorGeometry.DOCK_INSET_DP,
) {
    val candidates = scene.visibleWindows.sortedByDescending { it.zOrder }
        .ifEmpty { scene.windows.sortedByDescending { it.zOrder } }
    if (candidates.isEmpty()) return
    val frames = CompositorGeometry.computeTilingFrames(
        layout = layout,
        windowCount = candidates.size,
        canvasW = canvasW,
        canvasH = canvasH,
        density = density,
        dockInsetDp = dockInsetDp,
    )
    frames.forEachIndexed { idx, rect ->
        val win = candidates[idx]
        if (win.isMinimized) {
            BridgeClient.sendWindowActionAsync(win.windowId, BridgeProtocol.WinAction.FOCUS)
        }
        BridgeClient.updateWindowFrameAsync(
            windowId = win.windowId,
            left = rect.left,
            top = rect.top,
            right = rect.right,
            bottom = rect.bottom,
            presentation = BridgeProtocol.Presentation.FLOATING,
        )
    }
}

private sealed interface DesktopGridItem {
    data object BuiltinGuide : DesktopGridItem
    data class App(val entry: InstalledAppEntry) : DesktopGridItem
}

@Composable
internal fun DesktopShortcutColumn(
    allApps: List<InstalledAppEntry>,
    desktopPkgs: List<String>,
    showGuideIcon: Boolean = true,
    rowsPerColumn: Int = 5,
    onOpenGuide: () -> Unit = {},
    onContextGuide: (Offset) -> Unit = {},
    onLaunch: (InstalledAppEntry) -> Unit,
    onContextApp: (String, Offset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val gridItems = remember(allApps, desktopPkgs, showGuideIcon) {
        val byKey = allApps.associateBy { it.appKey }
        val list = ArrayList<DesktopGridItem>(16)
        if (showGuideIcon) {
            list.add(DesktopGridItem.BuiltinGuide)
        }
        val maxApps = if (showGuideIcon) 14 else 15
        desktopPkgs.mapNotNull { byKey[it] }.take(maxApps).forEach { entry ->
            list.add(DesktopGridItem.App(entry))
        }
        list
    }

    val columns = gridItems.chunked(rowsPerColumn.coerceIn(3, 8))
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        columns.forEach { colItems ->
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                colItems.forEach { gridItem ->
                    when (gridItem) {
                        DesktopGridItem.BuiltinGuide -> {
                            OpalGuideDesktopIconTile(
                                onOpen = onOpenGuide,
                                onContextMenu = onContextGuide,
                            )
                        }
                        is DesktopGridItem.App -> {
                            val app = gridItem.entry
                            val icon = remember(app.appKey) {
                                AppCatalogRepository.getAppIcon(ctx, app.pkg, app.userId, app.profileType)
                            }
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .width(74.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .onTapOrContextAction(
                                        app.appKey,
                                        onPrimaryTap = { onLaunch(app) },
                                        onContextAction = { rootPos -> onContextApp(app.appKey, rootPos) },
                                    )
                                    .padding(vertical = 6.dp, horizontal = 4.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .shadow(8.dp, RoundedCornerShape(13.dp))
                                        .clip(RoundedCornerShape(13.dp))
                                        .background(OpalPalette.GlassSurface),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (icon != null) {
                                        Image(
                                            bitmap = icon,
                                            contentDescription = app.label,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    } else {
                                        OpalText(text = app.label.take(1), fontSize = 18.sp, fontWeight = FontWeight.Bold)
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
                                        text = app.label,
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = OpalPalette.TextPrimary,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ExposeWindowSwitcherOverlay(
    windows: List<CompositorWindow>,
    isCompactWidth: Boolean = false,
    onSelectWindow: (CompositorWindow) -> Unit,
    onCloseWindow: (CompositorWindow) -> Unit,
    onMinimizeAll: () -> Unit,
    onCloseAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(OpalPalette.ScrimColor)
            .clickable(onClick = onDismiss)
            .padding(
                horizontal = if (isCompactWidth) 16.dp else 32.dp,
                vertical = if (isCompactWidth) 28.dp else 44.dp,
            ),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    OpalText(
                        text = stringResource(R.string.workspace_expose_title),
                        fontSize = if (isCompactWidth) 17.sp else 20.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    OpalText(
                        text = stringResource(R.string.workspace_expose_sub, windows.size),
                        fontSize = 11.5.sp,
                        color = OpalPalette.TextSecondary,
                        maxLines = 1,
                    )
                }
                if (windows.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OpalButton(
                            label = stringResource(R.string.taskbar_cc_minimize_all),
                            filled = false,
                            compact = true,
                            onClick = onMinimizeAll,
                        )
                        OpalButton(
                            label = stringResource(R.string.workspace_expose_close_all),
                            accent = OpalPalette.AccentDanger,
                            filled = false,
                            compact = true,
                            onClick = onCloseAll,
                        )
                    }
                }
            }
            Spacer(Modifier.height(if (isCompactWidth) 14.dp else 20.dp))
            if (windows.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    OpalText(
                        text = stringResource(R.string.workspace_expose_empty),
                        color = OpalPalette.TextSecondary,
                        fontSize = 14.sp,
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = if (isCompactWidth) 160.dp else 250.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(windows, key = { it.windowId }) { win ->
                        val icon = remember(win.pkg) { AppCatalogRepository.getAppIcon(ctx, win.pkg) }
                        var previewBmp by remember(win.windowId, win.snapshotVersion) {
                            mutableStateOf<Bitmap?>(LiveWindowPreviewRegistry.captureLivePreview(win.windowId))
                        }
                        LaunchedEffect(win.windowId, win.snapshotVersion) {
                            val liveSnap = LiveWindowPreviewRegistry.captureLivePreview(win.windowId)
                            if (liveSnap != null && !liveSnap.isRecycled) {
                                previewBmp = liveSnap
                            } else {
                                previewBmp = withContext(Dispatchers.IO) {
                                    BridgeClient.fetchWindowSnapshotBitmap(win.windowId, win.snapshotVersion)
                                } ?: LiveWindowPreviewRegistry.getCachedPreview(win.windowId)
                            }
                        }

                        OpalGlassCard(
                            modifier = Modifier.clickable { onSelectWindow(win) },
                            cornerRadius = 16.dp,
                            fillColor = OpalPalette.GlassPopover,
                            borderColor = if (win.focused) OpalPalette.AccentBlue else OpalPalette.BorderSubtle,
                            contentPadding = PaddingValues(12.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (icon != null) {
                                    Image(
                                        bitmap = icon,
                                        contentDescription = win.title,
                                        modifier = Modifier
                                            .size(24.dp)
                                            .clip(RoundedCornerShape(6.dp)),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    OpalText(text = win.title, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                    val stateLabel = when {
                                        win.isMinimized -> stringResource(R.string.workspace_win_minimized)
                                        win.isHibernated -> stringResource(R.string.workspace_win_hibernated)
                                        win.focused -> stringResource(R.string.workspace_win_active)
                                        else -> stringResource(R.string.workspace_win_live)
                                    }
                                    OpalText(
                                        text = "$stateLabel · ${win.contentWidth}×${win.contentHeight}",
                                        fontSize = 11.sp,
                                        color = OpalPalette.TextSecondary,
                                        maxLines = 1,
                                    )
                                }
                                OpalGlyphButton(glyph = ChromeGlyph.CLOSE, size = 24.dp, dangerHover = true) {
                                    onCloseWindow(win)
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(124.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(OpalPalette.WindowCanvasBg)
                                    .border(0.6.dp, OpalPalette.BorderHairline, RoundedCornerShape(10.dp)),
                                contentAlignment = Alignment.Center,
                            ) {
                                val bmp = previewBmp
                                if (bmp != null && !bmp.isRecycled) {
                                    Image(
                                        bitmap = bmp.asImageBitmap(),
                                        contentDescription = win.title,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                } else {
                                    OpalText(
                                        text = if (win.isLive) {
                                            stringResource(R.string.workspace_expose_live_vd, win.displayId)
                                        } else {
                                            stringResource(R.string.workspace_expose_tap_wake)
                                        },
                                        fontSize = 11.5.sp,
                                        color = OpalPalette.TextMuted,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun OfflineDaemonPromptCard(
    onOpenConsole: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OpalGlassCard(
        modifier = modifier.widthIn(max = 420.dp).fillMaxWidth(),
        cornerRadius = 20.dp,
        fillColor = OpalPalette.GlassPopover,
        contentPadding = PaddingValues(24.dp),
    ) {
        OpalStatusBadge(label = stringResource(R.string.workspace_daemon_disconnected_badge), color = OpalPalette.AccentAmber)
        Spacer(Modifier.height(12.dp))
        OpalText(text = stringResource(R.string.workspace_daemon_disconnected_title), fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        OpalText(
            text = stringResource(R.string.workspace_daemon_disconnected_desc),
            fontSize = 13.sp,
            color = OpalPalette.TextSecondary,
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            OpalButton(label = stringResource(R.string.workspace_go_activate), onClick = onOpenConsole, modifier = Modifier.weight(1f))
            OpalButton(label = stringResource(R.string.common_back), filled = false, onClick = onExit)
        }
    }
}

/**
 * Crisp macOS-style black-filled, white-outlined arrow pointer rendered at `(positionPx.x, positionPx.y)`
 * on top of the external monitor Presentation canvas.
 */
@Composable
internal fun MacVirtualPointerCursor(positionPx: Offset) {
    Canvas(
        modifier = Modifier
            .offset { IntOffset(positionPx.x.roundToInt(), positionPx.y.roundToInt()) }
            .size(26.dp),
    ) {
        val w = size.width
        val h = size.height
        val arrow = Path().apply {
            moveTo(0f, 0f)
            lineTo(0f, h * 0.84f)
            lineTo(w * 0.24f, h * 0.64f)
            lineTo(w * 0.42f, h * 0.96f)
            lineTo(w * 0.56f, h * 0.89f)
            lineTo(w * 0.38f, h * 0.58f)
            lineTo(w * 0.68f, h * 0.58f)
            close()
        }
        drawPath(path = arrow, color = Color(0xFF0E1015))
        drawPath(
            path = arrow,
            color = Color.White,
            style = Stroke(width = 1.6.dp.toPx(), join = StrokeJoin.Round),
        )
    }
}
