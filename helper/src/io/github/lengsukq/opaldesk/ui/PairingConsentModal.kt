package io.github.lengsukq.opaldesk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lengsukq.opaldesk.BridgeClient
import io.github.lengsukq.opaldesk.R
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol
import io.github.lengsukq.opaldesk.protocol.PendingPairingRequest
import kotlinx.coroutines.delay

/**
 * Interactive Liquid Glass host consent prompt replacing legacy 6-digit PIN pairing.
 *
 * Allows the host owner to choose:
 * - 永久允许 (persists hashed authToken, future connections skip prompt, revocable anytime)
 * - 仅本次允许 (grants current session only; never persisted)
 * - 拒绝 (rejects and feeds anti-spam backoff)
 */
@Composable
fun PairingConsentModal(
    request: PendingPairingRequest,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    var busy by remember(request.requestId) { mutableStateOf(false) }
    var remainingSec by remember(request.requestId) {
        mutableIntStateOf(((request.expiresAtMs - System.currentTimeMillis()) / 1000L).toInt().coerceIn(0, 60))
    }

    LaunchedEffect(request.requestId, request.expiresAtMs) {
        while (true) {
            val left = ((request.expiresAtMs - System.currentTimeMillis() + 999L) / 1000L).toInt().coerceAtLeast(0)
            remainingSec = left
            if (left <= 0) break
            delay(500L)
        }
    }

    fun submit(decision: Int) {
        if (busy) return
        busy = true
        BridgeClient.respondToPairingAsync(request.requestId, decision, ctx = ctx) {
            busy = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = {},
            )
            .padding(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .liquidGlassSurface(
                    shape = RoundedCornerShape(24.dp),
                    fill = OpalPalette.LiquidPopoverFill,
                    rim = OpalPalette.LiquidGlassRim,
                    elevation = 28.dp,
                )
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OpalStatusBadge(label = stringResource(R.string.pairing_badge), color = OpalPalette.AccentBlue)
                OpalText(
                    text = stringResource(R.string.pairing_countdown, remainingSec),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = if (remainingSec <= 10) OpalPalette.AccentDanger else OpalPalette.TextSecondary,
                )
            }

            OpalText(
                text = stringResource(R.string.pairing_question, request.clientName),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(OpalPalette.ChipFill)
                    .border(0.75.dp, OpalPalette.BorderSubtle, RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                OpalText(
                    text = stringResource(R.string.pairing_device_label, request.clientName),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                if (request.peerIp.isNotBlank()) {
                    OpalText(
                        text = stringResource(R.string.pairing_ip_label, request.peerIp),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = OpalPalette.TextSecondary,
                    )
                }
            }

            OpalText(
                text = stringResource(R.string.pairing_hint),
                fontSize = 12.sp,
                color = OpalPalette.TextSecondary,
            )

            Spacer(Modifier.height(2.dp))

            OpalButton(
                label = stringResource(if (busy) R.string.pairing_processing else R.string.pairing_allow_permanent),
                accent = OpalPalette.AccentEmerald,
                enabled = !busy,
                onClick = { submit(BridgeProtocol.PairingDecision.APPROVE_PERMANENT) },
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OpalButton(
                    label = stringResource(R.string.pairing_allow_once),
                    accent = OpalPalette.AccentBlue,
                    enabled = !busy,
                    onClick = { submit(BridgeProtocol.PairingDecision.APPROVE_ONCE) },
                    modifier = Modifier.weight(1f),
                )
                OpalButton(
                    label = stringResource(R.string.pairing_reject),
                    accent = OpalPalette.AccentDanger,
                    filled = false,
                    enabled = !busy,
                    onClick = { submit(BridgeProtocol.PairingDecision.REJECT) },
                    modifier = Modifier.weight(0.75f),
                )
            }
        }
    }
}
