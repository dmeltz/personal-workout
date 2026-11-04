package com.dmeltz.personalworkout.reminder

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.dmeltz.personalworkout.MainActivity
import com.dmeltz.personalworkout.R
import com.dmeltz.personalworkout.Repository
import com.dmeltz.personalworkout.currentMood
import com.dmeltz.personalworkout.today
import com.dmeltz.personalworkout.core.Mascot
import com.dmeltz.personalworkout.widget.StreakWidget
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** A daily notification at the chosen time, skipped when today's practice is already done. */
object Reminders {
    private const val CHANNEL_ID = "daily_reminder"
    private const val NOTIFICATION_ID = 1

    fun schedule(context: Context) {
        val state = Repository.get(context).state
        val alarms = context.getSystemService(AlarmManager::class.java)
        val pending = alarmIntent(context)
        alarms.cancel(pending)
        if (!state.reminderEnabled) return

        val now = java.time.LocalDateTime.now()
        var next = LocalDate.now().atTime(LocalTime.of(state.reminderHour, state.reminderMinute))
        if (!next.isAfter(now)) next = next.plusDays(1)
        val millis = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        alarms.setInexactRepeating(AlarmManager.RTC_WAKEUP, millis, AlarmManager.INTERVAL_DAY, pending)
    }

    private fun alarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, ReminderReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun notifyIfNeeded(context: Context) {
        val state = Repository.get(context).state
        if (state.practicedOn(today())) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.reminder_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = Mascot.greeting(currentMood(state), state.streak(today()), today())
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.reminder_title))
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        StreakWidget.updateAll(context)
        Reminders.notifyIfNeeded(context)
    }
}

/** Alarms are cleared on reboot and on app update, so they are set again here. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Reminders.schedule(context)
        StreakWidget.updateAll(context)
    }
}
