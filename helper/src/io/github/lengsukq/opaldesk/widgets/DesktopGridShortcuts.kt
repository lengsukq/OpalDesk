package io.github.lengsukq.opaldesk.widgets

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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

/**
 * Desktop app shortcut item hosted inside the unified desktop widget grid so
 * icons and widgets share one collision-free coordinate system.
 */
sealed interface DesktopGridShortcutItem {
    val stableKey: String

    data object BuiltinGuide : DesktopGridShortcutItem {
        override val stableKey: String = "builtin_guide"
    }

    data class App(val entry: InstalledAppEntry) : DesktopGridShortcutItem {
        override val stableKey: String = "app_${entry.appKey}"
    }
}

@Composable
internal fun DesktopGridShortcutTile(
    item: DesktopGridShortcutItem,
    isEditMode: Boolean,
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
