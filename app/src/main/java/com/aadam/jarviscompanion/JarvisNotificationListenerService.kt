package com.aadam.jarviscompanion

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.ConcurrentHashMap

/**
 * Reads active notification content. Requires the user to manually grant
 * "Notification access" in system settings (Settings > Apps > Special app
 * access > Notification access) -- Android does not allow this permission
 * to be requested via the normal runtime-permission dialog, same category
 * as the Calling Account toggle: MainActivity has a button that opens the
 * right settings screen, but the actual toggle is a manual step.
 *
 * Keeps only a lightweight snapshot (package, title, text, post time) in
 * memory -- DeviceInfoService reads this directly rather than needing its
 * own binding to the system notification service (only one listener
 * component is bound by the OS at a time per declared service).
 */
class JarvisNotificationListenerService : NotificationListenerService() {

    data class NotifSnapshot(
        val packageName: String,
        val title: String,
        val text: String,
        val postTime: Long
    )

    companion object {
        // key: notification key (unique per-notification from the OS)
        private val activeNotifications = ConcurrentHashMap<String, NotifSnapshot>()

        val currentNotifications: List<NotifSnapshot>
            get() = activeNotifications.values.sortedByDescending { it.postTime }

        @Volatile
        var isListening = false
            private set
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        isListening = true
        // Populate with whatever's already showing at connect time, not
        // just notifications that arrive after this point.
        try {
            val current = activeNotifications
            current.clear()
            getActiveNotifications()?.forEach { sbn ->
                toSnapshot(sbn)?.let { current[sbn.key] = it }
            }
        } catch (e: Exception) {
            // Best-effort initial sync; onNotificationPosted will still
            // pick up subsequent notifications regardless.
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isListening = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val snap = toSnapshot(sbn) ?: return
        activeNotifications[sbn.key] = snap
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        activeNotifications.remove(sbn.key)
    }

    private fun toSnapshot(sbn: StatusBarNotification): NotifSnapshot? {
        return try {
            val extras = sbn.notification.extras
            val title = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString() ?: ""
            val text = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString() ?: ""
            NotifSnapshot(
                packageName = sbn.packageName,
                title = title,
                text = text,
                postTime = sbn.postTime
            )
        } catch (e: Exception) {
            null
        }
    }
}
