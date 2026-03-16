package com.latsmon.cibustracker

import android.app.Application
import android.content.Context
import androidx.lifecycle.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.DayOfWeek

data class MonthPeriod(
    val label: String,
    val start: Long,
    val end: Long
)

class BudgetViewModel(application: Application) : AndroidViewModel(application) {

    private val dao = BudgetDatabase.getDatabase(application).spendDao()
    private val prefs = application.getSharedPreferences("cibus_prefs", Context.MODE_PRIVATE)

    private val _monthStartDay = MutableStateFlow(prefs.getInt("month_start_day", 25))
    val monthStartDay: StateFlow<Int> = _monthStartDay

    private val _monthlyBudget = MutableStateFlow(prefs.getFloat("monthly_budget", 1200f).toDouble())
    val monthlyBudget: StateFlow<Double> = _monthlyBudget

    private val _dailyLimit = MutableStateFlow(
        prefs.getFloat("daily_limit", -1f).let { if (it < 0) null else it.toDouble() }
    )
    val dailyLimit: StateFlow<Double?> = _dailyLimit

    private val _notificationHour = MutableStateFlow(prefs.getInt("notification_hour", 9))
    val notificationHour: StateFlow<Int> = _notificationHour

    private val _notificationMinute = MutableStateFlow(prefs.getInt("notification_minute", 0))
    val notificationMinute: StateFlow<Int> = _notificationMinute

    private val _workingDays = MutableStateFlow(loadWorkingDays())
    val workingDays: StateFlow<Set<DayOfWeek>> = _workingDays

    fun saveDailyLimit(limit: Double?) {
        prefs.edit().putFloat("daily_limit", limit?.toFloat() ?: -1f).apply()
        _dailyLimit.value = limit
    }

    private fun loadWorkingDays(): Set<DayOfWeek> {
        val saved = prefs.getStringSet("working_days", null)
        return if (saved != null) {
            saved.mapNotNull { runCatching { DayOfWeek.valueOf(it) }.getOrNull() }.toSet()
        } else {
            setOf(DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY)
        }
    }

    fun saveWorkingDays(days: Set<DayOfWeek>) {
        prefs.edit().putStringSet("working_days", days.map { it.name }.toSet()).apply()
        _workingDays.value = days
    }

    fun saveMonthStartDay(day: Int) {
        prefs.edit().putInt("month_start_day", day).apply()
        _monthStartDay.value = day
    }

    fun saveMonthlyBudget(budget: Double) {
        prefs.edit().putFloat("monthly_budget", budget.toFloat()).apply()
        _monthlyBudget.value = budget
    }

    fun saveDailyLimit(limit: Double) {
        prefs.edit().putFloat("daily_limit", limit.toFloat()).apply()
        _dailyLimit.value = limit
    }

    fun saveNotificationTime(hour: Int, minute: Int) {
        prefs.edit()
            .putInt("notification_hour", hour)
            .putInt("notification_minute", minute)
            .apply()
        _notificationHour.value = hour
        _notificationMinute.value = minute
    }

    val allSpends: StateFlow<List<Spend>> = dao.getAllSpends()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val totalSpentThisMonth: StateFlow<Double> = dao.getTotalSpentSince(getMonthStart())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    val remainingBalance: StateFlow<Double> = combine(
        totalSpentThisMonth, _monthlyBudget
    ) { spent, budget ->
        (budget - spent).coerceAtLeast(0.0)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 1200.0)

    val todaySpent: StateFlow<Double> = dao.getTotalSpentSince(getStartOfToday())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    val minimumToSpendToday: StateFlow<Double> = combine(
        remainingBalance, _workingDays, _monthStartDay, _dailyLimit
    ) { remaining, workDays, startDay, limit ->
        val workingDaysLeft = countWorkingDaysLeft(workDays, startDay)
        if (workingDaysLeft <= 0) return@combine 0.0
        val effectiveLimit = limit ?: remaining
        val minimum = remaining - (workingDaysLeft - 1) * effectiveLimit
        minimum.coerceIn(0.0, effectiveLimit)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    // All months that have spend data
    val availableMonths: StateFlow<List<MonthPeriod>> =
        dao.getEarliestSpendTimestamp().map { earliest ->
            if (earliest == null) return@map emptyList()
            buildMonthList(earliest)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun addSpend(amount: Double, timestamp: Long = System.currentTimeMillis()): String? {
        val currentRemaining = remainingBalance.value
        val currentTodaySpent = todaySpent.value
        val limit = _dailyLimit.value

        return when {
            amount > currentRemaining -> "Cannot spend ₪%.0f — only ₪%.0f left this month".format(amount, currentRemaining)
            limit != null && amount > (limit - currentTodaySpent) ->
                "Cannot spend ₪%.0f — only ₪%.0f left for today".format(amount, limit - currentTodaySpent)
            else -> {
                viewModelScope.launch { dao.insert(Spend(amount = amount, timestamp = timestamp)) }
                null
            }
        }
    }
    private fun buildMonthList(earliestTimestamp: Long): List<MonthPeriod> {
        val startDay = _monthStartDay.value
        val today = LocalDate.now()
        val earliest = java.time.Instant.ofEpochMilli(earliestTimestamp)
            .atZone(ZoneId.systemDefault()).toLocalDate()

        val months = mutableListOf<MonthPeriod>()
        var cursor = today

        while (!cursor.isBefore(earliest)) {
            val periodStart = if (cursor.dayOfMonth >= startDay)
                cursor.withDayOfMonth(startDay)
            else
                cursor.minusMonths(1).withDayOfMonth(startDay)

            val periodEnd = periodStart.plusMonths(1).withDayOfMonth(startDay - 1)

            val startMillis = periodStart.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val endMillis = periodEnd.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

            val label = "%d %s – %d %s".format(
                periodStart.dayOfMonth,
                periodStart.month.name.lowercase().replaceFirstChar { it.uppercase() }.take(3),
                periodEnd.dayOfMonth,
                periodEnd.month.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)
            )

            if (months.none { it.start == startMillis }) {
                months.add(MonthPeriod(label, startMillis, endMillis))
            }

            cursor = periodStart.minusDays(1)
        }

        return months
    }

    fun getSpendsBetween(start: Long, end: Long): Flow<List<Spend>> =
        dao.getSpendsBetween(start, end)

    fun addSpendToMonth(amount: Double, timestamp: Long): String? {
        viewModelScope.launch { dao.insert(Spend(amount = amount, timestamp = timestamp)) }
        return null
    }

    fun deleteSpend(spend: Spend) {
        viewModelScope.launch { dao.delete(spend) }
    }

    fun resetAllSpending() {
        viewModelScope.launch { dao.deleteAll() }
    }

    fun getMonthStart(): Long {
        val today = LocalDate.now()
        val startDay = _monthStartDay.value
        val monthStart = if (today.dayOfMonth >= startDay)
            today.withDayOfMonth(startDay)
        else
            today.minusMonths(1).withDayOfMonth(startDay)
        return monthStart.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    private fun getStartOfToday(): Long {
        return LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    fun countWorkingDaysLeft(
        workDays: Set<DayOfWeek> = _workingDays.value,
        startDay: Int = _monthStartDay.value
    ): Int {
        val today = LocalDate.now()
        val monthEnd = if (today.dayOfMonth >= startDay)
            today.plusMonths(1).withDayOfMonth(startDay - 1)
        else
            today.withDayOfMonth(startDay - 1)

        var count = 0
        var date = today
        while (!date.isAfter(monthEnd)) {
            if (date.dayOfWeek in workDays) count++
            date = date.plusDays(1)
        }
        return count
    }
}