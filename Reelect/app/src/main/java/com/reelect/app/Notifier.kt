package com.reelect.app

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

/** Daily (and Sunday weekly) summary notifications, scheduled with a single inexact alarm. */
object Notifier {
    private const val CH = "summary"

    private fun ensureChannel(c: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            c.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CH, "Daily & weekly summary", NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    private fun post(c: Context, id: Int, title: String, text: String) {
        ensureChannel(c)
        val open = PendingIntent.getActivity(
            c, 0, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(c, CH) else Notification.Builder(c)
        b.setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
        try { c.getSystemService(NotificationManager::class.java).notify(id, b.build()) } catch (_: Exception) {}
    }

    fun dailyText(c: Context): String {
        val d = Store.todayDay(c)
        val limit = Store.limit(c)
        if (d.total == 0) return "No Reels or Shorts today. Your streak is safe 🌿"
        val s = Stats.score(d, limit)
        return "You watched ${d.total} Reels and Shorts today, about ${Stats.fmtDuration(d.sec)}. " +
            "Scroll score ${s.value} ${Stats.moodEmoji(Stats.mood(d, limit))}"
    }

    fun weeklyText(c: Context): String {
        val w = Stats.weekCompare(Store.last15(c))
        val trend = when {
            w.prevTotal == 0 -> ""
            w.changePct < 0 -> " That's ${-w.changePct}% less than last week. Great job! 🎉"
            w.changePct > 0 -> " That's ${w.changePct}% more than last week."
            else -> " Same as last week."
        }
        return "This week: ${w.thisTotal} videos, about ${Stats.fmtDuration(w.thisSec)}.$trend"
    }

    fun sendDaily(c: Context) = post(c, 1, "Today's scroll summary", dailyText(c))
    fun sendWeekly(c: Context) = post(c, 2, "Your week in scrolling", weeklyText(c))

    private fun pending(c: Context) = PendingIntent.getBroadcast(
        c, 1, Intent(c, SummaryReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /** (Re)schedules the next summary, or cancels it when notifications are switched off. */
    fun schedule(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java)
        val pi = pending(c)
        if (!Store.bool(c, Store.KEY_NOTIF, false)) { am.cancel(pi); return }
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, Store.int(c, Store.KEY_SUMMARY_HOUR, 21))
            set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis() + 60_000) add(Calendar.DAY_OF_YEAR, 1)
        }
        try { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi) } catch (_: Exception) {}
    }
}

class SummaryReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent?) {
        if (Store.bool(c, Store.KEY_NOTIF, false)) {
            Notifier.sendDaily(c)
            if (Calendar.getInstance().get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) Notifier.sendWeekly(c)
        }
        Notifier.schedule(c)
    }
}
