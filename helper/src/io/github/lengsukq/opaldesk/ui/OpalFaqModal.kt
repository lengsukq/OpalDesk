package io.github.lengsukq.opaldesk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lengsukq.opaldesk.R
import io.github.lengsukq.opaldesk.guide.OpalFaqCatalog
import io.github.lengsukq.opaldesk.guide.OpalFaqCategory
import io.github.lengsukq.opaldesk.guide.OpalFaqEntry
import java.util.Locale

/**
 * Full-screen Liquid Glass FAQ modal for the OpalDesk Console.
 */
@Composable
fun OpalFaqModal(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(OpalPalette.ScrimColor)
            .pointerInput(Unit) { detectTapGestures { onDismiss() } }
            .padding(horizontal = 14.dp, vertical = 28.dp),
        contentAlignment = Alignment.Center,
    ) {
        val modalMaxH = (maxHeight - 24.dp).coerceAtLeast(340.dp)
        OpalFaqWindow(
            maxHeight = modalMaxH,
            onDismiss = onDismiss,
        )
    }
}

/**
 * Standalone Liquid Glass FAQ window matching [DexFeatureGuideWindow]'s visual chrome.
 */
@Composable
fun OpalFaqWindow(
    maxHeight: Dp,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(22.dp)
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
                            Brush.linearGradient(listOf(Color(0xFF0A84FF), Color(0xFF34D399))),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    OpalText(
                        text = "?",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    OpalText(
                        text = stringResource(R.string.faq_title),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                    OpalText(
                        text = stringResource(R.string.faq_subtitle),
                        fontSize = 11.sp,
                        color = OpalPalette.TextSecondary,
                        maxLines = 1,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            OpalButton(
                label = stringResource(R.string.common_close),
                compact = true,
                filled = false,
                onClick = onDismiss,
            )
        }

        Spacer(Modifier.height(10.dp))
        OpalFaqPanel(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false),
        )
    }
}

/**
 * Reusable FAQ search, category filter capsules, and collapsible accordion cards.
 * Used by both [OpalFaqWindow] and the inline FAQ view inside [DexFeatureGuideWindow].
 */
@Composable
fun OpalFaqPanel(
    modifier: Modifier = Modifier,
) {
    val config = LocalConfiguration.current
    val activeLocale = remember(config) {
        config.locales.takeIf { !it.isEmpty }?.get(0) ?: Locale.getDefault()
    }
    var query by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf(OpalFaqCategory.ALL) }
    var expandedIds by remember { mutableStateOf(setOf(OpalFaqCatalog.ID_TOUCH_UNRESPONSIVE)) }

    val allEntries = remember(activeLocale) { OpalFaqCatalog.entries(activeLocale) }
    val filteredEntries = remember(allEntries, selectedCategory, query) {
        OpalFaqCatalog.filterEntries(allEntries, selectedCategory, query)
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        // Search Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OpalTextInput(
                value = query,
                onValueChange = { query = it },
                placeholder = stringResource(R.string.faq_search_placeholder),
                modifier = Modifier.weight(1f),
            )
            if (query.isNotEmpty()) {
                OpalButton(
                    label = stringResource(R.string.common_clear),
                    compact = true,
                    filled = false,
                    onClick = { query = "" },
                )
            }
        }

        // Category Filter Capsules + Count
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                OpalFaqCatalog.categories().forEach { cat ->
                    val selected = cat == selectedCategory
                    val accent = faqCategoryAccent(cat)
                    val bg = if (selected) {
                        accent.copy(alpha = if (OpalPalette.isDark) 0.24f else 0.14f)
                    } else {
                        OpalPalette.ChipFill
                    }
                    val border = if (selected) {
                        accent.copy(alpha = if (OpalPalette.isDark) 0.50f else 0.36f)
                    } else {
                        OpalPalette.BorderHairline
                    }
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(bg)
                            .border(0.8.dp, border, CircleShape)
                            .clickable { selectedCategory = cat }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    ) {
                        OpalText(
                            text = cat.label(activeLocale),
                            fontSize = 11.5.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (selected) accent else OpalPalette.TextSecondary,
                            maxLines = 1,
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            OpalText(
                text = stringResource(R.string.faq_result_count, filteredEntries.size),
                fontSize = 11.sp,
                color = OpalPalette.TextMuted,
                maxLines = 1,
            )
        }

        // Collapsible FAQ Accordion List
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (filteredEntries.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(OpalPalette.ChipFill)
                        .border(0.75.dp, OpalPalette.BorderHairline, RoundedCornerShape(14.dp))
                        .padding(horizontal = 16.dp, vertical = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    OpalText(
                        text = stringResource(R.string.faq_empty_title),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = OpalPalette.TextPrimary,
                    )
                    OpalText(
                        text = stringResource(R.string.faq_empty_hint),
                        fontSize = 11.5.sp,
                        color = OpalPalette.TextSecondary,
                    )
                }
            } else {
                filteredEntries.forEachIndexed { idx, entry ->
                    val expanded = entry.id in expandedIds || query.isNotBlank()
                    OpalFaqAccordionCard(
                        entry = entry,
                        pinned = idx == 0 && entry.id == OpalFaqCatalog.ID_TOUCH_UNRESPONSIVE,
                        expanded = expanded,
                        locale = activeLocale,
                        onToggle = {
                            expandedIds = if (entry.id in expandedIds) {
                                expandedIds - entry.id
                            } else {
                                expandedIds + entry.id
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun OpalFaqAccordionCard(
    entry: OpalFaqEntry,
    pinned: Boolean,
    expanded: Boolean,
    locale: Locale,
    onToggle: () -> Unit,
) {
    val cardShape = RoundedCornerShape(14.dp)
    val accent = faqCategoryAccent(entry.category)
    val borderCol = if (expanded) {
        accent.copy(alpha = if (OpalPalette.isDark) 0.44f else 0.34f)
    } else {
        OpalPalette.BorderHairline
    }
    val bgCol = if (pinned && expanded) {
        accent.copy(alpha = if (OpalPalette.isDark) 0.13f else 0.08f)
    } else {
        OpalPalette.ChipFill
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(bgCol)
            .border(0.8.dp, borderCol, cardShape)
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (pinned) {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(OpalPalette.AccentAmber.copy(alpha = if (OpalPalette.isDark) 0.24f else 0.14f))
                            .padding(horizontal = 6.dp, vertical = 1.5.dp),
                    ) {
                        OpalText(
                            text = stringResource(R.string.faq_pinned_badge),
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = OpalPalette.AccentAmber,
                            maxLines = 1,
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(accent.copy(alpha = if (OpalPalette.isDark) 0.20f else 0.12f))
                        .padding(horizontal = 6.dp, vertical = 1.5.dp),
                ) {
                    OpalText(
                        text = entry.category.label(locale),
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = accent,
                        maxLines = 1,
                    )
                }
            }
            Spacer(Modifier.width(6.dp))
            OpalText(
                text = stringResource(if (expanded) R.string.common_collapse else R.string.common_expand),
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Medium,
                color = if (expanded) accent else OpalPalette.TextMuted,
                maxLines = 1,
            )
        }

        OpalText(
            text = entry.question,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = OpalPalette.TextPrimary,
            lineHeight = 18.sp,
        )

        OpalText(
            text = entry.summary,
            fontSize = 11.5.sp,
            color = OpalPalette.TextSecondary,
            lineHeight = 16.5.sp,
        )

        if (expanded) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.75.dp)
                    .background(OpalPalette.DividerColor),
            )
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                entry.steps.forEachIndexed { stepIdx, step ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .padding(top = 1.dp)
                                .size(17.dp)
                                .clip(CircleShape)
                                .background(accent.copy(alpha = if (OpalPalette.isDark) 0.22f else 0.13f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            OpalText(
                                text = "${stepIdx + 1}",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = accent,
                            )
                        }
                        OpalText(
                            text = step,
                            fontSize = 11.5.sp,
                            color = OpalPalette.TextPrimary,
                            lineHeight = 17.sp,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun faqCategoryAccent(category: OpalFaqCategory): Color = when (category) {
    OpalFaqCategory.ALL -> OpalPalette.AccentBlue
    OpalFaqCategory.TOUCH_INPUT -> OpalPalette.AccentAmber
    OpalFaqCategory.CAST_CONNECTION -> OpalPalette.AccentCyan
    OpalFaqCategory.DISPLAY_ACTIVATION -> OpalPalette.AccentEmerald
}
