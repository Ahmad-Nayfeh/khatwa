package com.khatwa.app.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.khatwa.app.R
import com.khatwa.app.i18n.I18n
import com.khatwa.app.steps.Today
import com.khatwa.app.ui.MainActivity
import com.khatwa.app.util.Fmt

class Notifications(private val context: Context) {
    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun createChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, context.getString(R.string.notification_channel_service), NotificationManager.IMPORTANCE_LOW).apply {
                description = I18n.current.channelServiceDescription
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_EVENTS, context.getString(R.string.notification_channel_events), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = I18n.current.channelEventsDescription
            }
        )
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun buildServiceNotification(today: Today): Notification {
        val remaining = (today.goal - today.steps).coerceAtLeast(0)
        val s = I18n.current
        val text = if (remaining == 0L) s.goalDoneToday else s.remainingSteps(Fmt.n(remaining))
        return NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(s.stepsOfGoal(Fmt.n(today.steps), Fmt.n(today.goal.toLong())))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(openAppIntent())
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    fun updateServiceNotification(today: Today) {
        if (!canPost()) return
        manager.notify(SERVICE_ID, buildServiceNotification(today))
    }

    fun event(id: Int, title: String, text: String, silent: Boolean = false) {
        if (!canPost()) return
        val n = NotificationCompat.Builder(context, CHANNEL_EVENTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setSilent(silent)
            .setContentIntent(openAppIntent())
            .build()
        manager.notify(id, n)
    }

    private fun canPost(): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    companion object {
        const val CHANNEL_SERVICE = "khatwa_service"
        const val CHANNEL_EVENTS = "khatwa_events"
        const val SERVICE_ID = 1001
        const val ID_UNLOCKED = 2001
        const val ID_LOCK_WARN = 2002
        const val ID_MORNING = 2003
        const val ID_WEIGHT = 2004
        const val ID_LOCK_STARTED = 2005
    }
}
