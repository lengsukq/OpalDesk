package io.github.lengsukq.opaldesk.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lengsukq.opaldesk.R
import io.github.lengsukq.opaldesk.compositor.AppCatalogRepository
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import io.github.lengsukq.opaldesk.protocol.AppProfileKey
import java.text.Collator
import java.util.Locale
import kotlin.math.abs

private val APP_TILE_SHAPE = RoundedCornerShape(16.dp)
private val APP_ICON_SHAPE = RoundedCornerShape(14.dp)
private val LAUNCHER_PANEL_SHAPE = RoundedCornerShape(24.dp)

/**
 * Unified app entry shared identically by both Local/External DeX App Drawer
 * and Remote Standalone Floating App Picker.
 */
data class UnifiedAppItem(
    val pkg: String,
    val activityClass: String,
    val label: String,
    val openCount: Int = 0,
    val isPinnedDesktop: Boolean = false,
    val isPinnedDock: Boolean = false,
    val userId: Int = 0,
    val profileType: String = AppProfileKey.PROFILE_PRIMARY,
) {
    val appKey: String get() = AppProfileKey.format(pkg, userId)
    val isSecondaryProfile: Boolean get() = userId > 0
}

/**
 * Pure deterministic search + frequency/alphabetical sorting shared across all OpalDesk app pickers.
 */
object AppLauncherSorter {
    fun filterAndSort(
        items: List<UnifiedAppItem>,
        query: String,
        sortByUsage: Boolean,
        locale: Locale = Locale.getDefault(),
    ): List<UnifiedAppItem> {
        val q = query.trim().lowercase(locale)
        val filtered = if (q.isEmpty()) {
            items
        } else {
            items.filter { item ->
                item.label.lowercase(locale).contains(q) ||
                    item.pkg.lowercase(Locale.ROOT).contains(q) ||
                    (item.isSecondaryProfile && AppProfileKey.searchKeywords(item.profileType, item.userId).any { kw ->
                        kw.lowercase(locale).contains(q)
                    })
            }
        }
        val collator = Collator.getInstance(locale)
        return if (sortByUsage) {
            filtered.sortedWith(
                Comparator { a, b ->
                    val cmpCount = b.openCount.compareTo(a.openCount)
                    if (cmpCount != 0) return@Comparator cmpCount
                    val cmpLabel = collator.compare(a.label, b.label)
                    if (cmpLabel != 0) cmpLabel else a.userId.compareTo(b.userId)
                },
            )
        } else {
            filtered.sortedWith { a, b ->
                val cmpLabel = collator.compare(a.label, b.label)
                if (cmpLabel != 0) cmpLabel else a.userId.compareTo(b.userId)
            }
        }
    }
}

private val AVATAR_SEED_PALETTE = listOf(
    Color(0xFF0A84FF),
    Color(0xFF34C759),
    Color(0xFF5E5CE6),
    Color(0xFFFF9F0A),
    Color(0xFF32ADE6),
    Color(0xFFFF375F),
    Color(0xFFAF52DE),
)

private fun seedAvatarColor(pkg: String): Color =
    AVATAR_SEED_PALETTE[abs(pkg.hashCode()) % AVATAR_SEED_PALETTE.size]

/**
 * Single reusable Liquid Glass App Launcher Grid shared by:
 * 1. Local / External DeX App Drawer (`DexAppDrawerPopover`)
 * 2. Remote Standalone Floating Window Picker (`RemoteAppPickerModal`)
 *
 * Includes live search, global "按打开次数排序" toggle (default ON, persisted in [AppPrefs]),
 * subtle open-count badges, icon rendering with clean monogram fallback, and optional context menus.
 */
@Composable
fun UnifiedAppLauncherPanel(
    items: List<UnifiedAppItem>,
    searchPlaceholder: String,
    onSelectApp: (UnifiedAppItem) -> Unit,
    modifier: Modifier = Modifier,
    maxWidth: Dp = 580.dp,
    maxHeight: Dp = 480.dp,
    statusText: String = "",
    errorText: String = "",
    onRetry: (() -> Unit)? = null,
    onContextApp: ((UnifiedAppItem, Offset) -> Unit)? = null,
    headerContent: (@Composable ColumnScope.() -> Unit)? = null,
    bannerContent: (@Composable ColumnScope.() -> Unit)? = null,
    topControlsContent: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val sortByUsage by AppPrefs.sortAppsByUsageFlow.collectAsState()
    var query by remember { mutableStateOf("") }

    val sortedItems = remember(items, query, sortByUsage) {
        AppLauncherSorter.filterAndSort(items, query, sortByUsage)
    }

    Column(
        modifier = modifier
            .widthIn(max = maxWidth)
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .liquidGlassSurface(
                shape = LAUNCHER_PANEL_SHAPE,
                fill = OpalPalette.LiquidPopoverFill,
                rim = OpalPalette.LiquidGlassRim,
                elevation = 28.dp,
            )
            .pointerInput(Unit) { detectTapGestures {} }
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        headerContent?.invoke(this)
        bannerContent?.invoke(this)

        // Search input + live count + unified "按打开次数排序" toggle pill
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            OpalTextInput(
                value = query,
                onValueChange = { query = it },
                placeholder = searchPlaceholder,
                modifier = Modifier.weight(1f),
            )
            val pillShape = CircleShape
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier
                    .clip(pillShape)
                    .background(
                        if (sortByUsage) OpalPalette.AccentBlue.copy(alpha = 0.18f) else OpalPalette.ChipFill,
                    )
                    .border(
                        width = 0.85.dp,
                        color = if (sortByUsage) OpalPalette.AccentBlue.copy(alpha = 0.55f) else OpalPalette.BorderSubtle,
                        shape = pillShape,
                    )
                    .clickable { AppPrefs.setSortAppsByUsage(ctx, !sortByUsage) }
                    .padding(horizontal = 10.dp, vertical = 7.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(if (sortByUsage) OpalPalette.AccentBlue else OpalPalette.TextMuted),
                )
                OpalText(
                    text = stringResource(if (sortByUsage) R.string.launcher_sort_usage else R.string.launcher_sort_name),
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (sortByUsage) OpalPalette.AccentBlue else OpalPalette.TextSecondary,
                    maxLines = 1,
                )
            }
            OpalText(
                text = "${sortedItems.size}",
                fontSize = 11.5.sp,
                color = OpalPalette.TextMuted,
                maxLines = 1,
            )
        }

        topControlsContent?.invoke(this)

        when {
            errorText.isNotBlank() -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OpalText(text = errorText, fontSize = 13.sp, color = OpalPalette.AccentDanger)
                    if (onRetry != null) {
                        OpalButton(label = stringResource(R.string.launcher_retry), compact = true, onClick = onRetry)
                    }
                }
            }
            statusText.isNotBlank() && items.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    OpalText(text = statusText, fontSize = 13.sp, color = OpalPalette.TextSecondary)
                }
            }
            sortedItems.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    OpalText(text = stringResource(R.string.launcher_empty), fontSize = 13.sp, color = OpalPalette.TextMuted)
                }
            }
            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 84.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(sortedItems, key = { it.appKey }, contentType = { "app_tile" }) { app ->
                        var icon by remember(app.appKey) {
                            mutableStateOf(AppCatalogRepository.getCachedIcon(app.pkg, app.userId))
                        }
                        LaunchedEffect(app.appKey) {
                            if (icon == null) {
                                icon = AppCatalogRepository.loadAppIconAsync(ctx, app.pkg, app.userId, app.profileType)
                            }
                        }
                        val highlighted = app.isPinnedDesktop || app.isPinnedDock
                        val tileBg = if (highlighted) OpalPalette.ChipFill else Color.Transparent
                        val tileModifier = if (onContextApp != null) {
                            Modifier
                                .clip(APP_TILE_SHAPE)
                                .background(tileBg)
                                .onTapOrContextAction(
                                    app.appKey,
                                    onPrimaryTap = { onSelectApp(app) },
                                    onContextAction = { rootPos -> onContextApp(app, rootPos) },
                                )
                                .padding(vertical = 8.dp, horizontal = 4.dp)
                        } else {
                            Modifier
                                .clip(APP_TILE_SHAPE)
                                .background(tileBg)
                                .clickable { onSelectApp(app) }
                                .padding(vertical = 8.dp, horizontal = 4.dp)
                        }

                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = tileModifier,
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                val resolvedIcon = icon
                                if (resolvedIcon != null) {
                                    Image(
                                        bitmap = resolvedIcon,
                                        contentDescription = app.label,
                                        filterQuality = FilterQuality.Medium,
                                        modifier = Modifier
                                            .size(50.dp)
                                            .clip(APP_ICON_SHAPE),
                                    )
                                } else {
                                    val tint = seedAvatarColor(app.pkg)
                                    val isDark = OpalPalette.isDark
                                    Box(
                                        modifier = Modifier
                                            .size(50.dp)
                                            .clip(APP_ICON_SHAPE)
                                            .background(tint.copy(alpha = if (isDark) 0.24f else 0.14f))
                                            .border(
                                                0.85.dp,
                                                tint.copy(alpha = if (isDark) 0.45f else 0.28f),
                                                APP_ICON_SHAPE,
                                            ),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        OpalText(
                                            text = app.label.trim().take(1).ifEmpty { "A" },
                                            fontSize = 19.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = tint,
                                        )
                                    }
                                }
                                if (app.isSecondaryProfile) {
                                    val badgeText = AppCatalogRepository.formatProfileBadge(ctx, app.profileType, app.userId)
                                    val badgeColor = when (AppProfileKey.normalizeProfileType(app.profileType, app.userId)) {
                                        AppProfileKey.PROFILE_CLONE -> OpalPalette.AccentAmber
                                        AppProfileKey.PROFILE_WORK -> OpalPalette.AccentBlue
                                        else -> Color(0xFF8B5CF6)
                                    }
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.BottomEnd)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(badgeColor)
                                            .border(1.dp, OpalPalette.GlassPopover, RoundedCornerShape(6.dp))
                                            .padding(horizontal = 4.dp, vertical = 1.dp),
                                    ) {
                                        OpalText(
                                            text = badgeText,
                                            fontSize = 8.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
                                            maxLines = 1,
                                        )
                                    }
                                }
                                if (sortByUsage && app.openCount > 0) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .clip(CircleShape)
                                            .background(OpalPalette.AccentBlue)
                                            .border(1.dp, OpalPalette.GlassPopover, CircleShape)
                                            .padding(horizontal = 5.dp, vertical = 1.dp),
                                    ) {
                                        OpalText(
                                            text = if (app.openCount > 99) "99+" else "${app.openCount}",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
                                            maxLines = 1,
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            OpalText(
                                text = if (highlighted) "★ ${app.label}" else app.label,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                color = OpalPalette.TextPrimary,
                            )
                        }
                    }
                }
            }
        }
    }
}
