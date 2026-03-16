package com.latsmon.cibustracker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.*
import java.util.concurrent.TimeUnit

class GmailPollingWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : Worker(context, workerParams) {

    override fun doWork(): Result {
        val prefs = context.getSharedPreferences("cibus_prefs", Context.MODE_PRIVATE)
        val accountName = prefs.getString("gmail_account", null) ?: return Result.success()
        val lastCheckedId = prefs.getString("last_email_id", null)

        Log.d("GmailPolling", "Polling Gmail for account: $accountName")

        val newSpends = GmailService.fetchNewSpends(context, accountName, lastCheckedId)

        if (newSpends.isEmpty()) return Result.success()

        val db = BudgetDatabase.getDatabase(context)

        for ((amount, emailId, meta) in newSpends.reversed()) {
            db.spendDao().insertBlocking(
                Spend(
                    amount = amount,
                    timestamp = meta.timestamp,
                    businessName = meta.businessName
                )
            )
            Log.d("GmailPolling", "Auto-logged ₪$amount from ${meta.businessName} (email $emailId)")
        }

        prefs.edit().putString("last_email_id", newSpends.first().second).apply()
        showAutoLogNotification(newSpends.map { it.first })

        return Result.success()
    }

    private fun showAutoLogNotification(amounts: List<Double>) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "cibus_autolog"

        val channel = NotificationChannel(
            channelId, "Auto-logged Spends", NotificationManager.IMPORTANCE_DEFAULT
        )
        manager.createNotificationChannel(channel)

        val total = amounts.sum()
        val message = if (amounts.size == 1)
            "Auto-logged ₪%.0f from Cibus receipt".format(amounts[0])
        else
            "Auto-logged ${amounts.size} spends totalling ₪%.0f".format(total)

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Cibus spend detected")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        manager.notify(3, notification)
    }
}

fun scheduleGmailPolling(context: Context) {
    val request = PeriodicWorkRequestBuilder<GmailPollingWorker>(60, TimeUnit.MINUTES)
        .addTag("gmail_polling")
        .build()

    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        "gmail_polling",
        ExistingPeriodicWorkPolicy.KEEP,
        request
    )
}

fun cancelGmailPolling(context: Context) {
    WorkManager.getInstance(context).cancelUniqueWork("gmail_polling")
}