package com.lnsgroup.elise.watch.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.util.concurrent.ConcurrentLinkedDeque

private const val TAG = "EliseNotifService"
private const val MAX_NOTIFS = 12

private val IGNORED_PACKAGES = setOf(
    "com.lnsgroup.elise.watch",
    "android",
    "com.android.systemui",
    "com.google.android.wearable.app",
)

private val NOTIFICATION_QUERY_KEYWORDS = listOf(
    "notification", "notif", "message", "alerte", "qu'est-ce qui s'est passé",
    "lis mes", "lire mes", "what's new", "quoi de neuf",
)

/**
 * Capture les notifications actives sur la montre et les rend disponibles
 * pour qu'Élise puisse les lire à voix haute sur commande vocale.
 *
 * Permission requise : Settings → Apps spéciales → Accès aux notifications
 */
class EliseNotificationService : NotificationListenerService() {

    companion object {
        private val _notifications = ConcurrentLinkedDeque<CapturedNotification>()

        fun getRecent(): List<CapturedNotification> = _notifications.toList()

        fun buildContextString(): String {
            val notifs = getRecent()
            if (notifs.isEmpty()) return ""
            val lines = notifs.takeLast(8).joinToString(" | ") {
                "${it.appLabel}: ${it.title}${if (it.text.isNotBlank()) " — ${it.text}" else ""}"
            }
            return "[notifications: $lines]"
        }

        fun isNotificationQuery(transcript: String): Boolean {
            val t = transcript.lowercase()
            return NOTIFICATION_QUERY_KEYWORDS.any { it in t }
        }
    }

    data class CapturedNotification(
        val appLabel: String,
        val title: String,
        val text: String,
        val timeMs: Long,
        val key: String,
    )

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val extras = sbn.notification?.extras ?: return
        val pkg = sbn.packageName ?: return

        if (pkg in IGNORED_PACKAGES) return

        val appLabel = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrElse { pkg.substringAfterLast('.') }

        val title = extras.getCharSequence("android.title")?.toString()?.trim() ?: return
        val text  = extras.getCharSequence("android.text")?.toString()?.trim() ?: ""

        if (title.isBlank()) return

        val captured = CapturedNotification(
            appLabel = appLabel,
            title    = title,
            text     = text,
            timeMs   = sbn.postTime,
            key      = sbn.key,
        )

        _notifications.removeIf { it.key == sbn.key }
        _notifications.addLast(captured)
        while (_notifications.size > MAX_NOTIFS) _notifications.pollFirst()

        Log.d(TAG, "Captured: [$appLabel] $title")
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        _notifications.removeIf { it.key == sbn.key }
    }

    override fun onListenerConnected() {
        Log.i(TAG, "Notification listener connected")
        runCatching { activeNotifications?.forEach { onNotificationPosted(it) } }
    }
}
