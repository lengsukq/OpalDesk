package io.github.lengsukq.opaldesk.notifications

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.ConcurrentHashMap

/**
 * Standard AOSP [NotificationListenerService] streaming phone app notifications (including
 * inline [RemoteInput] quick-reply targets) and active media playback sessions into OpalDesk's
 * DeX desktop notification center and OPD1 v1.2 companion clients (100% rootless & OEM-agnostic).
 */
class OpalNotificationListenerService : NotificationListenerService() {

    private data class ReplyTarget(
        val pendingIntent: PendingIntent,
        val remoteInput: RemoteInput,
        val label: String,
    )

    private val appLabelCache = ConcurrentHashMap<String, String>()
    private val replyTargets = ConcurrentHashMap<String, ReplyTarget>()

    override fun onListenerConnected() {
        super.onListenerConnected()
        OpalNotificationStore.bindService(this)
        syncActiveNotifications()
        OpalMediaSessionMonitor.start(this)
    }

    override fun onListenerDisconnected() {
        OpalMediaSessionMonitor.stop()
        replyTargets.clear()
        OpalNotificationStore.bindService(null)
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        OpalMediaSessionMonitor.stop()
        replyTargets.clear()
        OpalNotificationStore.bindService(null)
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val item = toDesktopItem(sbn)
        if (item == null) {
            replyTargets.remove(sbn.key)
            return
        }
        OpalNotificationStore.onPosted(item, showBanner = true)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val key = sbn?.key ?: return
        replyTargets.remove(key)
        OpalNotificationStore.onRemoved(key)
    }

    fun syncActiveNotifications() {
        val active = runCatching { activeNotifications }.getOrNull() ?: return
        val seenKeys = HashSet<String>(active.size)
        val items = active.mapNotNull { sbn ->
            val item = toDesktopItem(sbn)
            if (item != null) {
                seenKeys.add(item.key)
            }
            item
        }
        replyTargets.keys.retainAll(seenKeys)
        OpalNotificationStore.updateAll(items)
    }

    fun cancelNotificationSafely(key: String) {
        replyTargets.remove(key)
        runCatching { cancelNotification(key) }
    }

    fun cancelNotificationsSafely(keys: List<String>) {
        if (keys.isEmpty()) return
        keys.forEach { replyTargets.remove(it) }
        runCatching { cancelNotifications(keys.toTypedArray()) }
    }

    /**
     * Dispatches an inline quick-reply via the notification's cached [RemoteInput] + [PendingIntent].
     */
    fun replyToNotificationSafely(key: String, replyText: String): Boolean {
        if (key.isBlank() || replyText.isBlank()) return false
        val target = replyTargets[key] ?: return false
        return runCatching {
            val fillInIntent = Intent()
            val results = Bundle().apply {
                putCharSequence(target.remoteInput.resultKey, replyText)
            }
            RemoteInput.addResultsToIntent(arrayOf(target.remoteInput), fillInIntent, results)
            target.pendingIntent.send(this, 0, fillInIntent)
            true
        }.getOrDefault(false)
    }

    private fun toDesktopItem(sbn: StatusBarNotification): DesktopNotificationItem? {
        val pkg = sbn.packageName.orEmpty()
        val notif = sbn.notification ?: return null
        val flags = notif.flags
        val isOngoing = sbn.isOngoing || (flags and Notification.FLAG_ONGOING_EVENT) != 0
        val isGroupSummary = (flags and Notification.FLAG_GROUP_SUMMARY) != 0
        val isFgs = (flags and Notification.FLAG_FOREGROUND_SERVICE) != 0

        val extras = notif.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val bigText = extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()
        val plainText = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val body = bigText.ifEmpty { plainText }

        if (!OpalNotificationFilter.shouldInclude(
                packageName = pkg,
                title = title,
                text = body,
                isOngoing = isOngoing,
                isGroupSummary = isGroupSummary,
                isForegroundService = isFgs,
            )
        ) {
            return null
        }

        val replyTarget = extractReplyTarget(notif)
        if (replyTarget != null) {
            replyTargets[sbn.key] = replyTarget
        } else {
            replyTargets.remove(sbn.key)
        }

        val appLabel = resolveAppLabel(pkg)
        return DesktopNotificationItem(
            key = sbn.key,
            packageName = pkg,
            appLabel = appLabel,
            title = title.ifEmpty { appLabel },
            text = body,
            postTimeMs = sbn.postTime,
            clearable = sbn.isClearable,
            isOngoing = isOngoing,
            canReply = replyTarget != null,
            replyLabel = replyTarget?.label.orEmpty(),
            otpCode = "",
        )
    }

    private fun extractReplyTarget(notif: Notification): ReplyTarget? {
        val actions = notif.actions ?: return null
        for (action in actions) {
            val pi = action?.actionIntent ?: continue
            val inputs = action.remoteInputs ?: continue
            val ri = inputs.firstOrNull { input ->
                input != null && !input.resultKey.isNullOrBlank() && input.allowFreeFormInput
            } ?: inputs.firstOrNull { input ->
                input != null && !input.resultKey.isNullOrBlank()
            } ?: continue
            val label = action.title?.toString()?.trim().orEmpty()
                .ifEmpty { ri.label?.toString()?.trim().orEmpty() }
                .ifEmpty { "回复" }
            return ReplyTarget(pendingIntent = pi, remoteInput = ri, label = label)
        }
        return null
    }

    private fun resolveAppLabel(pkg: String): String {
        return appLabelCache.getOrPut(pkg) {
            try {
                val pm = packageManager
                val ai = pm.getApplicationInfo(pkg, 0)
                pm.getApplicationLabel(ai).toString().trim().ifEmpty { pkg }
            } catch (_: Throwable) {
                pkg
            }
        }
    }
}
