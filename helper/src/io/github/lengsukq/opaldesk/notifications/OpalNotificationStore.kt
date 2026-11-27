package io.github.lengsukq.opaldesk.notifications

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import io.github.lengsukq.opaldesk.BridgeClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class NotificationActionButton(
    val index: Int,
    val label: String,
)

data class RawNotificationAction(
    val label: String,
    val hasActionIntent: Boolean,
    val hasRemoteInputs: Boolean,
)

data class DesktopNotificationItem(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val postTimeMs: Long,
    val clearable: Boolean,
    val isOngoing: Boolean,
    val canReply: Boolean = false,
    val replyLabel: String = "",
    val otpCode: String = "",
    val actions: List<NotificationActionButton> = emptyList(),
) {
    fun formattedTime(): String {
        if (postTimeMs <= 0L) return ""
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(postTimeMs))
    }
}

/**
 * Pure JVM-testable selection policy for generic clickable non-reply notification actions
 * (caps at 3 buttons and excludes inline-reply / blank-label / non-clickable items).
 */
object OpalNotificationActionPolicy {
    const val MAX_ACTIONS = 3

    fun selectClickableNonReplyIndices(
        rawActions: List<RawNotificationAction>,
    ): List<Pair<Int, NotificationActionButton>> {
        if (rawActions.isEmpty()) return emptyList()
        val selected = ArrayList<Pair<Int, NotificationActionButton>>(MAX_ACTIONS)
        for (srcIdx in rawActions.indices) {
            if (selected.size >= MAX_ACTIONS) break
            val action = rawActions[srcIdx]
            val cleanLabel = action.label.trim()
            if (!action.hasActionIntent || action.hasRemoteInputs || cleanLabel.isEmpty()) continue
            val buttonIdx = selected.size
            selected.add(srcIdx to NotificationActionButton(index = buttonIdx, label = cleanLabel))
        }
        return selected
    }
}

/**
 * Pure JVM-testable filtering rules deciding which Android notifications belong in the OpalDesk
 * DeX Notification Center, live heads-up banners, and OPD1 v1.2 companion notification feed
 * (filtering out self-package, ongoing foreground services, group summaries, and empty placeholders).
 */
object OpalNotificationFilter {
    private const val SELF_PKG = "io.github.lengsukq.opaldesk"
    private val IGNORED_PACKAGES = setOf(
        SELF_PKG,
        "android",
        "com.android.systemui",
        "com.android.providers.downloads",
    )

    fun shouldInclude(
        packageName: String,
        title: String,
        text: String,
        isOngoing: Boolean,
        isGroupSummary: Boolean,
        isForegroundService: Boolean,
    ): Boolean {
        if (packageName.isBlank() || packageName in IGNORED_PACKAGES) return false
        if (isOngoing || isGroupSummary || isForegroundService) return false
        return title.isNotBlank() || text.isNotBlank()
    }
}

/**
 * Pure JVM-testable JSON serializer producing exact OPD1 v1.2 {@code 0x19 TYPE_NOTIFICATION_EVENT}
 * payloads for Binder TX 50 ({@code TX_SYNC_NOTIFICATIONS}) without depending on Android's org.json stubs.
 */
object OpalNotificationJsonCodec {
    fun encodeSnapshot(items: List<DesktopNotificationItem>): String =
        encodeEvent(action = "snapshot", items = items)

    fun encodePosted(item: DesktopNotificationItem): String =
        encodeEvent(action = "posted", items = listOf(item))

    fun encodeRemoved(key: String): String {
        val sb = StringBuilder(64)
        sb.append("{\"action\":\"removed\",\"key\":")
        appendJsonString(sb, key)
        sb.append('}')
        return sb.toString()
    }

    private fun encodeEvent(action: String, items: List<DesktopNotificationItem>): String {
        val sb = StringBuilder(128 + items.size * 160)
        sb.append("{\"action\":")
        appendJsonString(sb, action)
        sb.append(",\"items\":[")
        items.forEachIndexed { idx, item ->
            if (idx > 0) sb.append(',')
            sb.append("{\"key\":")
            appendJsonString(sb, item.key)
            sb.append(",\"pkg\":")
            appendJsonString(sb, item.packageName)
            sb.append(",\"appLabel\":")
            appendJsonString(sb, item.appLabel)
            sb.append(",\"title\":")
            appendJsonString(sb, item.title)
            sb.append(",\"text\":")
            appendJsonString(sb, item.text)
            sb.append(",\"postTimeMs\":").append(item.postTimeMs.coerceAtLeast(0L))
            sb.append(",\"clearable\":").append(item.clearable)
            sb.append(",\"canReply\":").append(item.canReply)
            sb.append(",\"replyLabel\":")
            appendJsonString(sb, item.replyLabel)
            sb.append(",\"otpCode\":")
            appendJsonString(sb, item.otpCode)
            sb.append(",\"actions\":[")
            item.actions.forEachIndexed { actionIdx, button ->
                if (actionIdx > 0) sb.append(',')
                sb.append("{\"index\":").append(button.index.coerceAtLeast(0))
                sb.append(",\"label\":")
                appendJsonString(sb, button.label)
                sb.append('}')
            }
            sb.append("]}")
        }
        sb.append("]}")
        return sb.toString()
    }

    internal fun appendJsonString(out: StringBuilder, value: String) {
        out.append('"')
        for (i in value.indices) {
            val c = value[i]
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (c.code < 0x20) {
                    out.append(String.format(Locale.US, "\\u%04x", c.code))
                } else {
                    out.append(c)
                }
            }
        }
        out.append('"')
    }
}

/**
 * In-process reactive store bridging [OpalNotificationListenerService], the DeX desktop UI,
 * and the shell daemon companion channel (Binder TX 50 / `notificationAction` callback).
 */
object OpalNotificationStore {
    private val _notificationsFlow = MutableStateFlow<List<DesktopNotificationItem>>(emptyList())
    val notificationsFlow: StateFlow<List<DesktopNotificationItem>> = _notificationsFlow.asStateFlow()

    private val _bannerFlow = MutableStateFlow<DesktopNotificationItem?>(null)
    val bannerFlow: StateFlow<DesktopNotificationItem?> = _bannerFlow.asStateFlow()

    private val _listenerConnectedFlow = MutableStateFlow(false)
    val listenerConnectedFlow: StateFlow<Boolean> = _listenerConnectedFlow.asStateFlow()

    private val _accessGrantedFlow = MutableStateFlow(false)
    val accessGrantedFlow: StateFlow<Boolean> = _accessGrantedFlow.asStateFlow()

    private val _doNotDisturbFlow = MutableStateFlow(false)
    val doNotDisturbFlow: StateFlow<Boolean> = _doNotDisturbFlow.asStateFlow()

    @Volatile
    private var activeService: OpalNotificationListenerService? = null

    fun bindService(service: OpalNotificationListenerService?) {
        activeService = service
        _listenerConnectedFlow.value = service != null
        if (service != null) {
            _accessGrantedFlow.value = true
        }
        if (service == null) {
            _bannerFlow.value = null
        }
    }

    fun updateAll(items: List<DesktopNotificationItem>) {
        val sorted = items.sortedByDescending { it.postTimeMs }.take(40)
        _notificationsFlow.value = sorted
        val curBanner = _bannerFlow.value
        if (curBanner != null && sorted.none { it.key == curBanner.key }) {
            _bannerFlow.value = null
        }
        syncJsonToDaemonSafe(OpalNotificationJsonCodec.encodeSnapshot(sorted))
    }

    fun onPosted(item: DesktopNotificationItem, showBanner: Boolean) {
        val next = (_notificationsFlow.value.filterNot { it.key == item.key } + item)
            .sortedByDescending { it.postTimeMs }
            .take(40)
        _notificationsFlow.value = next
        if (showBanner && !_doNotDisturbFlow.value) {
            _bannerFlow.value = item
        }
        syncJsonToDaemonSafe(OpalNotificationJsonCodec.encodePosted(item))
    }

    fun setDoNotDisturb(enabled: Boolean) {
        _doNotDisturbFlow.value = enabled
        if (enabled) {
            _bannerFlow.value = null
        }
    }

    fun toggleDoNotDisturb(): Boolean {
        val next = !_doNotDisturbFlow.value
        setDoNotDisturb(next)
        return next
    }

    fun onRemoved(key: String) {
        _notificationsFlow.value = _notificationsFlow.value.filterNot { it.key == key }
        if (_bannerFlow.value?.key == key) {
            _bannerFlow.value = null
        }
        if (key.isNotEmpty()) {
            syncJsonToDaemonSafe(OpalNotificationJsonCodec.encodeRemoved(key))
        }
    }

    fun dismissBanner(key: String? = null) {
        if (key == null || _bannerFlow.value?.key == key) {
            _bannerFlow.value = null
        }
    }

    fun cancelNotification(key: String) {
        onRemoved(key)
        activeService?.cancelNotificationSafely(key)
    }

    fun clearAllNotifications() {
        val keys = _notificationsFlow.value.filter { it.clearable }.map { it.key }
        val remaining = _notificationsFlow.value.filterNot { it.clearable }
        _notificationsFlow.value = remaining
        _bannerFlow.value = null
        activeService?.cancelNotificationsSafely(keys)
        syncJsonToDaemonSafe(OpalNotificationJsonCodec.encodeSnapshot(remaining))
    }

    fun replyToNotification(key: String, replyText: String): Boolean {
        if (key.isBlank() || replyText.isBlank()) return false
        return activeService?.replyToNotificationSafely(key, replyText) == true
    }

    fun invokeAction(key: String, actionIndex: Int): Boolean {
        if (key.isBlank() || actionIndex < 0) return false
        return activeService?.invokeNotificationActionSafely(key, actionIndex) == true
    }

    fun toBridgeJson(items: List<DesktopNotificationItem> = _notificationsFlow.value): String =
        OpalNotificationJsonCodec.encodeSnapshot(items)

    fun requestSync() {
        val svc = activeService
        if (svc != null) {
            svc.syncActiveNotifications()
        } else {
            pushSnapshotToDaemon()
        }
    }

    fun pushSnapshotToDaemon() {
        syncJsonToDaemonSafe(toBridgeJson(_notificationsFlow.value))
    }

    /**
     * Dispatches a remote notification command originating from the daemon
     * (`BridgeProvider.call("notificationAction")`, backed by OPD1 v1.2/v1.3 `0x31`).
     */
    fun handleRemoteAction(op: String, key: String, replyText: String, actionIndex: Int = -1): Boolean {
        return when (op.trim().lowercase(Locale.ROOT)) {
            "sync" -> {
                requestSync()
                true
            }
            "dismiss" -> if (key.isNotEmpty()) {
                cancelNotification(key)
                true
            } else {
                false
            }
            "clear_all" -> {
                clearAllNotifications()
                true
            }
            "reply" -> if (key.isNotEmpty() && replyText.isNotBlank()) {
                replyToNotification(key, replyText)
            } else {
                false
            }
            "invoke_action" -> invokeAction(key, actionIndex)
            else -> false
        }
    }

    private fun syncJsonToDaemonSafe(json: String) {
        runCatching {
            if (BridgeClient.isBinderAliveFast()) {
                BridgeClient.syncNotificationsAsync(json)
            }
        }
    }

    fun refreshAccessState(ctx: Context): Boolean {
        val granted = isAccessGranted(ctx)
        _accessGrantedFlow.value = granted
        return granted
    }

    fun isAccessGranted(ctx: Context): Boolean {
        if (_listenerConnectedFlow.value) return true
        val cn = ComponentName(ctx.applicationContext, OpalNotificationListenerService::class.java)
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        val nmGranted = runCatching { nm?.isNotificationListenerAccessGranted(cn) == true }.getOrDefault(false)
        if (nmGranted) return true
        val enabled = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners").orEmpty()
        return enabled.contains(cn.flattenToString()) ||
            enabled.contains(cn.flattenToShortString()) ||
            enabled.contains(ctx.packageName)
    }

    fun openNotificationAccessSettings(ctx: Context) {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { ctx.startActivity(intent) }
    }
}
