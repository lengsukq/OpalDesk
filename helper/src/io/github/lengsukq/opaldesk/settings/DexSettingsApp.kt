package io.github.lengsukq.opaldesk.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lengsukq.opaldesk.BridgeClient
import io.github.lengsukq.opaldesk.compositor.WorkspaceUiState
import io.github.lengsukq.opaldesk.notifications.OpalNotificationStore
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import io.github.lengsukq.opaldesk.prefs.ProfileConfigStore
import io.github.lengsukq.opaldesk.ui.ChromeGlyph
import io.github.lengsukq.opaldesk.ui.LocalOpalColors
import io.github.lengsukq.opaldesk.ui.OpalButton
import io.github.lengsukq.opaldesk.ui.OpalGlyphButton
import io.github.lengsukq.opaldesk.ui.OpalGlyphIcon
import io.github.lengsukq.opaldesk.ui.OpalPalette
import io.github.lengsukq.opaldesk.ui.OpalText
import io.github.lengsukq.opaldesk.ui.OpalTextInput
import io.github.lengsukq.opaldesk.ui.liquidGlassSurface

/**
 * Standalone adaptive DeX Settings overlay for Phone (< 520.dp), Tablet (520..719.dp),
 * and PC / External Monitor (>= 720.dp) built exclusively on Compose Foundation + OpalPrimitives.
 */
@Composable
internal fun DexSettingsOverlay(
    bridge: BridgeClient,
    uiState: WorkspaceUiState,
    initialCategory: DexSettingsCategory = DexSettingsCategory.APPEARANCE,
    onOpenWidgetStore: () -> Unit,
    onToggleWidgetEdit: () -> Unit,
    onOpenKeyBindings: () -> Unit,
    onOpenFaq: () -> Unit,
    onUploadLogs: () -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val languagePref by AppPrefs.languagePrefFlow.collectAsState()
    val isZh = remember(languagePref) { isDexSettingsZh(languagePref) }

    var selectedCategory by remember(initialCategory) { mutableStateOf(initialCategory) }
    var searchQuery by remember { mutableStateOf("") }
    var phoneInDetail by remember(initialCategory) { mutableStateOf(false) }

    val filteredCategories = remember(searchQuery) { filterDexSettingsCategories(searchQuery) }

    LaunchedEffect(Unit) {
        AppPrefs.hydrateFlows(ctx)
        ProfileConfigStore.hydrate(ctx)
        OpalNotificationStore.refreshAccessState(ctx)
        if (bridge.statusFlow.value.connected) {
            bridge.refreshDexImeStateAsync()
        }
    }

    LaunchedEffect(filteredCategories) {
        if (filteredCategories.isNotEmpty() && selectedCategory !in filteredCategories) {
            selectedCategory = filteredCategories.first()
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(OpalPalette.ScrimColor.copy(alpha = if (OpalPalette.isDark) 0.56f else 0.36f))
            .pointerInput(Unit) { detectTapGestures { onDismiss() } }
            .padding(horizontal = 14.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        val layoutMode = computeDexSettingsLayoutMode(maxWidth.value)
        val outerShape = RoundedCornerShape(28.dp)

        val containerModifier = when (layoutMode) {
            DexSettingsLayoutMode.EXPANDED_PC -> Modifier
                .widthIn(max = 980.dp)
                .fillMaxWidth(0.92f)
                .heightIn(max = 680.dp)
                .fillMaxHeight(0.88f)
            DexSettingsLayoutMode.MEDIUM_TABLET -> Modifier
                .widthIn(max = 700.dp)
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.90f)
            DexSettingsLayoutMode.COMPACT_PHONE -> Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.94f)
        }

        Box(
            modifier = containerModifier
                .liquidGlassSurface(
                    shape = outerShape,
                    fill = OpalPalette.SolidPopoverFill,
                    rim = OpalPalette.LiquidGlassRim,
                    elevation = 28.dp,
                    borderWidth = 1.1.dp,
                )
                .pointerInput(Unit) { detectTapGestures {} },
        ) {
            when (layoutMode) {
                DexSettingsLayoutMode.EXPANDED_PC,
                DexSettingsLayoutMode.MEDIUM_TABLET,
                -> {
                    val sidebarWidth = if (layoutMode == DexSettingsLayoutMode.EXPANDED_PC) 228.dp else 188.dp
                    DexSettingsDualPaneLayout(
                        sidebarWidth = sidebarWidth,
                        isZh = isZh,
                        searchQuery = searchQuery,
                        onSearchChange = { searchQuery = it },
                        categories = filteredCategories,
                        selectedCategory = selectedCategory,
                        onSelectCategory = { selectedCategory = it },
                        onDismiss = onDismiss,
                    ) {
                        DexSettingsCategoryContent(
                            category = selectedCategory,
                            bridge = bridge,
                            uiState = uiState,
                            isZh = isZh,
                            onOpenWidgetStore = onOpenWidgetStore,
                            onToggleWidgetEdit = onToggleWidgetEdit,
                            onOpenKeyBindings = onOpenKeyBindings,
                            onOpenFaq = onOpenFaq,
                            onUploadLogs = onUploadLogs,
                            onDismiss = onDismiss,
                        )
                    }
                }

                DexSettingsLayoutMode.COMPACT_PHONE -> {
                    DexSettingsPhoneLayout(
                        isZh = isZh,
                        inDetail = phoneInDetail,
                        searchQuery = searchQuery,
                        onSearchChange = { searchQuery = it },
                        categories = filteredCategories,
                        selectedCategory = selectedCategory,
                        onOpenCategory = { cat ->
                            selectedCategory = cat
                            phoneInDetail = true
                        },
                        onBackToList = { phoneInDetail = false },
                        onDismiss = onDismiss,
                    ) {
                        DexSettingsCategoryContent(
                            category = selectedCategory,
                            bridge = bridge,
                            uiState = uiState,
                            isZh = isZh,
                            onOpenWidgetStore = onOpenWidgetStore,
                            onToggleWidgetEdit = onToggleWidgetEdit,
                            onOpenKeyBindings = onOpenKeyBindings,
                            onOpenFaq = onOpenFaq,
                            onUploadLogs = onUploadLogs,
                            onDismiss = onDismiss,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DexSettingsDualPaneLayout(
    sidebarWidth: Dp,
    isZh: Boolean,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    categories: List<DexSettingsCategory>,
    selectedCategory: DexSettingsCategory,
    onSelectCategory: (DexSettingsCategory) -> Unit,
    onDismiss: () -> Unit,
    detailContent: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalOpalColors.current
    val sidebarBg = if (colors.isDark) Color(0x52121723) else Color(0x66EEF3FB)
    val navScroll = rememberScrollState()
    val detailScroll = rememberScrollState()

    Row(modifier = Modifier.fillMaxSize()) {
        // Left frosted glass navigation sidebar
        Column(
            modifier = Modifier
                .width(sidebarWidth)
                .fillMaxHeight()
                .background(sidebarBg)
                .padding(horizontal = 12.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DexSettingsCategoryIconBadge(
                    glyph = ChromeGlyph.CONTROL_CENTER,
                    accent = OpalPalette.AccentBlue,
                    size = 28.dp,
                )
                OpalText(
                    text = if (isZh) "DeX 设置" else "DeX Settings",
                    fontSize = 15.5.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }

            OpalTextInput(
                value = searchQuery,
                onValueChange = onSearchChange,
                placeholder = if (isZh) "搜索设置项…" else "Search settings…",
                modifier = Modifier.fillMaxWidth(),
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(navScroll),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (categories.isEmpty()) {
                    OpalText(
                        text = if (isZh) "未找到匹配分类" else "No matching categories",
                        fontSize = 12.sp,
                        color = OpalPalette.TextMuted,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
                    )
                } else {
                    categories.forEach { cat ->
                        val meta = cat.meta()
                        val selected = cat == selectedCategory
                        val itemShape = RoundedCornerShape(14.dp)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(itemShape)
                                .background(
                                    if (selected) {
                                        OpalPalette.AccentBlue.copy(alpha = if (colors.isDark) 0.24f else 0.14f)
                                    } else {
                                        Color.Transparent
                                    },
                                )
                                .border(
                                    width = if (selected) 0.85.dp else 0.dp,
                                    color = if (selected) OpalPalette.AccentBlue.copy(alpha = 0.36f) else Color.Transparent,
                                    shape = itemShape,
                                )
                                .clickable { onSelectCategory(cat) }
                                .padding(horizontal = 9.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(9.dp),
                        ) {
                            DexSettingsCategoryIconBadge(
                                glyph = meta.glyph,
                                accent = meta.badgeColor,
                                size = 26.dp,
                            )
                            OpalText(
                                text = meta.title(isZh),
                                fontSize = 13.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                color = if (selected) OpalPalette.TextPrimary else OpalPalette.TextSecondary,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .width(0.8.dp)
                .fillMaxHeight()
                .background(OpalPalette.DividerColor),
        )

        // Right scrollable detail content pane
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(horizontal = 18.dp, vertical = 14.dp),
        ) {
            val meta = selectedCategory.meta()
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    DexSettingsCategoryIconBadge(
                        glyph = meta.glyph,
                        accent = meta.badgeColor,
                        size = 32.dp,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        OpalText(
                            text = meta.title(isZh),
                            fontSize = 17.5.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                        OpalText(
                            text = meta.subtitle(isZh),
                            fontSize = 12.sp,
                            color = OpalPalette.TextSecondary,
                            maxLines = 1,
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                OpalGlyphButton(
                    glyph = ChromeGlyph.CLOSE,
                    size = 30.dp,
                    onClick = onDismiss,
                )
            }

            Spacer(Modifier.height(12.dp))
            DexSettingsDivider()
            Spacer(Modifier.height(12.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(detailScroll),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = detailContent,
            )
        }
    }
}

@Composable
private fun DexSettingsPhoneLayout(
    isZh: Boolean,
    inDetail: Boolean,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    categories: List<DexSettingsCategory>,
    selectedCategory: DexSettingsCategory,
    onOpenCategory: (DexSettingsCategory) -> Unit,
    onBackToList: () -> Unit,
    onDismiss: () -> Unit,
    detailContent: @Composable ColumnScope.() -> Unit,
) {
    val listScroll = rememberScrollState()
    val detailScroll = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 14.dp),
    ) {
        if (!inDetail) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    DexSettingsCategoryIconBadge(
                        glyph = ChromeGlyph.CONTROL_CENTER,
                        accent = OpalPalette.AccentBlue,
                        size = 30.dp,
                    )
                    OpalText(
                        text = if (isZh) "DeX 设置" else "DeX Settings",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                OpalGlyphButton(glyph = ChromeGlyph.CLOSE, size = 30.dp, onClick = onDismiss)
            }

            Spacer(Modifier.height(10.dp))
            OpalTextInput(
                value = searchQuery,
                onValueChange = onSearchChange,
                placeholder = if (isZh) "搜索外观、Dock、投屏、音频、输入法…" else "Search appearance, Dock, cast, audio, IME…",
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(listScroll),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (categories.isEmpty()) {
                    OpalText(
                        text = if (isZh) "未找到匹配分类，试试其他关键词" else "No matching categories found",
                        fontSize = 13.sp,
                        color = OpalPalette.TextMuted,
                        modifier = Modifier.padding(vertical = 20.dp),
                    )
                } else {
                    categories.forEach { cat ->
                        val meta = cat.meta()
                        val cardShape = RoundedCornerShape(20.dp)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(cardShape)
                                .background(OpalPalette.GlassSurface)
                                .border(0.85.dp, OpalPalette.BorderSubtle, cardShape)
                                .clickable { onOpenCategory(cat) }
                                .padding(horizontal = 14.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            DexSettingsCategoryIconBadge(
                                glyph = meta.glyph,
                                accent = meta.badgeColor,
                                size = 34.dp,
                            )
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                OpalText(text = meta.title(isZh), fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
                                OpalText(
                                    text = meta.subtitle(isZh),
                                    fontSize = 11.5.sp,
                                    color = OpalPalette.TextSecondary,
                                    maxLines = 1,
                                )
                            }
                            OpalText(text = "›", fontSize = 18.sp, color = OpalPalette.TextMuted)
                        }
                    }
                }
            }
        } else {
            val meta = selectedCategory.meta()
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    OpalButton(
                        label = if (isZh) "← 返回" else "← Back",
                        compact = true,
                        filled = false,
                        accent = OpalPalette.AccentBlue,
                        onClick = onBackToList,
                    )
                    OpalText(
                        text = meta.title(isZh),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.width(8.dp))
                OpalGlyphButton(glyph = ChromeGlyph.CLOSE, size = 30.dp, onClick = onDismiss)
            }

            Spacer(Modifier.height(10.dp))
            DexSettingsDivider()
            Spacer(Modifier.height(10.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(detailScroll),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = detailContent,
            )
        }
    }
}

@Composable
private fun DexSettingsCategoryContent(
    category: DexSettingsCategory,
    bridge: BridgeClient,
    uiState: WorkspaceUiState,
    isZh: Boolean,
    onOpenWidgetStore: () -> Unit,
    onToggleWidgetEdit: () -> Unit,
    onOpenKeyBindings: () -> Unit,
    onOpenFaq: () -> Unit,
    onUploadLogs: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (category) {
        DexSettingsCategory.APPEARANCE -> DexAppearanceSettingsSection(
            bridge = bridge,
            uiState = uiState,
            isZh = isZh,
            onOpenWidgetStore = onOpenWidgetStore,
            onToggleWidgetEdit = onToggleWidgetEdit,
            onDismiss = onDismiss,
        )
        DexSettingsCategory.DOCK -> DexDockSettingsSection(isZh = isZh)
        DexSettingsCategory.DISPLAY_CAST -> DexDisplayCastSettingsSection(
            bridge = bridge,
            isZh = isZh,
        )
        DexSettingsCategory.AUDIO -> DexAudioSettingsSection(
            bridge = bridge,
            isZh = isZh,
        )
        DexSettingsCategory.INPUT_IME -> DexInputImeSettingsSection(
            bridge = bridge,
            isZh = isZh,
            onOpenKeyBindings = onOpenKeyBindings,
            onDismiss = onDismiss,
        )
        DexSettingsCategory.WINDOWS -> DexWindowsSettingsSection(
            bridge = bridge,
            isZh = isZh,
        )
        DexSettingsCategory.SYSTEM_DIAG -> DexSystemDiagSettingsSection(
            bridge = bridge,
            isZh = isZh,
            onOpenFaq = onOpenFaq,
            onUploadLogs = onUploadLogs,
            onDismiss = onDismiss,
        )
    }
}

@Composable
internal fun DexSettingsGroupCard(
    title: String,
    subtitle: String = "",
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(OpalPalette.GlassSurface)
            .border(0.85.dp, OpalPalette.BorderSubtle, shape)
            .padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            OpalText(text = title, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
            if (subtitle.isNotEmpty()) {
                OpalText(text = subtitle, fontSize = 11.5.sp, color = OpalPalette.TextSecondary)
            }
        }
        content()
    }
}

@Composable
internal fun DexSettingsDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(0.75.dp)
            .background(OpalPalette.DividerColor),
    )
}

@Composable
internal fun DexSettingsCategoryIconBadge(
    glyph: ChromeGlyph,
    accent: Color,
    size: Dp = 28.dp,
) {
    val shape = RoundedCornerShape(size * 0.30f)
    Box(
        modifier = Modifier
            .size(size)
            .clip(shape)
            .background(accent.copy(alpha = if (OpalPalette.isDark) 0.85f else 0.92f))
            .border(0.6.dp, Color.White.copy(alpha = 0.30f), shape),
        contentAlignment = Alignment.Center,
    ) {
        OpalGlyphIcon(glyph = glyph, tint = Color.White, size = size * 0.54f)
    }
}

@Composable
internal fun DexSettingsActionRow(
    title: String,
    subtitle: String,
    actionLabel: String,
    accent: Color = OpalPalette.AccentBlue,
    filled: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            OpalText(text = title, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
            if (subtitle.isNotEmpty()) {
                OpalText(text = subtitle, fontSize = 11.5.sp, color = OpalPalette.TextSecondary)
            }
        }
        Spacer(Modifier.width(10.dp))
        OpalButton(
            label = actionLabel,
            compact = true,
            filled = filled,
            accent = accent,
            onClick = onClick,
        )
    }
}

@Composable
internal fun DexSettingsInlineChip(
    label: String,
    selected: Boolean,
    accent: Color = OpalPalette.AccentBlue,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = CircleShape
    val bg = if (selected) {
        accent.copy(alpha = if (OpalPalette.isDark) 0.28f else 0.16f)
    } else {
        OpalPalette.ChipFill
    }
    val borderCol = if (selected) {
        accent.copy(alpha = if (OpalPalette.isDark) 0.54f else 0.40f)
    } else {
        OpalPalette.BorderHairline
    }
    Box(
        modifier = modifier
            .clip(shape)
            .background(bg)
            .border(0.8.dp, borderCol, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        OpalText(
            text = label,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) accent else OpalPalette.TextSecondary,
            maxLines = 1,
        )
    }
}
