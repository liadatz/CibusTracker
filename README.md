CibusTracker
A personal Android app for tracking monthly Cibus (Israeli meal voucher) spending.
What it does
Cibus allocates a monthly balance (default ₪1,200) that expires at the end of each cycle. Any unspent balance is lost. This app helps you avoid wasting money by calculating the minimum you need to spend each day to guarantee your full balance is used before the month ends.
Each morning it sends a notification telling you exactly how much to spend that day — no more, no less.
Features

Daily minimum calculation — works backwards from the end of the month: remaining balance − (remaining working days − 1) × daily limit
Daily notification — fires at your chosen time with the minimum amount required. No notification if you've already spent enough today or no spending is required yet
Manual spend logging — add and delete spend entries with full history
Spending limits — blocks entries that exceed ₪200/day or your remaining monthly balance
Configurable settings:

Monthly budget amount
Month cycle start day (default: 25th)
Working days (any combination of Sun–Sat)
Notification time (slider + manual input)
Reset all spending
Force-send a test notification


Tech stack
Kotlin + Jetpack Compose
Room (local database)
WorkManager (background notifications)
SharedPreferences (settings)
Android API 29+

Notes
This is a personal-use app and is not published on the Play Store. Built for the Israeli work week (Sunday–Thursday by default) but fully configurable for any schedule.