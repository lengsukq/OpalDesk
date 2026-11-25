package io.github.lengsukq.opaldesk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol

/**
 * Handles one-tap host consent actions ("永久允许" / "仅本次允许" / "拒绝") dispatched from
 * the high-priority wireless pairing heads-up notification.
 */
class PairingActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_PAIRING_DECISION = "io.github.lengsukq.opaldesk.PAIRING_DECISION"
        const val EXTRA_REQUEST_ID = "requestId"
        const val EXTRA_DECISION = "decision"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null || intent.action != ACTION_PAIRING_DECISION) return
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty().trim()
        if (requestId.isEmpty()) return
        val decision = intent.getIntExtra(EXTRA_DECISION, BridgeProtocol.PairingDecision.REJECT)

        BridgeService.updatePairingNotification(context, null)
        if (BridgeClient.isBinderAliveFast()) {
            BridgeClient.respondToPairingAsync(requestId, decision, ctx = context)
            return
        }

        // Daemon binder not yet attached in this process wake: request it and submit right after
        val pendingResult = goAsync()
        BridgeClient.requestBinder(context)
        val handler = Handler(Looper.getMainLooper())
        fun attempt(remaining: Int) {
            if (BridgeClient.isBinderAliveFast() || remaining <= 0) {
                BridgeClient.respondToPairingAsync(requestId, decision, ctx = context) {
                    pendingResult.finish()
                }
            } else {
                handler.postDelayed({ attempt(remaining - 1) }, 120L)
            }
        }
        handler.postDelayed({ attempt(8) }, 100L)
    }
}
