package com.latsmon.cibustracker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.*
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.DayOfWeek
import java.time.ZoneId
import java.util.concurrent.TimeUnit

class NotificationWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : Worker(context, workerParams) {

    override fun doWork(): Result {
        val today = LocalDate.now()
        val prefs = context.getSharedPreferences("cibus_prefs", Context.MODE_PRIVATE)

        val workingDays = prefs.getStringSet("working_days", null)
            ?.mapNotNull { runCatching { DayOfWeek.valueOf(it) }.getOrNull() }?.toSet()
            ?: setOf(DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY)

        val hour = prefs.getInt("notification_hour", 9)
        val minute = prefs.getInt("notification_minute", 0)

        // Skip if today is not a working day
        if (today.dayOfWeek !in workingDays) {
            scheduleNext(hour, minute)
            return Result.success()
        }

        val db = BudgetDatabase.getDatabase(context)
        val monthStartDay = prefs.getInt("month_start_day", 25)
        val monthlyBudget = prefs.getFloat("monthly_budget", 1200f).toDouble()

        val monthStart = run {
            val d = if (today.dayOfMonth >= monthStartDay)
                today.withDayOfMonth(monthStartDay)
            else
                today.minusMonths(1).withDayOfMonth(monthStartDay)
            d.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }

        val startOfToday = today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val totalSpent = db.spendDao().getTotalSpentSinceBlocking(monthStart)
        val todaySpent = db.spendDao().getTotalSpentSinceBlocking(startOfToday)
        val remaining = (monthlyBudget - totalSpent).coerceAtLeast(0.0)

        val monthEnd = if (today.dayOfMonth >= monthStartDay)
            today.plusMonths(1).withDayOfMonth(monthStartDay - 1)
        else
            today.withDayOfMonth(monthStartDay - 1)

        var workingDaysLeft = 0
        var date = today
        while (!date.isAfter(monthEnd)) {
            if (date.dayOfWeek in workingDays) workingDaysLeft++
            date = date.plusDays(1)
        }

        val dailyLimit = prefs.getFloat("daily_limit", 200f).toDouble()
        val minimum = if (workingDaysLeft > 0)
            (remaining - (workingDaysLeft - 1) * dailyLimit).coerceIn(0.0, dailyLimit)
        else 0.0

        if (minimum <= 0.0 || todaySpent >= dailyLimit) {
            scheduleNext(hour, minute)
            return Result.success()
        }

        showNotification(minimum, remaining)
        scheduleNext(hour, minute)
        return Result.success()
    }

    private fun showNotification(minimum: Double, remaining: Double) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "cibus_daily"

        val channel = NotificationChannel(
            channelId, "Daily Cibus Reminder", NotificationManager.IMPORTANCE_DEFAULT
        )
        manager.createNotificationChannel(channel)

        val message = if (minimum >= 200.0)
            "Spend ₪200 today — ₪%.0f left this month".format(remaining)
        else
            "Spend at least ₪%.0f today — ₪%.0f left this month".format(minimum, remaining)

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Cibus Reminder")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        manager.notify(1, notification)
    }

    fun scheduleNext(hour: Int, minute: Int) {
        val now = LocalDateTime.now()
        val next = now.toLocalDate().plusDays(1).atTime(hour, minute)
        val delay = java.time.Duration.between(now, next).toMillis()

        val request = OneTimeWorkRequestBuilder<NotificationWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .addTag("daily_notification")
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "daily_notification",
            ExistingWorkPolicy.REPLACE,
            request
        )
    }
}

object ForceNotificationHelper {
    fun show(context: Context, minimum: Double, remaining: Double) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "cibus_daily"

        val channel = NotificationChannel(
            channelId, "Daily Cibus Reminder", NotificationManager.IMPORTANCE_DEFAULT
        )
        manager.createNotificationChannel(channel)

        val message = when {
            minimum <= 0.0 -> "No spending required today — ₪%.0f left this month".format(remaining)
            minimum >= 200.0 -> "Spend ₪200 today — ₪%.0f left this month".format(remaining)
            else -> "Spend at least ₪%.0f today — ₪%.0f left this month".format(minimum, remaining)
        }

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Cibus Reminder")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        manager.notify(2, notification)
    }
}