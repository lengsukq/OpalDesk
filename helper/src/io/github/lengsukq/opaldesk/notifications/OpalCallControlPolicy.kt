package io.github.lengsukq.opaldesk.notifications

import android.telephony.TelephonyManager
import java.util.Locale

/**
 * Immutable call continuity state snapshot synced to the daemon over Binder TX 52
 * (`TX_SYNC_CALL_STATE`) and forwarded as OPD1 v1.3 `0x1E TYPE_CALL_STATE`.
 */
data class CallSnapshot(
    val state: String = CallControlPolicy.STATE_IDLE,
    val number: String = "",
    val contactName: String = "",
    val startedAtMs: Long = 0L,
    val isMuted: Boolean = false,
    val isSpeakerOn: Boolean = false,
    val canAnswer: Boolean = false,
    val canReject: Boolean = false,
    val canDial: Boolean = true,
    val source: String = "",
) {
    val isActive: Boolean
        get() = CallControlPolicy.normalizeState(state) != CallControlPolicy.STATE_IDLE

    /**
     * Serializes this snapshot into the exact UTF-8 JSON schema expected by Java
     * `OpalCastProtocol.CallStateMsg` and Swift `CallStatePayload`.
     */
    fun toBridgeJson(): String {
        val normalizedState = CallControlPolicy.normalizeState(state)
        val sb = StringBuilder(224)
        sb.append("{\"state\":")
        OpalNotificationJsonCodec.appendJsonString(sb, normalizedState)
        sb.append(",\"number\":")
        OpalNotificationJsonCodec.appendJsonString(sb, number)
        sb.append(",\"contactName\":")
        OpalNotificationJsonCodec.appendJsonString(sb, contactName)
        sb.append(",\"startedAtMs\":").append(startedAtMs.coerceAtLeast(0L))
        sb.append(",\"isMuted\":").append(isMuted)
        sb.append(",\"isSpeakerOn\":").append(isSpeakerOn)
        sb.append(",\"canAnswer\":").append(canAnswer)
        sb.append(",\"canReject\":").append(canReject)
        sb.append(",\"canDial\":").append(canDial)
        sb.append(",\"source\":")
        OpalNotificationJsonCodec.appendJsonString(sb, source)
        sb.append('}')
        return sb.toString()
    }
}

/**
 * Pure JVM-testable call state transition, notification inference, and dial sanitization policy.
 */
object CallControlPolicy {
    const val STATE_IDLE = "idle"
    const val STATE_RINGING = "ringing"
    const val STATE_OFFHOOK = "offhook"
    const val STATE_DIALING = "dialing"

    const val SOURCE_TELEPHONY = "telephony"
    const val SOURCE_NOTIFICATION = "notification"

    private const val MAX_DIAL_NUMBER_LEN = 32

    fun normalizeState(raw: String?): String = when (raw?.trim()?.lowercase(Locale.ROOT)) {
        STATE_RINGING -> STATE_RINGING
        STATE_OFFHOOK, "active", "holding" -> STATE_OFFHOOK
        STATE_DIALING -> STATE_DIALING
        else -> STATE_IDLE
    }

    fun telephonyStateToWireState(telephonyState: Int): String = when (telephonyState) {
        TelephonyManager.CALL_STATE_RINGING -> STATE_RINGING
        TelephonyManager.CALL_STATE_OFFHOOK -> STATE_OFFHOOK
        else -> STATE_IDLE
    }

    /**
     * Sanitizes a dial number from remote clients: strips optional `tel:` prefix, keeps only
     * `[0-9+*#()\- ]` characters, normalizes whitespace, and requires at least one dialable symbol
     * (`0-9`, `*`, or `#`).
     */
    fun sanitizeDialNumber(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val trimmed = raw.trim()
        val withoutScheme = if (trimmed.startsWith("tel:", ignoreCase = true)) {
            trimmed.substring(4).trim()
        } else {
            trimmed
        }
        val sb = StringBuilder(withoutScheme.length.coerceAtMost(MAX_DIAL_NUMBER_LEN))
        var hasDialSymbol = false
        var lastWasSpace = false
        for (i in withoutScheme.indices) {
            if (sb.length >= MAX_DIAL_NUMBER_LEN) break
            val ch = withoutScheme[i]
            val allowed = (ch in '0'..'9') || ch == '+' || ch == '*' || ch == '#' ||
                ch == '(' || ch == ')' || ch == '-' || ch == ' '
            if (!allowed) break
            if (ch == ' ') {
                if (sb.isEmpty() || lastWasSpace) continue
                lastWasSpace = true
            } else {
                lastWasSpace = false
                if (ch in '0'..'9' || ch == '*' || ch == '#') {
                    hasDialSymbol = true
                }
            }
            sb.append(ch)
        }
        val cleaned = sb.toString().trim().trimEnd { it == ' ' || it == '-' }
        return if (hasDialSymbol) cleaned else ""
    }

    fun transitionTelephonyState(
        prev: CallSnapshot,
        telephonyState: Int,
        incomingNumber: String,
        contactName: String,
        nowMs: Long,
        isMuted: Boolean,
        isSpeakerOn: Boolean,
        canAnswerPerm: Boolean,
        canDial: Boolean = true,
    ): CallSnapshot {
        val nextState = telephonyStateToWireState(telephonyState)
        if (nextState == STATE_IDLE) {
            return CallSnapshot(
                state = STATE_IDLE,
                number = "",
                contactName = "",
                startedAtMs = 0L,
                isMuted = false,
                isSpeakerOn = false,
                canAnswer = false,
                canReject = false,
                canDial = canDial,
                source = SOURCE_TELEPHONY,
            )
        }

        val cleanIncoming = incomingNumber.trim()
        val cleanContact = contactName.trim()
        val preservedNumber = when {
            cleanIncoming.isNotEmpty() -> cleanIncoming
            prev.state != STATE_IDLE -> prev.number
            else -> ""
        }
        val preservedContact = when {
            cleanContact.isNotEmpty() -> cleanContact
            prev.state != STATE_IDLE -> prev.contactName
            else -> ""
        }
        val startedAt = when {
            nextState == prev.state && prev.startedAtMs > 0L -> prev.startedAtMs
            else -> nowMs.coerceAtLeast(0L)
        }

        return CallSnapshot(
            state = nextState,
            number = preservedNumber,
            contactName = preservedContact,
            startedAtMs = startedAt,
            isMuted = isMuted,
            isSpeakerOn = isSpeakerOn,
            canAnswer = (nextState == STATE_RINGING) && canAnswerPerm,
            canReject = canAnswerPerm,
            canDial = canDial,
            source = SOURCE_TELEPHONY,
        )
    }

    fun inferNotificationCallState(
        title: String,
        text: String,
        hasAnswerAction: Boolean,
        isOngoing: Boolean,
    ): String {
        val combined = "$title $text".lowercase(Locale.ROOT)
        if (hasAnswerAction ||
            combined.contains("来电") ||
            combined.contains("呼入") ||
            combined.contains("响铃") ||
            combined.contains("incoming") ||
            combined.contains("ringing")
        ) {
            return STATE_RINGING
        }
        if (combined.contains("正在拨打") ||
            combined.contains("拨号") ||
            combined.contains("呼叫中") ||
            combined.contains("dialing") ||
            combined.contains("calling")
        ) {
            return STATE_DIALING
        }
        return if (isOngoing) STATE_OFFHOOK else STATE_RINGING
    }

    fun buildNotificationFallbackSnapshot(
        prev: CallSnapshot,
        title: String,
        text: String,
        postTimeMs: Long,
        hasAnswerAction: Boolean,
        isOngoing: Boolean,
        isMuted: Boolean,
        isSpeakerOn: Boolean,
        canAnswerPerm: Boolean,
        canDial: Boolean = true,
    ): CallSnapshot {
        val inferred = inferNotificationCallState(title, text, hasAnswerAction, isOngoing)
        val cleanTitle = title.trim()
        val sanitizedFromTitle = sanitizeDialNumber(cleanTitle)
        val number = when {
            sanitizedFromTitle.isNotEmpty() && cleanTitle.all { it.isDigit() || it in "+-*#() " } -> sanitizedFromTitle
            prev.number.isNotEmpty() -> prev.number
            else -> ""
        }
        val contactName = when {
            cleanTitle.isNotEmpty() && number != cleanTitle -> cleanTitle
            prev.contactName.isNotEmpty() -> prev.contactName
            else -> ""
        }
        val startedAt = when {
            prev.state == inferred && prev.startedAtMs > 0L -> prev.startedAtMs
            postTimeMs > 0L -> postTimeMs
            else -> 0L
        }
        return CallSnapshot(
            state = inferred,
            number = number,
            contactName = contactName,
            startedAtMs = startedAt,
            isMuted = isMuted,
            isSpeakerOn = isSpeakerOn,
            canAnswer = (inferred == STATE_RINGING) && canAnswerPerm,
            canReject = canAnswerPerm,
            canDial = canDial,
            source = SOURCE_NOTIFICATION,
        )
    }
}
