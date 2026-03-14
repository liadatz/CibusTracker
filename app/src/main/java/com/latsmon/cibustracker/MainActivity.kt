package com.latsmon.cibustracker

import android.Manifest
import android.accounts.AccountManager
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.work.*
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.services.gmail.GmailScopes
import java.text.SimpleDateFormat
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.util.*
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        scheduleDailyNotification(this)

        setContent {
            MaterialTheme {
                CibusApp()
            }
        }
    }
}

fun scheduleDailyNotification(context: Context) {
    val prefs = context.getSharedPreferences("cibus_prefs", Context.MODE_PRIVATE)
    val hour = prefs.getInt("notification_hour", 9)
    val minute = prefs.getInt("notification_minute", 0)

    val workManager = WorkManager.getInstance(context)
    val existing = workManager.getWorkInfosByTag("daily_notification").get()
    if (existing.any { !it.state.isFinished }) return

    val now = LocalDateTime.now()
    val todayAtTime = now.toLocalDate().atTime(hour, minute)
    val next = if (now.isBefore(todayAtTime)) todayAtTime else todayAtTime.plusDays(1)
    val delay = java.time.Duration.between(now, next).toMillis()

    val request = OneTimeWorkRequestBuilder<NotificationWorker>()
        .setInitialDelay(delay, TimeUnit.MILLISECONDS)
        .addTag("daily_notification")
        .build()

    workManager.enqueueUniqueWork("daily_notification", ExistingWorkPolicy.KEEP, request)
}

fun rescheduleNotification(context: Context, hour: Int, minute: Int) {
    val now = LocalDateTime.now()
    val todayAtTime = now.toLocalDate().atTime(hour, minute)
    val next = if (now.isBefore(todayAtTime)) todayAtTime else todayAtTime.plusDays(1)
    val delay = java.time.Duration.between(now, next).toMillis()

    val request = OneTimeWorkRequestBuilder<NotificationWorker>()
        .setInitialDelay(delay, TimeUnit.MILLISECONDS)
        .addTag("daily_notification")
        .build()

    WorkManager.getInstance(context).enqueueUniqueWork(
        "daily_notification", ExistingWorkPolicy.REPLACE, request
    )
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            scheduleDailyNotification(context)
        }
    }
}

@Composable
fun CibusApp(vm: BudgetViewModel = viewModel()) {
    val context = LocalContext.current
    val remaining by vm.remainingBalance.collectAsState()
    val todaySpent by vm.todaySpent.collectAsState()
    val minimum by vm.minimumToSpendToday.collectAsState()
    val allSpends by vm.allSpends.collectAsState()
    val workingDaysLeft = vm.countWorkingDaysLeft()

    var showAddDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var amountInput by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }

    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 24.dp)
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Balance this month", style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "₪%.0f".format(remaining),
                            fontSize = 52.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "$workingDaysLeft working days left",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Today's minimum", style = MaterialTheme.typography.labelMedium)
                            Text(
                                "₪%.0f".format(minimum),
                                fontSize = 32.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (minimum >= 200.0)
                                    MaterialTheme.colorScheme.error
                                else
                                    MaterialTheme.colorScheme.primary
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("Spent today", style = MaterialTheme.typography.labelMedium)
                            Text(
                                "₪%.0f".format(todaySpent),
                                fontSize = 32.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { showAddDialog = true },
                        modifier = Modifier.weight(1f).height(52.dp)
                    ) {
                        Text("+ Add spend", fontSize = 16.sp)
                    }
                    OutlinedButton(
                        onClick = { showSettingsDialog = true },
                        modifier = Modifier.height(52.dp)
                    ) {
                        Text("Settings")
                    }
                }
            }

            if (allSpends.isEmpty()) {
                item {
                    Text(
                        "No spends logged yet",
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                item {
                    Text(
                        "History",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                items(allSpends, key = { it.id }) { spend ->
                    SpendRow(spend = spend, onDelete = { vm.deleteSpend(spend) })
                }
            }
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = {
                showAddDialog = false
                amountInput = ""
                errorMessage = ""
            },
            title = { Text("Add spend") },
            text = {
                Column {
                    if (errorMessage.isNotEmpty()) {
                        Text(
                            errorMessage,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                    OutlinedTextField(
                        value = amountInput,
                        onValueChange = { amountInput = it },
                        label = { Text("Amount (₪)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val amount = amountInput.toDoubleOrNull()
                    if (amount != null && amount > 0) {
                        val error = vm.addSpend(amount)
                        if (error == null) {
                            showAddDialog = false
                            amountInput = ""
                            errorMessage = ""
                        } else {
                            errorMessage = error
                        }
                    }
                }) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showAddDialog = false
                    amountInput = ""
                    errorMessage = ""
                }) { Text("Cancel") }
            }
        )
    }

    if (showSettingsDialog) {
        SettingsDialog(
            vm = vm,
            onDismiss = { showSettingsDialog = false },
            onReschedule = { hour, minute -> rescheduleNotification(context, hour, minute) },
            onForceNotification = {
                val rem = vm.remainingBalance.value
                val min = vm.minimumToSpendToday.value
                ForceNotificationHelper.show(context, min, rem)
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDialog(
    vm: BudgetViewModel,
    onDismiss: () -> Unit,
    onReschedule: (Int, Int) -> Unit,
    onForceNotification: () -> Unit
) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("cibus_prefs", Context.MODE_PRIVATE)

    val monthStartDay by vm.monthStartDay.collectAsState()
    val monthlyBudget by vm.monthlyBudget.collectAsState()
    val notificationHour by vm.notificationHour.collectAsState()
    val notificationMinute by vm.notificationMinute.collectAsState()
    val workingDays by vm.workingDays.collectAsState()

    var dayInput by remember { mutableStateOf(monthStartDay.toString()) }
    var budgetInput by remember { mutableStateOf(monthlyBudget.toInt().toString()) }
    var dailyLimitInput by remember { mutableStateOf(vm.dailyLimit.value.toInt().toString()) }
    var hourInput by remember { mutableStateOf(notificationHour.toString()) }
    var minuteInput by remember { mutableStateOf(notificationMinute.toString().padStart(2, '0')) }
    var selectedDays by remember { mutableStateOf(workingDays) }
    var showResetConfirm by remember { mutableStateOf(false) }
    var savedMessage by remember { mutableStateOf("") }

    var gmailAccount by remember {
        mutableStateOf(prefs.getString("gmail_account", null))
    }

    val signInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            val accountName = data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
                ?: GoogleSignIn.getLastSignedInAccount(context)?.email
            if (accountName != null) {
                prefs.edit().putString("gmail_account", accountName).apply()
                gmailAccount = accountName
                scheduleGmailPolling(context)
                savedMessage = "Gmail connected: $accountName"
            }
        }
    }

    val allDays = listOf(
        DayOfWeek.SUNDAY to "Sun",
        DayOfWeek.MONDAY to "Mon",
        DayOfWeek.TUESDAY to "Tue",
        DayOfWeek.WEDNESDAY to "Wed",
        DayOfWeek.THURSDAY to "Thu",
        DayOfWeek.FRIDAY to "Fri",
        DayOfWeek.SATURDAY to "Sat"
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Title
            Text(
                "Settings",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            HorizontalDivider()

            // Scrollable content
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {

                // Gmail integration
                Text("Gmail integration", style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold)

                if (gmailAccount != null) {
                    Text(
                        "Connected: $gmailAccount",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                WorkManager.getInstance(context).enqueue(
                                    OneTimeWorkRequestBuilder<GmailPollingWorker>().build()
                                )
                                savedMessage = "Polling Gmail now..."
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Poll now", fontSize = 12.sp)
                        }
                        OutlinedButton(
                            onClick = {
                                prefs.edit()
                                    .remove("gmail_account")
                                    .remove("last_email_id")
                                    .apply()
                                gmailAccount = null
                                cancelGmailPolling(context)
                                val gso = GoogleSignInOptions.Builder(
                                    GoogleSignInOptions.DEFAULT_SIGN_IN).build()
                                GoogleSignIn.getClient(context, gso).signOut()
                                savedMessage = "Gmail disconnected"
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text("Disconnect", fontSize = 12.sp)
                        }
                    }
                } else {
                    Button(
                        onClick = {
                            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                                .requestEmail()
                                .requestScopes(Scope(GmailScopes.GMAIL_READONLY))
                                .requestServerAuthCode(
                                    "582879497056-uiu4iljpi4sf8qbudn5qgok3fununmcm.apps.googleusercontent.com"
                                )
                                .build()
                            val signInIntent = GoogleSignIn.getClient(context, gso).signInIntent
                            signInLauncher.launch(signInIntent)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Connect Gmail account")
                    }
                }

                HorizontalDivider()

                // Budget settings
                Text("Budget", style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold)

                OutlinedTextField(
                    value = budgetInput,
                    onValueChange = { budgetInput = it },
                    label = { Text("Monthly budget (₪)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = dailyLimitInput,
                    onValueChange = { dailyLimitInput = it },
                    label = { Text("Daily spending limit (₪)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = dayInput,
                    onValueChange = { dayInput = it },
                    label = { Text("Month start day (1–31)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                HorizontalDivider()

                // Working days
                Text("Working days", style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    allDays.forEach { (day, label) ->
                        val selected = day in selectedDays
                        FilterChip(
                            selected = selected,
                            onClick = {
                                selectedDays = if (selected)
                                    selectedDays - day
                                else
                                    selectedDays + day
                            },
                            label = { Text(label, fontSize = 11.sp) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                HorizontalDivider()

                // Notification time
                Text("Notification time", style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold)

                Text(
                    "%02d:%02d".format(
                        hourInput.toIntOrNull() ?: 9,
                        minuteInput.toIntOrNull() ?: 0
                    ),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )

                Text("Hour", style = MaterialTheme.typography.bodySmall)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Slider(
                        value = (hourInput.toIntOrNull() ?: 9).toFloat(),
                        onValueChange = { hourInput = it.toInt().toString() },
                        valueRange = 0f..23f,
                        steps = 22,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = hourInput,
                        onValueChange = { v ->
                            val n = v.toIntOrNull()
                            if (v.isEmpty() || (n != null && n in 0..23)) hourInput = v
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.width(64.dp)
                    )
                }

                Text("Minute", style = MaterialTheme.typography.bodySmall)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Slider(
                        value = (minuteInput.toIntOrNull() ?: 0).toFloat(),
                        onValueChange = {
                            minuteInput = it.toInt().toString().padStart(2, '0')
                        },
                        valueRange = 0f..59f,
                        steps = 58,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = minuteInput,
                        onValueChange = { v ->
                            val n = v.toIntOrNull()
                            if (v.isEmpty() || (n != null && n in 0..59)) minuteInput = v
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.width(64.dp)
                    )
                }

                HorizontalDivider()

                // Test & reset
                Text("Tools", style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold)

                OutlinedButton(
                    onClick = onForceNotification,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Send test notification now")
                }

                OutlinedButton(
                    onClick = { showResetConfirm = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Reset all spending")
                }

                if (savedMessage.isNotEmpty()) {
                    Text(
                        savedMessage,
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                }

                // Bottom padding so last item isn't hidden behind buttons
                Spacer(Modifier.height(8.dp))
            }

            // Always-visible bottom bar
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Cancel")
                }
                Button(
                    onClick = {
                        val day = dayInput.toIntOrNull()?.coerceIn(1, 31)
                        val budget = budgetInput.toDoubleOrNull()
                        val dailyLimit = dailyLimitInput.toDoubleOrNull()
                        val hour = hourInput.toIntOrNull()?.coerceIn(0, 23)
                        val minute = minuteInput.toIntOrNull()?.coerceIn(0, 59)
                        if (day != null) vm.saveMonthStartDay(day)
                        if (budget != null) vm.saveMonthlyBudget(budget)
                        if (dailyLimit != null && dailyLimit > 0) vm.saveDailyLimit(dailyLimit)
                        if (hour != null && minute != null) {
                            vm.saveNotificationTime(hour, minute)
                            onReschedule(hour, minute)
                        }
                        if (selectedDays.isNotEmpty()) vm.saveWorkingDays(selectedDays)
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Save & Close")
                }
            }
        }
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("Reset all spending?") },
            text = { Text("This will delete all spend history. Cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.resetAllSpending()
                    showResetConfirm = false
                }) {
                    Text("Reset", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
fun SpendRow(spend: Spend, onDelete: () -> Unit) {
    val formatter = remember { SimpleDateFormat("dd/MM  HH:mm", Locale.getDefault()) }
    var showConfirm by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    formatter.format(Date(spend.timestamp)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (spend.businessName != null) {
                    Text(
                        spend.businessName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            Text(
                "₪%.0f".format(spend.amount),
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp
            )
            TextButton(onClick = { showConfirm = true }) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("Delete spend?") },
            text = { Text("Remove ₪%.0f from history?".format(spend.amount)) },
            confirmButton = {
                TextButton(onClick = { onDelete(); showConfirm = false }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text("Cancel") }
            }
        )
    }
}