package io.github.lengsukq.opaldesk.widgets

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lengsukq.opaldesk.compositor.AppCatalogRepository
import io.github.lengsukq.opaldesk.compositor.InstalledAppEntry
import io.github.lengsukq.opaldesk.ui.OpalGuideDesktopIconTile
import io.github.lengsukq.opaldesk.ui.OpalPalette
import io.github.lengsukq.opaldesk.ui.OpalText
import io.github.lengsukq.opaldesk.ui.onTapOrContextAction
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Desktop app shortcut item hosted inside the unified desktop widget grid so
 * icons and widgets share one collision-free coordinate system.
 */
sealed interface DesktopGridShortcutItem {
    val stableKey: String

    data object BuiltinDexSettings : DesktopGridShortcutItem {
        override val stableKey: String = "builtin_dex_settings"
    }

    data object BuiltinGuide : DesktopGridShortcutItem {
        override val stableKey: String = "builtin_guide"
    }

    data class App(val entry: InstalledAppEntry) : DesktopGridShortcutItem {
        override val stableKey: String = "app_${entry.appKey}"
    }
}

/**
 * Builds the complete list of desktop shortcut grid items (built-in entries followed by all
 * user-added desktop shortcuts matching [allApps]), preserving user order without any
 * hardcoded item cap.
 */
internal fun buildDesktopGridShortcuts(
    showGuideIcon: Boolean,
    allApps: List<InstalledAppEntry>,
    desktopAppKeys: List<String>,
): List<DesktopGridShortcutItem> {
    val byKey = allApps.associateBy { it.appKey }
    return buildList(2 + desktopAppKeys.size) {
        add(DesktopGridShortcutItem.BuiltinDexSettings)
        if (showGuideIcon) add(DesktopGridShortcutItem.BuiltinGuide)
        desktopAppKeys.asSequence().distinct().mapNotNull { byKey[it] }.forEach { entry ->
            add(DesktopGridShortcutItem.App(entry))
        }
    }
}

@Composable
internal fun DesktopGridShortcutTile(
    item: DesktopGridShortcutItem,
    isEditMode: Boolean,
    onOpenDexSettings: () -> Unit = {},
    onOpenGuide: () -> Unit,
    onContextGuide: (Offset) -> Unit,
    onLaunchApp: (InstalledAppEntry) -> Unit,
    onContextApp: (String, Offset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val tileAlpha = if (isEditMode) 0.72f else 1f

    Box(
        modifier = modifier.alpha(tileAlpha),
        contentAlignment = Alignment.Center,
    ) {
        when (item) {
            DesktopGridShortcutItem.BuiltinDexSettings -> {
                DexSettingsDesktopIconTile(
                    onOpen = { if (!isEditMode) onOpenDexSettings() },
                )
            }

            DesktopGridShortcutItem.BuiltinGuide -> {
                OpalGuideDesktopIconTile(
                    onOpen = { if (!isEditMode) onOpenGuide() },
                    onContextMenu = { pos -> if (!isEditMode) onContextGuide(pos) },
                )
            }

            is DesktopGridShortcutItem.App -> {
                val app = item.entry
                val icon = remember(app.appKey) {
                    AppCatalogRepository.getAppIcon(ctx, app.pkg, app.userId, app.profileType)
                }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .widthIn(max = 78.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .onTapOrContextAction(
                            app.appKey,
                            isEditMode,
                            onPrimaryTap = { if (!isEditMode) onLaunchApp(app) },
                            onContextAction = { rootPos -> if (!isEditMode) onContextApp(app.appKey, rootPos) },
                        )
                        .padding(vertical = 4.dp, horizontal = 4.dp),
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
                    Spacer(Modifier.height(4.dp))
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

@Composable
private fun DexSettingsDesktopIconTile(
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val isZh = remember(ctx) {
        val lang = runCatching {
            ctx.resources.configuration.locales.takeIf { !it.isEmpty }?.get(0)?.language
        }.getOrNull() ?: Locale.getDefault().language
        lang.equals("zh", ignoreCase = true)
    }
    val iconShape = RoundedCornerShape(13.dp)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .width(74.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onOpen)
            .padding(vertical = 6.dp, horizontal = 4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .shadow(8.dp, iconShape)
                .clip(iconShape)
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF475569), Color(0xFF1E293B)),
                    ),
                )
                .border(0.9.dp, Color.White.copy(alpha = 0.34f), iconShape),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.size(26.dp)) {
                val cx = size.width * 0.5f
                val cy = size.height * 0.5f
                val sw = 2.1.dp.toPx()
                val innerR = size.minDimension * 0.17f
                val bodyR = size.minDimension * 0.33f
                val toothOut = size.minDimension * 0.47f
                drawCircle(color = Color.White, radius = innerR, center = Offset(cx, cy), style = Stroke(sw))
                drawCircle(color = Color.White.copy(alpha = 0.94f), radius = bodyR, center = Offset(cx, cy), style = Stroke(sw))
                for (i in 0 until 8) {
                    val rad = (i * 45.0) * PI / 180.0
                    drawLine(
                        color = Color.White,
                        start = Offset(cx + (bodyR * cos(rad)).toFloat(), cy + (bodyR * sin(rad)).toFloat()),
                        end = Offset(cx + (toothOut * cos(rad)).toFloat(), cy + (toothOut * sin(rad)).toFloat()),
                        strokeWidth = sw,
                        cap = StrokeCap.Round,
                    )
                }
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
                text = if (isZh) "DeX 设置" else "DeX Settings",
                fontSize = 10.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = OpalPalette.TextPrimary,
                maxLines = 1,
            )
        }
    }
}
