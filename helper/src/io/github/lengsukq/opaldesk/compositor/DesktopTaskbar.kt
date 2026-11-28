package io.github.lengsukq.opaldesk.compositor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lengsukq.opaldesk.R
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import io.github.lengsukq.opaldesk.protocol.AppProfileKey
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol
import io.github.lengsukq.opaldesk.protocol.CompositorWindow
import io.github.lengsukq.opaldesk.ui.ChromeGlyph
import io.github.lengsukq.opaldesk.ui.LocalOpalColors
import io.github.lengsukq.opaldesk.ui.OpalGlyphIcon
import io.github.lengsukq.opaldesk.ui.OpalPalette
import io.github.lengsukq.opaldesk.ui.OpalSegmentedRow
import io.github.lengsukq.opaldesk.ui.OpalText
import io.github.lengsukq.opaldesk.ui.UnifiedAppItem
import io.github.lengsukq.opaldesk.ui.UnifiedAppLauncherPanel
import io.github.lengsukq.opaldesk.ui.liquidGlassSurface
import io.github.lengsukq.opaldesk.ui.onTapOrContextAction
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private data class TaskbarAppSlot(
    val pkg: String,
    val cls: String,
    val label: String,
    val userId: Int,
    val profileType: String,
) {
    val appKey: String get() = AppProfileKey.format(pkg, userId)
}

/**
 * Centered Floating macOS Liquid Glass Dock (adaptive for both wide tablet/monitor and compact
 * portrait phone screens), preserving Samsung DeX's intuitive Launchpad + Running Apps + Navigation.
 */
@Composable
fun DexMacTaskbar(
    allApps: List<InstalledAppEntry>,
    dockPinnedPkgs: List<String>,
    windows: List<CompositorWindow>,
    showAppDrawer: Boolean,
    showExpose: Boolean,
    showSpotlight: Boolean = false,
    showControlCenter: Boolean = false,
    isCompactWidth: Boolean = false,
    dockOpacity: Float = DockAppearancePolicy.DEFAULT_OPACITY,
    dockScale: Float = DockAppearancePolicy.DEFAULT_SCALE,
    imeActive: Boolean = false,
    onToggleSoftKeyboard: (() -> Unit)? = null,
    onToggleAppDrawer: () -> Unit,
    onOpenSearch: () -> Unit,
    onToggleControlCenter: () -> Unit = {},
    onToggleExpose: () -> Unit,
    onSendBackToFocused: () -> Unit,
    onShowDesktopToggle: () -> Unit,
    onLaunchOrToggleApp: (String, String, Int) -> Unit,
    onContextApp: (String, CompositorWindow?, Offset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val taskbarEntries = remember(dockPinnedPkgs, windows, allApps) {
        val installedByKey = allApps.associateBy { it.appKey }
        val combined = LinkedHashSet<String>()
        dockPinnedPkgs
            .filter { installedByKey.containsKey(it) }
            .take(AppPrefs.MAX_DOCK_PINNED)
            .forEach { combined.add(it) }
        windows.forEach { combined.add(it.appKey) }
        if (combined.isEmpty()) {
            allApps.take(5).forEach { combined.add(it.appKey) }
        }
        combined.take(AppPrefs.MAX_DOCK_PINNED + 6).map { key ->
            val info = installedByKey[key]
            val win = windows.firstOrNull { it.appKey == key }
            val (parsedPkg, parsedUid) = AppProfileKey.parse(key)
            val uid = info?.userId ?: win?.userId ?: parsedUid
            val profileType = info?.profileType?.ifEmpty { win?.profileType.orEmpty() }
                ?: win?.profileType.orEmpty()
            TaskbarAppSlot(
                pkg = info?.pkg ?: win?.pkg ?: parsedPkg,
                cls = info?.activityClass ?: win?.activityClass.orEmpty(),
                label = info?.label ?: win?.title ?: parsedPkg,
                userId = uid,
                profileType = profileType,
            )
        }
    }

    val clampedScale = DockAppearancePolicy.clampScale(dockScale)
    val metrics = remember(clampedScale, isCompactWidth) {
        DockAppearancePolicy.computeMetrics(clampedScale, isCompactWidth)
    }
    val effectScale = DockAppearancePolicy.rimAndEffectScale(dockOpacity)
    val dockShape = RoundedCornerShape(metrics.cornerRadiusDp.dp)
    val tileCorner = ((if (isCompactWidth) 11f else 14f) * clampedScale).coerceIn(8f, 18f).dp
    val iconCorner = ((if (isCompactWidth) 8f else 9f) * clampedScale).coerceIn(6f, 13f).dp
    val leadBtnSize = metrics.leadBtnDp.dp
    val searchBtnSize = metrics.searchBtnDp.dp
    val navBtnSize = metrics.navBtnDp.dp
    val appTileSize = metrics.appTileDp.dp
    val appIconSize = metrics.appIconDp.dp

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = if (isCompactWidth) 8.dp else 14.dp,
                end = if (isCompactWidth) 8.dp else 14.dp,
                bottom = metrics.bottomMarginDp.dp,
            ),
    ) {
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .widthIn(max = metrics.maxWidthDp.dp)
                .liquidGlassSurface(
                    shape = dockShape,
                    fill = OpalPalette.dockGlassFill(dockOpacity),
                    rim = OpalPalette.dockGlassRim(dockOpacity),
                    elevation = (22f * effectScale).dp,
                    borderWidth = if (effectScale > 0.01f) 1.dp else 0.dp,
                    effectAlphaScale = effectScale,
                )
                .padding(
                    horizontal = metrics.horizontalPaddingDp.dp,
                    vertical = metrics.verticalPaddingDp.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(metrics.itemSpacingDp.dp),
        ) {
            GlassDockButton(
                glyph = ChromeGlyph.APPS_GRID,
                active = showAppDrawer,
                size = leadBtnSize,
                onClick = onToggleAppDrawer,
            )
            if (!isCompactWidth) {
                GlassDockButton(
                    glyph = ChromeGlyph.SEARCH,
                    active = showSpotlight,
                    size = searchBtnSize,
                    onClick = onOpenSearch,
                )
            }
            GlassDockSettingButton(
                active = showControlCenter,
                size = if (isCompactWidth) leadBtnSize else searchBtnSize,
                onClick = onToggleControlCenter,
            )

            GlassDockDivider(height = metrics.dividerHeightDp.dp)

            Row(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(metrics.itemSpacingDp.dp),
            ) {
                taskbarEntries.forEach { slot ->
                    val icon = remember(slot.appKey) {
                        AppCatalogRepository.getAppIcon(ctx, slot.pkg, slot.userId, slot.profileType)
                    }
                    val runningWin = windows.firstOrNull { it.pkg == slot.pkg && it.userId == slot.userId }
                    val isFocused = runningWin != null && runningWin.focused && !runningWin.isMinimized

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .size(width = appTileSize, height = appTileSize)
                            .clip(RoundedCornerShape(tileCorner))
                            .background(
                                when {
                                    isFocused -> OpalPalette.ChipActiveFill
                                    runningWin != null -> OpalPalette.ChipFill
                                    else -> Color.Transparent
                                },
                            )
                            .border(
                                width = if (isFocused) 0.85.dp else 0.dp,
                                color = if (isFocused) OpalPalette.BorderFocused else Color.Transparent,
                                shape = RoundedCornerShape(tileCorner),
                            )
                            .onTapOrContextAction(
                                slot.appKey, runningWin?.windowId, runningWin?.state, runningWin?.focused,
                                onPrimaryTap = { onLaunchOrToggleApp(slot.pkg, slot.cls, slot.userId) },
                                onContextAction = { rootPos -> onContextApp(slot.appKey, runningWin, rootPos) },
                            ),
                    ) {
                        Box(
                            modifier = Modifier.size(appIconSize + 2.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (icon != null) {
                                Image(
                                    bitmap = icon,
                                    contentDescription = slot.label,
                                    alpha = if (runningWin?.isMinimized == true) 0.56f else 1f,
                                    modifier = Modifier
                                        .size(appIconSize)
                                        .clip(RoundedCornerShape(iconCorner)),
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(appIconSize)
                                        .clip(RoundedCornerShape(iconCorner))
                                        .background(OpalPalette.BgElevated),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    OpalText(
                                        text = slot.label.take(1),
                                        fontSize = (12f * clampedScale).coerceIn(10f, 16f).sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height((2f * clampedScale).coerceIn(1.5f, 3f).dp))
                        Box(
                            modifier = Modifier
                                .size(
                                    width = ((if (isFocused) 11f else 4f) * clampedScale).dp,
                                    height = (2.8f * clampedScale).coerceIn(2.2f, 3.8f).dp,
                                )
                                .clip(CircleShape)
                                .background(
                                    when {
                                        runningWin == null -> Color.Transparent
                                        runningWin.isMinimized -> OpalPalette.TextMuted
                                        runningWin.isHibernated -> OpalPalette.AccentAmber
                                        isFocused -> OpalPalette.AccentBlue
                                        else -> OpalPalette.AccentCyan
                                    },
                                ),
                        )
                    }
                }
            }

            GlassDockDivider(height = metrics.dividerHeightDp.dp)

            if (onToggleSoftKeyboard != null) {
                GlassDockButton(
                    glyph = ChromeGlyph.KEYBOARD,
                    active = imeActive,
                    size = navBtnSize,
                    onClick = onToggleSoftKeyboard,
                )
            }
            GlassDockButton(
                glyph = ChromeGlyph.BACK,
                active = false,
                size = navBtnSize,
                onClick = onSendBackToFocused,
            )
            GlassDockButton(
                glyph = ChromeGlyph.HOME_DESKTOP,
                active = false,
                size = navBtnSize,
                onClick = onShowDesktopToggle,
            )
            GlassDockButton(
                glyph = ChromeGlyph.EXPOSE,
                active = showExpose,
                size = navBtnSize,
                onClick = onToggleExpose,
            )
        }
    }
}

@Composable
private fun GlassDockButton(
    glyph: ChromeGlyph,
    active: Boolean,
    size: Dp = 38.dp,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape((size * 0.34f).coerceIn(9.dp, 16.dp))
    Box(
        modifier = Modifier
            .size(size)
            .clip(shape)
            .background(if (active) OpalPalette.AccentBlue else OpalPalette.ChipFill)
            .border(
                width = 0.75.dp,
                color = if (active) OpalPalette.AccentBlue else OpalPalette.BorderHairline,
                shape = shape,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        OpalGlyphIcon(
            glyph = glyph,
            tint = if (active) Color.White else OpalPalette.TextPrimary.copy(alpha = 0.86f),
            size = size * 0.46f,
        )
    }
}

@Composable
private fun GlassDockSettingButton(
    active: Boolean,
    size: Dp = 38.dp,
    onClick: () -> Unit,
) {
    val colors = LocalOpalColors.current
    val accent = colors.accentBlue
    val shape = RoundedCornerShape((size * 0.34f).coerceIn(9.dp, 16.dp))
    val fill = if (active) {
        Brush.verticalGradient(
            listOf(
                accent.copy(alpha = if (colors.isDark) 0.92f else 0.95f),
                colors.accentCyan.copy(alpha = if (colors.isDark) 0.78f else 0.84f),
            ),
        )
    } else {
        Brush.verticalGradient(
            listOf(
                if (colors.isDark) Color(0x28FFFFFF) else Color(0x8AFFFFFF),
                colors.chipFill,
            ),
        )
    }
    val borderBrush = if (active) {
        Brush.linearGradient(
            listOf(
                Color.White.copy(alpha = 0.88f),
                colors.accentCyan.copy(alpha = 0.85f),
            ),
        )
    } else {
        Brush.linearGradient(
            listOf(
                colors.glassRimTop.copy(alpha = if (colors.isDark) 0.40f else 0.88f),
                colors.borderHairline,
            ),
        )
    }
    Box(
        modifier = Modifier
            .size(size)
            .clip(shape)
            .background(fill)
            .border(width = if (active) 1.1.dp else 0.8.dp, brush = borderBrush, shape = shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(size * 0.48f)) {
            val cx = this.size.width * 0.5f
            val cy = this.size.height * 0.5f
            val sw = 1.65.dp.toPx()
            val innerR = this.size.minDimension * 0.17f
            val bodyR = this.size.minDimension * 0.33f
            val toothOut = this.size.minDimension * 0.47f
            val tint = if (active) Color.White else colors.textPrimary.copy(alpha = 0.88f)
            if (active) {
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(Color.White.copy(alpha = 0.36f), Color.Transparent),
                        center = Offset(cx, cy),
                        radius = this.size.minDimension * 0.65f,
                    ),
                    radius = this.size.minDimension * 0.65f,
                    center = Offset(cx, cy),
                )
            }
            drawCircle(color = tint, radius = innerR, center = Offset(cx, cy), style = Stroke(sw))
            drawCircle(color = tint, radius = bodyR, center = Offset(cx, cy), style = Stroke(sw))
            for (i in 0 until 8) {
                val rad = (i * 45.0) * PI / 180.0
                drawLine(
                    color = tint,
                    start = Offset(cx + (bodyR * cos(rad)).toFloat(), cy + (bodyR * sin(rad)).toFloat()),
                    end = Offset(cx + (toothOut * cos(rad)).toFloat(), cy + (toothOut * sin(rad)).toFloat()),
                    strokeWidth = sw,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

@Composable
private fun GlassDockDivider(height: Dp = 26.dp) {
    Box(
        modifier = Modifier
            .padding(horizontal = 3.dp)
            .width(1.dp)
            .height(height)
            .background(OpalPalette.DividerColor),
    )
}

/**
 * Floating Rounded Liquid Glass App Launcher (macOS Launchpad × Samsung DeX Search & Profile Picker).
 * Delegates directly to [UnifiedAppLauncherPanel] so Local DeX and Remote Standalone Floating Window
 * Picker share one identical search, usage-sorting, and grid component.
 */
@Composable
fun DexAppDrawerPopover(
    apps: List<InstalledAppEntry>,
    desktopPkgs: List<String>,
    dockPinnedPkgs: List<String>,
    onLaunch: (InstalledAppEntry, Int) -> Unit,
    onContextApp: (InstalledAppEntry, Offset) -> Unit,
    maxHeight: Dp = 460.dp,
    modifier: Modifier = Modifier,
) {
    val launchCounts by AppPrefs.appLaunchCountsFlow.collectAsState()
    var selectedProfileIdx by remember { mutableIntStateOf(0) }

    val profileCode = when (selectedProfileIdx) {
        1 -> BridgeProtocol.Profile.DESKTOP
        2 -> BridgeProtocol.Profile.PORTRAIT
        3 -> BridgeProtocol.Profile.TABLET
        else -> BridgeProtocol.Profile.AUTO
    }

    val byKey = remember(apps) { apps.associateBy { it.appKey } }
    val unifiedItems = remember(apps, desktopPkgs, dockPinnedPkgs, launchCounts) {
        val desktopSet = desktopPkgs.toHashSet()
        val dockSet = dockPinnedPkgs.toHashSet()
        apps.map { app ->
            UnifiedAppItem(
                pkg = app.pkg,
                activityClass = app.activityClass,
                label = app.label,
                openCount = launchCounts[app.appKey] ?: if (app.userId == 0) (launchCounts[app.pkg] ?: 0) else 0,
                isPinnedDesktop = desktopSet.contains(app.appKey),
                isPinnedDock = dockSet.contains(app.appKey),
                userId = app.userId,
                profileType = app.profileType,
            )
        }
    }

    UnifiedAppLauncherPanel(
        items = unifiedItems,
        searchPlaceholder = stringResource(R.string.taskbar_drawer_search),
        maxWidth = 580.dp,
        maxHeight = maxHeight,
        onSelectApp = { item ->
            val entry = byKey[item.appKey]
                ?: InstalledAppEntry(item.pkg, item.activityClass, item.label, item.userId, item.profileType)
            onLaunch(entry, profileCode)
        },
        onContextApp = { item, rootPos ->
            val entry = byKey[item.appKey]
                ?: InstalledAppEntry(item.pkg, item.activityClass, item.label, item.userId, item.profileType)
            onContextApp(entry, rootPos)
        },
        topControlsContent = {
            OpalSegmentedRow(
                options = listOf(
                    stringResource(R.string.taskbar_profile_auto),
                    stringResource(R.string.taskbar_profile_desktop),
                    stringResource(R.string.taskbar_profile_portrait),
                    stringResource(R.string.taskbar_profile_tablet),
                ),
                selectedIndex = selectedProfileIdx,
                onSelect = { selectedProfileIdx = it },
            )
        },
        modifier = modifier,
    )
}
