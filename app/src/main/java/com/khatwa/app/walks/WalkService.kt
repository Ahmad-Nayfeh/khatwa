package com.khatwa.app.walks

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.khatwa.app.KhatwaApp
import com.khatwa.app.R
import com.khatwa.app.i18n.I18n
import com.khatwa.app.notifications.Notifications
import com.khatwa.app.ui.MainActivity
import com.khatwa.core.walk.Distances

/** Keeps the app running (and GPS on) while a walk is recorded; the notification can stop it. */
class WalkService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val recorder = KhatwaApp.container(this).walks
        if (intent?.action == ACTION_STOP) {
            recorder.stopAsync()
            return START_NOT_STICKY
        }
        val walk = recorder.active.value
        if (walk == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val n = build(this, walk)
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(Notifications.ID_WALK, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else startForeground(Notifications.ID_WALK, n)
        } catch (e: Exception) {
            // No location permission (Android 14 refuses a location service without it).
            Log.w(TAG, "cannot start in the foreground: $e")
            recorder.stopAsync()
            return START_NOT_STICKY
        }
        if (!recorder.beginRecording()) recorder.stopAsync()
        return START_NOT_STICKY
    }

    companion object {
        private const val TAG = "WalkService"
        private const val ACTION_STOP = "com.khatwa.app.walk.STOP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, WalkService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WalkService::class.java))
        }

        fun update(context: Context, walk: ActiveWalk) {
            if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                runCatching { NotificationManagerCompat.from(context).notify(Notifications.ID_WALK, build(context, walk)) }
            }
        }

        private fun build(context: Context, walk: ActiveWalk): Notification {
            val s = I18n.current
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val stop = PendingIntent.getService(
                context, 1, Intent(context, WalkService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val text = if (walk.hasFix) s.walkKm(Distances.km(walk.distanceM)) else s.walkWaitingForGps
            return NotificationCompat.Builder(context, Notifications.CHANNEL_SERVICE)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(walk.placeName?.let { s.walkingTo(it) } ?: s.walkRecording)
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setUsesChronometer(true)
                .setWhen(walk.startMs)
                .setContentIntent(open)
                .addAction(0, s.stopWalk, stop)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
        }
    }
}
