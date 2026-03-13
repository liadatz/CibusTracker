package com.latsmon.cibustracker

import android.app.Application
import android.content.Context
import androidx.lifecycle.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.DayOfWeek

class BudgetViewModel(application: Application) : AndroidViewModel(application) {

    private val dao = BudgetDatabase.getDatabase(application).spendDao()
    private val prefs = application.getSharedPreferences("cibus_prefs", Context.MODE_PRIVATE)

    private val _monthStartDay = MutableStateFlow(prefs.getInt("month_start_day", 25))
    val monthStartDay: StateFlow<Int> = _monthStartDay

    private val _monthlyBudget = MutableStateFlow(prefs.getFloat("monthly_budget", 1200f).toDouble())
    val monthlyBudget: StateFlow<Double> = _monthlyBudget

    private val _notificationHour = MutableStateFlow(prefs.getInt("notification_hour", 9))
    val notificationHour: StateFlow<Int> = _notificationHour

    private val _notificationMinute = MutableStateFlow(prefs.getInt("notification_minute", 0))
    val notificationMinute: StateFlow<Int> = _notificationMinute

    // Working days stored as a set of DayOfWeek ordinals (1=Mon ... 7=Sun)
    private val _workingDays = MutableStateFlow(loadWorkingDays())
    val workingDays: StateFlow<Set<DayOfWeek>> = _workingDays

    private fun loadWorkingDays(): Set<DayOfWeek> {
        val saved = prefs.getStringSet("working_days", null)
        return if (saved != null) {
            saved.mapNotNull { runCatching { DayOfWeek.valueOf(it) }.getOrNull() }.toSet()
        } else {
            // Default: Sunday to Thursday
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
        remainingBalance, _workingDays, _monthStartDay
    ) { remaining, workDays, startDay ->
        val workingDaysLeft = countWorkingDaysLeft(workDays, startDay)
        if (workingDaysLeft <= 0) return@combine 0.0
        val minimum = remaining - (workingDaysLeft - 1) * 200.0
        minimum.coerceIn(0.0, 200.0)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    fun addSpend(amount: Double): String? {
        val currentRemaining = remainingBalance.value
        val currentTodaySpent = todaySpent.value
        val todayLeft = 200.0 - currentTodaySpent

        return when {
            amount > currentRemaining -> "Cannot spend ₪%.0f — only ₪%.0f left this month".format(amount, currentRemaining)
            amount > todayLeft -> "Cannot spend ₪%.0f — only ₪%.0f left for today".format(amount, todayLeft)
            else -> {
                viewModelScope.launch { dao.insert(Spend(amount = amount)) }
                null
            }
        }
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

    fun countWorkingDaysLeft(workDays: Set<DayOfWeek> = _workingDays.value, startDay: Int = _monthStartDay.value): Int {
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