package io.github.lengsukq.opaldesk.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Stable section keys across all 4 Host Console tabs, shared by
 * [ConsoleNavigationController], [OpalCollapsibleSectionCard], [consoleSearchAnchor],
 * and [io.github.lengsukq.opaldesk.search.ConsoleSearchCatalog].
 */
object ConsoleSectionKeys {
    // Tab 0: 工作站
    const val HERO = "tab0_hero"
    const val QUICK_TILES = "tab0_quick_tiles"
    const val INTERCONNECT = "tab0_interconnect"
    const val TRUSTED_PEERS = "tab0_trusted_peers"
    const val MANUAL_CONNECT = "tab0_manual_connect"
    const val SLOTS_QUICK = "tab0_slots_quick"

    // Tab 1: 显示与外设
    const val APPEARANCE = "tab1_appearance"
    const val FPS_DPI = "tab1_fps_dpi"
    const val EXTERNAL_DISPLAY = "tab1_external_display"
    const val CUSTOM_RESOLUTION = "tab1_custom_resolution"
    const val PER_APP_DPI = "tab1_per_app_dpi"
    const val PERIPHERALS_WALLPAPER = "tab1_peripherals_wallpaper"
    const val KEYBOARD_SHORTCUTS = "tab1_keyboard_shortcuts"
    const val DEX_IME = "tab1_dex_ime"

    // Tab 2: 系统设置
    const val NOTIFICATIONS_COMPANION = "tab2_notifications_companion"
    const val STARTUP_APPS = "tab2_startup_apps"
    const val APP_UPDATE_GUIDE = "tab2_app_update_guide"
    const val SLOTS_AUTO_LAYOUT = "tab2_slots_auto_layout"
    const val AUDIO_MEDIA = "tab2_audio_media"
    const val USB_TETHER_BETA = "tab2_usb_tether_beta"
    const val ENGINE_LOWLEVEL = "tab2_engine_lowlevel"

    // Tab 3: 诊断日志
    const val DIAGNOSTICS_LOGS = "tab3_diagnostics_logs"
    const val FEEDBACK_TRACKING = "tab3_feedback_tracking"

    val ALL_KEYS: Set<String> = setOf(
        HERO,
        QUICK_TILES,
        INTERCONNECT,
        TRUSTED_PEERS,
        MANUAL_CONNECT,
        SLOTS_QUICK,
        APPEARANCE,
        FPS_DPI,
        EXTERNAL_DISPLAY,
        CUSTOM_RESOLUTION,
        PER_APP_DPI,
        PERIPHERALS_WALLPAPER,
        KEYBOARD_SHORTCUTS,
        DEX_IME,
        NOTIFICATIONS_COMPANION,
        STARTUP_APPS,
        APP_UPDATE_GUIDE,
        SLOTS_AUTO_LAYOUT,
        AUDIO_MEDIA,
        USB_TETHER_BETA,
        ENGINE_LOWLEVEL,
        DIAGNOSTICS_LOGS,
        FEEDBACK_TRACKING,
    )
}

/**
 * Reactive controller coordinating Host Console bottom-tab switching, collapsible card
 * expansion state, `BringIntoViewRequester` registration, and Spotlight deep-link jump +
 * 1.25s Liquid Glass border pulse highlights.
 */
@Stable
class ConsoleNavigationController(
    initialTab: Int = 0,
    private val scope: CoroutineScope,
) {
    var selectedTab by mutableIntStateOf(initialTab.coerceIn(0, 3))

    var expandedSections by mutableStateOf<Set<String>>(emptySet())
        private set

    private var collapsedDefaultSections by mutableStateOf<Set<String>>(emptySet())

    var highlightedSectionKey by mutableStateOf<String?>(null)
        private set

    var highlightPulseSeq by mutableIntStateOf(0)
        private set

    private val requesters = LinkedHashMap<String, BringIntoViewRequester>()
    private var activeJumpJob: Job? = null

    fun registerRequester(sectionKey: String, requester: BringIntoViewRequester) {
        requesters[sectionKey] = requester
    }

    fun unregisterRequester(sectionKey: String, requester: BringIntoViewRequester) {
        if (requesters[sectionKey] === requester) {
            requesters.remove(sectionKey)
        }
    }

    fun isExpanded(key: String, defaultExpanded: Boolean): Boolean =
        if (defaultExpanded) {
            key !in collapsedDefaultSections
        } else {
            key in expandedSections
        }

    fun setExpanded(key: String, expanded: Boolean) {
        if (expanded) {
            expandedSections = expandedSections + key
            collapsedDefaultSections = collapsedDefaultSections - key
        } else {
            expandedSections = expandedSections - key
            collapsedDefaultSections = collapsedDefaultSections + key
        }
    }

    fun toggleSection(key: String, defaultExpanded: Boolean) {
        setExpanded(key, !isExpanded(key, defaultExpanded))
    }

    /**
     * Switches to [tabIndex], expands [sectionKey], awaits composition/layout of the target
     * tab, scrolls the anchored section cleanly into view, and plays a 1.25s Liquid Glass blue
     * border pulse highlight.
     */
    fun jumpToSection(tabIndex: Int, sectionKey: String) {
        val targetTab = tabIndex.coerceIn(0, 3)
        selectedTab = targetTab
        setExpanded(sectionKey, true)
        activeJumpJob?.cancel()
        activeJumpJob = scope.launch {
            // Wait for tab switch & collapsible expansion composition to mount its requester
            withFrameNanos { }
            delay(50L)
            var requester = requesters[sectionKey]
            var waitedMs = 0L
            while (requester == null && waitedMs < 480L) {
                delay(40L)
                waitedMs += 40L
                requester = requesters[sectionKey]
            }
            if (requester != null) {
                withFrameNanos { }
                runCatching { requester.bringIntoView() }
                // Re-align once expand animation has settled so tall cards sit comfortably on screen
                delay(200L)
                runCatching { requesters[sectionKey]?.bringIntoView() }
            }
            highlightedSectionKey = sectionKey
            highlightPulseSeq++
            delay(1_250L)
            if (highlightedSectionKey == sectionKey) {
                highlightedSectionKey = null
            }
        }
    }
}

@Composable
fun rememberConsoleNavigationController(initialTab: Int = 0): ConsoleNavigationController {
    val scope = rememberCoroutineScope()
    return remember(scope) { ConsoleNavigationController(initialTab = initialTab, scope = scope) }
}

/**
 * Registers any card or container (collapsible or static) as a Spotlight deep-link target:
 * binds a [BringIntoViewRequester] and renders a smooth 1.25s pulsing Liquid Glass blue rim
 * highlight when [ConsoleNavigationController.jumpToSection] targets [sectionKey].
 */
@Composable
fun Modifier.consoleSearchAnchor(
    controller: ConsoleNavigationController?,
    sectionKey: String,
    shape: Shape = RoundedCornerShape(22.dp),
): Modifier {
    if (controller == null) return this
    val requester = remember(sectionKey) { BringIntoViewRequester() }
    DisposableEffect(controller, sectionKey, requester) {
        controller.registerRequester(sectionKey, requester)
        onDispose { controller.unregisterRequester(sectionKey, requester) }
    }

    val isHighlighted = controller.highlightedSectionKey == sectionKey
    val pulseSeq = controller.highlightPulseSeq
    val pulseAlpha = remember(sectionKey) { Animatable(0f) }

    LaunchedEffect(isHighlighted, pulseSeq) {
        if (isHighlighted) {
            pulseAlpha.snapTo(0.25f)
            pulseAlpha.animateTo(1f, animationSpec = tween(220, easing = FastOutSlowInEasing))
            pulseAlpha.animateTo(0.45f, animationSpec = tween(260, easing = FastOutSlowInEasing))
            pulseAlpha.animateTo(0.95f, animationSpec = tween(260, easing = FastOutSlowInEasing))
            pulseAlpha.animateTo(0f, animationSpec = tween(480, easing = FastOutSlowInEasing))
        } else {
            pulseAlpha.animateTo(0f, animationSpec = tween(160))
        }
    }

    val highlightColor = OpalPalette.AccentBlue
    return this
        .bringIntoViewRequester(requester)
        .drawWithContent {
            drawContent()
            val alpha = pulseAlpha.value
            if (alpha > 0.01f) {
                val outline = shape.createOutline(size, layoutDirection, this)
                drawOutline(
                    outline = outline,
                    color = highlightColor.copy(alpha = 0.08f * alpha),
                )
                drawOutline(
                    outline = outline,
                    color = highlightColor.copy(alpha = 0.88f * alpha),
                    style = Stroke(width = 2.2.dp.toPx()),
                )
            }
        }
}

/**
 * Unified Liquid Glass collapsible section card (`RoundedCornerShape(22.dp)`) used across Host
 * Console tabs to keep default vertical density compact while surfacing live status summaries
 * at a glance and supporting Spotlight jump-to-expand + border pulse highlights.
 */
@Composable
fun OpalCollapsibleSectionCard(
    title: String,
    sectionKey: String,
    controller: ConsoleNavigationController? = null,
    subtitle: String = "",
    summaryText: String = "",
    summaryAccent: Color = OpalPalette.AccentBlue,
    defaultExpanded: Boolean = false,
    accentBorder: Color? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    var localExpanded by remember(sectionKey, defaultExpanded) { mutableStateOf(defaultExpanded) }
    val expanded = controller?.isExpanded(sectionKey, defaultExpanded) ?: localExpanded
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
        label = "collapsibleChevron",
    )

    val shape = RoundedCornerShape(22.dp)
    val rimBrush = if (accentBorder != null) {
        Brush.verticalGradient(listOf(accentBorder.copy(alpha = 0.62f), accentBorder.copy(alpha = 0.18f)))
    } else {
        OpalPalette.LiquidGlassRim
    }
    val isDark = OpalPalette.isDark

    Column(
        modifier = modifier
            .fillMaxWidth()
            .consoleSearchAnchor(controller = controller, sectionKey = sectionKey, shape = shape)
            .liquidGlassSurface(
                shape = shape,
                fill = OpalPalette.LiquidDockFill,
                rim = rimBrush,
                elevation = if (isDark) 12.dp else 9.dp,
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    val next = !expanded
                    if (controller != null) {
                        controller.setExpanded(sectionKey, next)
                    } else {
                        localExpanded = next
                    }
                    onExpandedChange?.invoke(next)
                }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                OpalText(
                    text = title,
                    fontSize = 15.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                if (subtitle.isNotBlank()) {
                    OpalText(
                        text = subtitle,
                        fontSize = 11.5.sp,
                        color = OpalPalette.TextSecondary,
                        maxLines = 1,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (summaryText.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(summaryAccent.copy(alpha = if (isDark) 0.18f else 0.11f))
                            .border(0.75.dp, summaryAccent.copy(alpha = if (isDark) 0.40f else 0.28f), CircleShape)
                            .padding(horizontal = 9.dp, vertical = 3.5.dp),
                    ) {
                        OpalText(
                            text = summaryText,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = summaryAccent,
                            maxLines = 1,
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(OpalPalette.ChipFill)
                        .border(0.7.dp, OpalPalette.BorderHairline, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(modifier = Modifier.rotate(chevronRotation)) {
                        OpalGlyphIcon(
                            glyph = ChromeGlyph.CHEVRON_DOWN,
                            tint = if (expanded) OpalPalette.AccentBlue else OpalPalette.TextSecondary,
                            size = 13.dp,
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(animationSpec = tween(210, easing = FastOutSlowInEasing)) +
                fadeIn(animationSpec = tween(170)),
            exit = shrinkVertically(animationSpec = tween(180, easing = FastOutSlowInEasing)) +
                fadeOut(animationSpec = tween(140)),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(0.75.dp)
                        .background(OpalPalette.DividerColor),
                )
                Spacer(Modifier.height(12.dp))
                content()
            }
        }
    }
}
