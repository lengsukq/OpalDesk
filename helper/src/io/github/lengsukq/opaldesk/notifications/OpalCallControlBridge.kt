package io.github.lengsukq.opaldesk.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.widget.Toast
import io.github.lengsukq.opaldesk.BridgeClient
import io.github.lengsukq.opaldesk.diagnostics.OpalLogStore
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.concurrent.Executor

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

/**
 * Opt-in Android call continuity bridge observing Telephony state (when `READ_PHONE_STATE` is granted)
 * plus a zero-phone-permission `Notification.CATEGORY_CALL` fallback, pushing live [CallSnapshot]
 * JSON over Binder TX 52 and executing remote `callAction` commands from Mac.
 */
object OpalCallControlBridge {
    private const val TAG = "OpalCallBridge"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { cmd -> mainHandler.post(cmd) }
    private val lock = Any()

    private val _callStateFlow = MutableStateFlow(CallSnapshot())
    val callStateFlow: StateFlow<CallSnapshot> = _callStateFlow.asStateFlow()

    private val _telephonyRegisteredFlow = MutableStateFlow(false)
    val telephonyRegisteredFlow: StateFlow<Boolean> = _telephonyRegisteredFlow.asStateFlow()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var lastSentJson: String = ""

    private var telephonyManager: TelephonyManager? = null
    private var api31Callback: Any? = null

    @Suppress("DEPRECATION")
    private var legacyListener: PhoneStateListener? = null
    private var activeCallNotifKey: String? = null

    fun requiredRuntimePermissions(): Array<String> = arrayOf(
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.ANSWER_PHONE_CALLS,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_CONTACTS,
    )

    fun hasPermission(ctx: Context?, permission: String): Boolean {
        val c = ctx ?: return false
        return runCatching {
            c.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }

    fun hasReadPhoneStatePermission(ctx: Context?): Boolean =
        hasPermission(ctx, Manifest.permission.READ_PHONE_STATE)

    fun hasAnswerPhoneCallsPermission(ctx: Context?): Boolean =
        hasPermission(ctx, Manifest.permission.ANSWER_PHONE_CALLS)

    fun hasCallPhonePermission(ctx: Context?): Boolean =
        hasPermission(ctx, Manifest.permission.CALL_PHONE)

    fun hasReadContactsPermission(ctx: Context?): Boolean =
        hasPermission(ctx, Manifest.permission.READ_CONTACTS)

    fun countGrantedPermissions(ctx: Context?): Int =
        requiredRuntimePermissions().count { hasPermission(ctx, it) }

    fun syncEnabledState(ctx: Context?) {
        val appCtx = ctx?.applicationContext ?: return
        if (AppPrefs.isCallControlSyncEnabled(appCtx)) {
            start(appCtx)
        } else {
            stop(appCtx)
        }
    }

    @SuppressLint("MissingPermission")
    fun start(ctx: Context) {
        val appCtx = ctx.applicationContext
        if (!AppPrefs.isCallControlSyncEnabled(appCtx)) {
            stop(appCtx)
            return
        }
        synchronized(lock) {
            appContext = appCtx
            if (!hasReadPhoneStatePermission(appCtx)) {
                unregisterTelephonyLocked()
                return
            }
            if (_telephonyRegisteredFlow.value) return
            val tm = appCtx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return
            telephonyManager = tm
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) {
                        handleTelephonyStateChange(appCtx, state, "")
                    }
                }
                val registered = runCatching {
                    tm.registerTelephonyCallback(mainExecutor, cb)
                    api31Callback = cb
                    true
                }.getOrDefault(false)
                _telephonyRegisteredFlow.value = registered
                if (registered) {
                    OpalLogStore.i(TAG, "Registered TelephonyCallback.CallStateListener")
                    val initialState = runCatching { tm.callStateForSubscription }.getOrDefault(TelephonyManager.CALL_STATE_IDLE)
                    handleTelephonyStateChange(appCtx, initialState, "")
                }
            } else {
                mainHandler.post {
                    synchronized(lock) {
                        if (!AppPrefs.isCallControlSyncEnabled(appCtx) ||
                            !hasReadPhoneStatePermission(appCtx) ||
                            _telephonyRegisteredFlow.value
                        ) {
                            return@post
                        }
                        @Suppress("DEPRECATION")
                        val listener = object : PhoneStateListener() {
                            @Deprecated("Deprecated in Java")
                            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                                handleTelephonyStateChange(appCtx, state, phoneNumber.orEmpty())
                            }
                        }
                        val registered = runCatching {
                            @Suppress("DEPRECATION")
                            tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
                            legacyListener = listener
                            true
                        }.getOrDefault(false)
                        _telephonyRegisteredFlow.value = registered
                        if (registered) {
                            OpalLogStore.i(TAG, "Registered legacy PhoneStateListener")
                        }
                    }
                }
            }
        }
    }

    fun stop(ctx: Context? = null) {
        val hadActive = _callStateFlow.value.isActive
        synchronized(lock) {
            unregisterTelephonyLocked()
            activeCallNotifKey = null
            _callStateFlow.value = CallSnapshot(state = CallControlPolicy.STATE_IDLE, canDial = false)
            if (ctx != null) {
                appContext = ctx.applicationContext
            }
        }
        if (hadActive) {
            val idleJson = _callStateFlow.value.toBridgeJson()
            lastSentJson = idleJson
            sendToDaemonSafe(idleJson)
        } else {
            lastSentJson = ""
        }
    }

    fun pushCurrentStateToDaemon() {
        val ctx = appContext ?: return
        if (!AppPrefs.isCallControlSyncEnabled(ctx)) return
        val json = _callStateFlow.value.toBridgeJson()
        lastSentJson = json
        sendToDaemonSafe(json)
    }

    /**
     * Zero-phone-permission fallback & caller-name enrichment driven by
     * [OpalNotificationListenerService] observing `Notification.CATEGORY_CALL`.
     */
    fun onCallNotificationUpdated(
        ctx: Context,
        key: String,
        packageName: String,
        title: String,
        text: String,
        postTimeMs: Long,
        hasAnswerAction: Boolean,
        isOngoing: Boolean,
    ) {
        val appCtx = ctx.applicationContext
        if (!AppPrefs.isCallControlSyncEnabled(appCtx)) return
        appContext = appCtx
        val am = appCtx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val isMuted = runCatching { am?.isMicrophoneMute == true }.getOrDefault(false)
        val isSpeakerOn = isSpeakerActive(am)
        val canAnswerPerm = hasAnswerPhoneCallsPermission(appCtx)

        val updated = synchronized(lock) {
            activeCallNotifKey = key
            val prev = _callStateFlow.value
            val fallback = CallControlPolicy.buildNotificationFallbackSnapshot(
                prev = prev,
                title = title,
                text = text,
                postTimeMs = postTimeMs,
                hasAnswerAction = hasAnswerAction,
                isOngoing = isOngoing,
                isMuted = isMuted,
                isSpeakerOn = isSpeakerOn,
                canAnswerPerm = canAnswerPerm,
                canDial = true,
            )
            // When telephony listener already tracks the exact state (ringing/offhook), enrich missing
            // caller name/number without regressing source or startedAtMs.
            val next = if (_telephonyRegisteredFlow.value && prev.source == CallControlPolicy.SOURCE_TELEPHONY && prev.isActive) {
                prev.copy(
                    number = prev.number.ifBlank { fallback.number },
                    contactName = prev.contactName.ifBlank { fallback.contactName },
                    isMuted = isMuted,
                    isSpeakerOn = isSpeakerOn,
                    canAnswer = (prev.state == CallControlPolicy.STATE_RINGING) && canAnswerPerm,
                    canReject = canAnswerPerm,
                )
            } else {
                fallback
            }
            _callStateFlow.value = next
            next
        }
        publishSnapshot(updated, force = false)
    }

    fun onCallNotificationRemoved(ctx: Context?, key: String) {
        val appCtx = ctx?.applicationContext ?: appContext ?: return
        if (!AppPrefs.isCallControlSyncEnabled(appCtx)) return
        val next = synchronized(lock) {
            if (activeCallNotifKey != key) return
            activeCallNotifKey = null
            val prev = _callStateFlow.value
            if (_telephonyRegisteredFlow.value && prev.source == CallControlPolicy.SOURCE_TELEPHONY) {
                return
            }
            val idle = CallSnapshot(
                state = CallControlPolicy.STATE_IDLE,
                canDial = true,
                source = CallControlPolicy.SOURCE_NOTIFICATION,
            )
            _callStateFlow.value = idle
            idle
        }
        publishSnapshot(next, force = false)
    }

    fun clearCallNotifications(ctx: Context?) {
        val key = synchronized(lock) { activeCallNotifKey } ?: return
        onCallNotificationRemoved(ctx, key)
    }

    /**
     * Executes a remote `0x35 CALL_ACTION` forwarded from `BridgeProvider.call("callAction")`.
     *
     * Returns `true` when Helper handled the action, or `false` when permission/state does not allow
     * handling so `:bridge` can invoke its rootless shell fallback where supported.
     */
    @SuppressLint("MissingPermission")
    fun handleRemoteAction(ctx: Context?, op: String, number: String, smsText: String): Boolean {
        val appCtx = ctx?.applicationContext ?: appContext ?: return false
        appContext = appCtx
        val normalizedOp = op.trim().lowercase(Locale.ROOT)
        if (!AppPrefs.isCallControlSyncEnabled(appCtx)) {
            return false
        }

        return when (normalizedOp) {
            "sync" -> {
                pushCurrentStateToDaemon()
                true
            }
            "answer" -> {
                if (!hasAnswerPhoneCallsPermission(appCtx)) return false
                val tm = appCtx.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager ?: return false
                @Suppress("DEPRECATION")
                runCatching {
                    tm.acceptRingingCall()
                    true
                }.getOrDefault(false)
            }
            "reject", "hangup" -> {
                if (!hasAnswerPhoneCallsPermission(appCtx)) return false
                val tm = appCtx.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager ?: return false
                @Suppress("DEPRECATION")
                runCatching { tm.endCall() }.getOrDefault(false)
            }
            "toggle_mute", "mute", "unmute" -> {
                val am = appCtx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
                val targetMuted = when (normalizedOp) {
                    "mute" -> true
                    "unmute" -> false
                    else -> !am.isMicrophoneMute
                }
                val ok = runCatching {
                    am.isMicrophoneMute = targetMuted
                    true
                }.getOrDefault(false)
                if (ok) {
                    updateAudioFlagsAndPush(
                        muted = runCatching { am.isMicrophoneMute }.getOrDefault(targetMuted),
                        speakerOn = isSpeakerActive(am),
                    )
                }
                ok
            }
            "toggle_speaker", "speaker_on", "speaker_off" -> {
                val am = appCtx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
                val targetSpeaker = when (normalizedOp) {
                    "speaker_on" -> true
                    "speaker_off" -> false
                    else -> !isSpeakerActive(am)
                }
                val ok = setSpeakerActive(am, targetSpeaker)
                if (ok) {
                    updateAudioFlagsAndPush(
                        muted = runCatching { am.isMicrophoneMute }.getOrDefault(_callStateFlow.value.isMuted),
                        speakerOn = isSpeakerActive(am),
                    )
                }
                ok
            }
            "dial" -> handleDialAction(appCtx, number)
            "reject_sms", "reject_with_sms" -> handleRejectWithSms(appCtx, number, smsText)
            else -> false
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleDialAction(appCtx: Context, rawNumber: String): Boolean {
        val sanitized = CallControlPolicy.sanitizeDialNumber(rawNumber)
        if (sanitized.isEmpty()) return false
        val uri = Uri.fromParts("tel", sanitized, null)
        val action = if (hasCallPhonePermission(appCtx)) Intent.ACTION_CALL else Intent.ACTION_DIAL
        val intent = Intent(action, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            appCtx.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    @SuppressLint("MissingPermission")
    private fun handleRejectWithSms(appCtx: Context, rawNumber: String, rawSmsText: String): Boolean {
        var declined = false
        if (hasAnswerPhoneCallsPermission(appCtx)) {
            val tm = appCtx.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            @Suppress("DEPRECATION")
            declined = runCatching { tm?.endCall() == true }.getOrDefault(false)
        }
        val targetNumber = CallControlPolicy.sanitizeDialNumber(rawNumber)
            .ifEmpty { CallControlPolicy.sanitizeDialNumber(_callStateFlow.value.number) }
        val cleanText = rawSmsText.trim()
        if (targetNumber.isNotEmpty()) {
            val smsIntent = Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", targetNumber, null)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (cleanText.isNotEmpty()) {
                    putExtra("sms_body", cleanText)
                }
            }
            val launched = runCatching {
                appCtx.startActivity(smsIntent)
                true
            }.getOrDefault(false)
            if (launched) return true
        } else if (cleanText.isNotEmpty()) {
            runCatching {
                val cm = appCtx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("OpalDesk Reject SMS", cleanText))
            }
            mainHandler.post {
                Toast.makeText(appCtx, "已复制拒接短信内容", Toast.LENGTH_SHORT).show()
            }
            return true
        }
        return declined
    }

    private fun handleTelephonyStateChange(appCtx: Context, state: Int, incomingNumber: String) {
        if (!AppPrefs.isCallControlSyncEnabled(appCtx)) return
        val am = appCtx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val isMuted = runCatching { am?.isMicrophoneMute == true }.getOrDefault(false)
        val isSpeakerOn = isSpeakerActive(am)
        val canAnswerPerm = hasAnswerPhoneCallsPermission(appCtx)
        val cleanIncoming = incomingNumber.trim()
        val contactName = if (cleanIncoming.isNotEmpty()) {
            resolveContactNameSafe(appCtx, cleanIncoming)
        } else {
            ""
        }
        val updated = synchronized(lock) {
            val next = CallControlPolicy.transitionTelephonyState(
                prev = _callStateFlow.value,
                telephonyState = state,
                incomingNumber = cleanIncoming,
                contactName = contactName,
                nowMs = System.currentTimeMillis(),
                isMuted = isMuted,
                isSpeakerOn = isSpeakerOn,
                canAnswerPerm = canAnswerPerm,
                canDial = true,
            )
            if (next.state == CallControlPolicy.STATE_IDLE) {
                activeCallNotifKey = null
            }
            _callStateFlow.value = next
            next
        }
        publishSnapshot(updated, force = false)
    }

    private fun resolveContactNameSafe(ctx: Context, number: String): String {
        if (number.isBlank() || !hasReadContactsPermission(ctx)) return ""
        return runCatching {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            ctx.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0)?.trim().orEmpty() else ""
            }.orEmpty()
        }.getOrDefault("")
    }

    private fun isSpeakerActive(am: AudioManager?): Boolean {
        if (am == null) return false
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val dev = am.communicationDevice
                if (dev != null) {
                    return@runCatching dev.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                }
            }
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn
        }.getOrDefault(false)
    }

    private fun setSpeakerActive(am: AudioManager, enable: Boolean): Boolean {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (enable) {
                    val speaker = am.availableCommunicationDevices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    }
                    if (speaker != null && am.setCommunicationDevice(speaker)) {
                        return@runCatching true
                    }
                } else {
                    am.clearCommunicationDevice()
                    return@runCatching true
                }
            }
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = enable
            true
        }.getOrDefault(false)
    }

    private fun updateAudioFlagsAndPush(muted: Boolean, speakerOn: Boolean) {
        val next = synchronized(lock) {
            val updated = _callStateFlow.value.copy(
                isMuted = muted,
                isSpeakerOn = speakerOn,
            )
            _callStateFlow.value = updated
            updated
        }
        publishSnapshot(next, force = true)
    }

    private fun unregisterTelephonyLocked() {
        val tm = telephonyManager
        if (tm != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (api31Callback as? TelephonyCallback)?.let { cb ->
                    runCatching { tm.unregisterTelephonyCallback(cb) }
                }
            }
            @Suppress("DEPRECATION")
            legacyListener?.let { l ->
                runCatching { tm.listen(l, PhoneStateListener.LISTEN_NONE) }
            }
        }
        api31Callback = null
        legacyListener = null
        telephonyManager = null
        _telephonyRegisteredFlow.value = false
    }

    private fun publishSnapshot(snapshot: CallSnapshot, force: Boolean) {
        val json = snapshot.toBridgeJson()
        if (!force && json == lastSentJson) return
        lastSentJson = json
        sendToDaemonSafe(json)
    }

    private fun sendToDaemonSafe(json: String) {
        runCatching {
            if (BridgeClient.isBinderAliveFast()) {
                BridgeClient.syncCallStateAsync(json)
            }
        }
    }
}
