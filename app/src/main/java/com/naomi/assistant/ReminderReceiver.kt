package com.naomi.assistant

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Puts reminders on the system's alarm clock, to the minute and also while the phone dozes —
 * [ReminderReceiver] is woken for each. Alarms don't survive a restart, so the boot receiver and
 * the app itself put them all back.
 */
object ReminderAlarms {

    fun schedule(context: Context, reminder: Reminder) {
        val am = context.getSystemService(AlarmManager::class.java)
        val fire = fireIntent(context, reminder.id)
        try {
            // Without the exact-alarm permission (Android 12 lets it be taken back), within a few minutes will do.
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.at, fire)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.at, fire)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.at, fire)
        }
    }

    fun cancel(context: Context, id: Long) {
        context.getSystemService(AlarmManager::class.java).cancel(fireIntent(context, id))
    }

    /** Every reminder back on the alarm clock — after a restart or an update. Ones whose time passed meanwhile go off now. */
    fun rescheduleAll(context: Context) {
        ReminderStore.get(context).all().forEach { schedule(context, it) }
    }

    private fun fireIntent(context: Context, id: Long): PendingIntent = PendingIntent.getBroadcast(
        context, requestCode(id),
        Intent(context, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION_FIRE).putExtra(ReminderReceiver.EXTRA_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    internal fun requestCode(id: Long): Int = (id % Int.MAX_VALUE).toInt()
}

/**
 * A reminder going off: shown as a notification — with Snooze and Done — and said out loud,
 * unless the phone is on silent or vibrate, in Do Not Disturb, or on a call. Also puts every
 * reminder back on the alarm clock after the app is updated.
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ID, -1)
        when (intent.action) {
            ACTION_FIRE -> fire(context, id)
            ACTION_SNOOZE -> snooze(context, id, intent)
            ACTION_DONE -> context.getSystemService(NotificationManager::class.java).cancel(notificationId(id))
            Intent.ACTION_MY_PACKAGE_REPLACED -> ReminderAlarms.rescheduleAll(context)
        }
    }

    private fun fire(context: Context, id: Long) {
        val store = ReminderStore.get(context)
        val reminder = store.get(id) ?: return // cancelled in the meantime
        // An alarm left over from before the reminder moved on (a repeat, put back after an update): not its time yet.
        if (reminder.at > System.currentTimeMillis() + EARLY_MS) {
            ReminderAlarms.schedule(context, reminder)
            return
        }
        store.fired(id)?.let { ReminderAlarms.schedule(context, it) }
        show(context, reminder)
        if (mayTalk(context)) say(context, ReminderText.alert(reminder.text, reminder.portuguese))
    }

    private fun show(context: Context, reminder: Reminder) {
        val pt = reminder.portuguese
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, ReminderText.channel(pt), NotificationManager.IMPORTANCE_HIGH))
        }
        val code = notificationId(reminder.id)
        val open = PendingIntent.getActivity(
            context, code, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        fun button(action: String, label: String): Notification.Action {
            val press = Intent(context, ReminderReceiver::class.java).setAction(action)
                .putExtra(EXTRA_ID, reminder.id).putExtra(EXTRA_TEXT, reminder.text).putExtra(EXTRA_PT, pt)
            val pi = PendingIntent.getBroadcast(context, code, press, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            return Notification.Action.Builder(Icon.createWithResource(context, android.R.drawable.ic_popup_reminder), label, pi).build()
        }
        val text = reminder.text.replaceFirstChar { it.uppercase() }
        nm.notify(code, Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Naomi")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setWhen(reminder.at)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(button(ACTION_SNOOZE, ReminderText.snooze(pt)))
            .addAction(button(ACTION_DONE, ReminderText.done(pt)))
            .build())
    }

    /** "Snooze 10 min": the same reminder again, once, ten minutes from now. */
    private fun snooze(context: Context, id: Long, intent: Intent) {
        context.getSystemService(NotificationManager::class.java).cancel(notificationId(id))
        val text = intent.getStringExtra(EXTRA_TEXT) ?: return
        val later = ReminderStore.get(context).add(text, System.currentTimeMillis() + SNOOZE_MS, Repeat.NONE, intent.getBooleanExtra(EXTRA_PT, false))
        ReminderAlarms.schedule(context, later)
    }

    /** Whether she may speak up: the ringer's on, nothing's asked for quiet, and there's no call. */
    private fun mayTalk(context: Context): Boolean {
        val audio = context.getSystemService(AudioManager::class.java)
        val quiet = context.getSystemService(NotificationManager::class.java).currentInterruptionFilter
        return audio.ringerMode == AudioManager.RINGER_MODE_NORMAL && audio.mode == AudioManager.MODE_NORMAL &&
            (quiet == NotificationManager.INTERRUPTION_FILTER_ALL || quiet == NotificationManager.INTERRUPTION_FILTER_UNKNOWN)
    }

    /**
     * Says [line] in her voice once the notification's own sound has played. The receiver is kept
     * alive while she talks ([goAsync]), but never past what Android allows it.
     */
    private fun say(context: Context, line: String) {
        val pending = goAsync()
        val main = Handler(Looper.getMainLooper())
        val speaker = Speaker(context.applicationContext)
        val finished = AtomicBoolean(false)
        val finish = {
            if (finished.compareAndSet(false, true)) {
                speaker.shutdown()
                pending.finish()
            }
        }
        main.postDelayed({ speaker.speakWhenReady(line) { finish() } }, SPEAK_AFTER_MS)
        main.postDelayed({ finish() }, SPEAK_LIMIT_MS)
    }

    companion object {
        const val ACTION_FIRE = "com.naomi.assistant.REMINDER_FIRE"
        const val ACTION_SNOOZE = "com.naomi.assistant.REMINDER_SNOOZE"
        const val ACTION_DONE = "com.naomi.assistant.REMINDER_DONE"
        const val EXTRA_ID = "reminder_id"
        private const val EXTRA_TEXT = "reminder_text"
        private const val EXTRA_PT = "reminder_pt"
        private const val CHANNEL = "naomi_reminders"
        private const val SNOOZE_MS = 10 * 60_000L
        // An alarm this early is still on time; any earlier, it's not this reminder's.
        private const val EARLY_MS = 60_000L
        // After the notification sound; and the most a receiver can hold on for (Android allows about 10 s).
        private const val SPEAK_AFTER_MS = 1_200L
        private const val SPEAK_LIMIT_MS = 9_000L
        // Clear of the app's other notifications (1–3).
        private const val NOTIFICATION_BASE = 1000

        fun notificationId(id: Long): Int = NOTIFICATION_BASE + (id % 1_000_000).toInt()
    }
}
