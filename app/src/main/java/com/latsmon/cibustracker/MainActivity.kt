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
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import java.time.ZoneId
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CibusApp(vm: BudgetViewModel = viewModel()) {
    val context = LocalContext.current
    val remaining by vm.remainingBalance.collectAsState()
    val todaySpent by vm.todaySpent.collectAsState()
    val minimum by vm.minimumToSpendToday.collectAsState()
    val availableMonths by vm.availableMonths.collectAsState()
    val workingDaysLeft = vm.countWorkingDaysLeft()
    val dailyLimit by vm.dailyLimit.collectAsState()

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    var selectedMonth by remember { mutableStateOf<MonthPeriod?>(null) }

    val currentMonthStart = vm.getMonthStart()
    val currentMonthEnd = remember(currentMonthStart) {
        val startDay = java.time.Instant.ofEpochMilli(currentMonthStart)
            .atZone(ZoneId.systemDefault()).toLocalDate()
        startDay.plusMonths(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    val viewStart = selectedMonth?.start ?: currentMonthStart
    val viewEnd = selectedMonth?.end ?: currentMonthEnd
    val viewLabel = selectedMonth?.label ?: "This month"

    val visibleSpends by vm.getSpendsBetween(viewStart, viewEnd).collectAsState(initial = emptyList())

    var showAddDialog by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var showGmailSheet by remember { mutableStateOf(false) }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                DrawerContent(
                    months = availableMonths,
                    selectedMonth = selectedMonth,
                    onSettingsClick = {
                        showSettingsSheet = true
                        scope.launch { drawerState.close() }
                    },
                    onGmailClick = {
                        showGmailSheet = true
                        scope.launch { drawerState.close() }
                    },
                    onMonthClick = { month ->
                        selectedMonth = month
                        scope.launch { drawerState.close() }
                    },
                    onCurrentMonthClick = {
                        selectedMonth = null
                        scope.launch { drawerState.close() }
                    }
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(viewLabel) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Menu")
                        }
                    }
                )
            },
            floatingActionButton = {
                FloatingActionButton(
                    onClick = { showAddDialog = true },
                    shape = CircleShape
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add spend")
                }
            }
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 24.dp)
            ) {
                if (selectedMonth == null) {
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
                                Text("Balance this month",
                                    style = MaterialTheme.typography.labelLarge)
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
                                    Text("Today's minimum",
                                        style = MaterialTheme.typography.labelMedium)
                                    Text(
                                        "₪%.0f".format(minimum),
                                        fontSize = 32.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (dailyLimit != null && minimum >= dailyLimit!!)
                                            MaterialTheme.colorScheme.error
                                        else
                                            MaterialTheme.colorScheme.primary
                                    )
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text("Spent today",
                                        style = MaterialTheme.typography.labelMedium)
                                    Text(
                                        "₪%.0f".format(todaySpent),
                                        fontSize = 32.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                } else {
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
                                Text("Total spent",
                                    style = MaterialTheme.typography.labelLarge)
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "₪%.0f".format(visibleSpends.sumOf { it.amount }),
                                    fontSize = 52.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }
                }

                if (visibleSpends.isEmpty()) {
                    item {
                        Text(
                            "No spends logged yet",
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 16.dp),
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    item {
                        Text(
                            "Spends",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    items(visibleSpends, key = { it.id }) { spend ->
                        SwipeToDeleteRow(
                            spend = spend,
                            onDelete = { vm.deleteSpend(spend) }
                        )
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddSpendDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { amount, timestamp ->
                if (selectedMonth == null) {
                    vm.addSpend(amount, timestamp)
                } else {
                    vm.addSpendToMonth(amount, timestamp)
                }
                showAddDialog = false
            }
        )
    }

    if (showSettingsSheet) {
        SettingsDialog(
            vm = vm,
            onDismiss = { showSettingsSheet = false },
            onReschedule = { hour, minute -> rescheduleNotification(context, hour, minute) },
            onForceNotification = {
                val rem = vm.remainingBalance.value
                val min = vm.minimumToSpendToday.value
                ForceNotificationHelper.show(context, min, rem)
            }
        )
    }

    if (showGmailSheet) {
        GmailSheet(
            onDismiss = { showGmailSheet = false },
            onDisconnect = { vm.resetAllSpending() },
            onResetAndRepoll = { vm.resetAllSpending() }
        )
    }
}

@Composable
fun DrawerContent(
    months: List<MonthPeriod>,
    selectedMonth: MonthPeriod?,
    onSettingsClick: () -> Unit,
    onGmailClick: () -> Unit,
    onMonthClick: (MonthPeriod) -> Unit,
    onCurrentMonthClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            "Cibus Tracker",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        HorizontalDivider()
        Spacer(Modifier.height(8.dp))

        NavigationDrawerItem(
            label = { Text("Gmail integration") },
            selected = false,
            onClick = onGmailClick
        )

        NavigationDrawerItem(
            label = { Text("Settings") },
            selected = false,
            onClick = onSettingsClick
        )

        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))

        Text(
            "Months",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(Modifier.height(4.dp))

        NavigationDrawerItem(
            label = { Text("Current month") },
            selected = selectedMonth == null,
            onClick = onCurrentMonthClick
        )

        months.drop(1).forEach { month ->
            NavigationDrawerItem(
                label = { Text(month.label) },
                selected = selectedMonth?.start == month.start,
                onClick = { onMonthClick(month) }
            )
        }
    }
}

@Composable
fun SwipeToDeleteRow(
    spend: Spend,
    onDelete: () -> Unit
) {
    val formatter = remember { SimpleDateFormat("dd/MM  HH:mm", Locale.getDefault()) }
    var revealed by remember { mutableStateOf(false) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    val maxOffset = -180f
    val revealThreshold = -90f

    val backgroundColor by animateColorAsState(
        targetValue = if (revealed || offsetX < revealThreshold)
            MaterialTheme.colorScheme.errorContainer
        else
            MaterialTheme.colorScheme.surfaceVariant,
        label = "bg"
    )

    var showConfirm by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(backgroundColor, shape = MaterialTheme.shapes.medium),
            contentAlignment = Alignment.CenterEnd
        ) {
            IconButton(
                onClick = { showConfirm = true },
                modifier = Modifier.padding(end = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset((if (revealed) maxOffset else offsetX).roundToInt(), 0) }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (!revealed) {
                                if (offsetX < revealThreshold) {
                                    revealed = true
                                } else {
                                    offsetX = 0f
                                }
                            }
                        },
                        onDragCancel = {
                            if (!revealed) {
                                offsetX = 0f
                            }
                        },
                        onHorizontalDrag = { _, dragAmount ->
                            if (!revealed) {
                                offsetX = (offsetX + dragAmount).coerceIn(maxOffset, 0f)
                            } else if (dragAmount > 0) {
                                revealed = false
                                offsetX = 0f
                            }
                        }
                    )
                },
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
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Text(
                    "₪%.0f".format(spend.amount),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp
                )
            }
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = {
                showConfirm = false
                revealed = false
                offsetX = 0f
            },
            title = { Text("Delete spend?") },
            text = { Text("Remove ₪%.0f from history?".format(spend.amount)) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete()
                    showConfirm = false
                    revealed = false
                    offsetX = 0f
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showConfirm = false
                    revealed = false
                    offsetX = 0f
                }) { Text("Cancel") }
            }
        )
    }
}

@Composable
fun AddSpendDialog(
    onDismiss: () -> Unit,
    onConfirm: (Double, Long) -> Unit
) {
    val now = remember { Calendar.getInstance() }
    var errorMessage by remember { mutableStateOf("") }
    var amountInput by remember { mutableStateOf("") }
    var dayInput by remember { mutableStateOf(now.get(Calendar.DAY_OF_MONTH)) }
    var monthInput by remember { mutableStateOf(now.get(Calendar.MONTH) + 1) }
    var yearInput by remember { mutableStateOf(now.get(Calendar.YEAR)) }
    var hourInput by remember { mutableStateOf(now.get(Calendar.HOUR_OF_DAY)) }
    var minuteInput by remember { mutableStateOf(now.get(Calendar.MINUTE)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add spend") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (errorMessage.isNotEmpty()) {
                    Text(
                        errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                OutlinedTextField(
                    value = amountInput,
                    onValueChange = { amountInput = it },
                    label = { Text("Amount (₪)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                HorizontalDivider()

                Text("Date", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    NumberPicker(
                        value = dayInput,
                        range = 1..31,
                        label = { "%02d".format(it) },
                        onValueChange = { dayInput = it }
                    )
                    Text("/", fontSize = 24.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 4.dp))
                    NumberPicker(
                        value = monthInput,
                        range = 1..12,
                        label = { "%02d".format(it) },
                        onValueChange = { monthInput = it }
                    )
                    Text("/", fontSize = 24.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 4.dp))
                    NumberPicker(
                        value = yearInput,
                        range = 2020..2035,
                        label = { it.toString() },
                        onValueChange = { yearInput = it }
                    )
                }

                HorizontalDivider()

                Text("Time", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    NumberPicker(
                        value = hourInput,
                        range = 0..23,
                        label = { "%02d".format(it) },
                        onValueChange = { hourInput = it }
                    )
                    Text(":", fontSize = 28.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp))
                    NumberPicker(
                        value = minuteInput,
                        range = 0..59,
                        label = { "%02d".format(it) },
                        onValueChange = { minuteInput = it }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val amount = amountInput.toDoubleOrNull()
                if (amount == null || amount <= 0) {
                    errorMessage = "Enter a valid amount"
                    return@TextButton
                }
                if (dayInput !in 1..31) { errorMessage = "Invalid day"; return@TextButton }
                if (monthInput !in 1..12) { errorMessage = "Invalid month"; return@TextButton }
                if (hourInput !in 0..23) { errorMessage = "Invalid hour"; return@TextButton }
                if (minuteInput !in 0..59) { errorMessage = "Invalid minute"; return@TextButton }

                val cal = Calendar.getInstance().apply {
                    set(yearInput, monthInput - 1, dayInput, hourInput, minuteInput, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                if (cal.timeInMillis > System.currentTimeMillis()) {
                    errorMessage = "Cannot add a spend in the future"
                    return@TextButton
                }
                onConfirm(amount, cal.timeInMillis)
            }) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GmailSheet(
    onDismiss: () -> Unit,
    onDisconnect: () -> Unit = {},
    onResetAndRepoll: () -> Unit = {}
) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("cibus_prefs", Context.MODE_PRIVATE)
    var gmailAccount by remember { mutableStateOf(prefs.getString("gmail_account", null)) }
    var message by remember { mutableStateOf("") }

    val signInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val accountName = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
                ?: GoogleSignIn.getLastSignedInAccount(context)?.email
            if (accountName != null) {
                prefs.edit().putString("gmail_account", accountName).apply()
                gmailAccount = accountName
                scheduleGmailPolling(context)
                message = "Connected: $accountName"
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Gmail integration",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            HorizontalDivider()

            if (gmailAccount != null) {
                Text(
                    "Connected: $gmailAccount",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            WorkManager.getInstance(context).enqueue(
                                OneTimeWorkRequestBuilder<GmailPollingWorker>().build()
                            )
                            message = "Polling Gmail now..."
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Poll now") }

                    OutlinedButton(
                        onClick = {
                            onResetAndRepoll()
                            prefs.edit().remove("last_email_id").apply()
                            WorkManager.getInstance(context).enqueue(
                                OneTimeWorkRequestBuilder<GmailPollingWorker>().build()
                            )
                            message = "Re-polling full history..."
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Re-poll history") }
                }

                OutlinedButton(
                    onClick = {
                        prefs.edit().remove("gmail_account").remove("last_email_id").apply()
                        gmailAccount = null
                        cancelGmailPolling(context)
                        val gso = GoogleSignInOptions.Builder(
                            GoogleSignInOptions.DEFAULT_SIGN_IN).build()
                        GoogleSignIn.getClient(context, gso).signOut()
                        message = "Disconnected"
                        onDisconnect()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("Disconnect") }
            } else {
                Text(
                    "Connect your Gmail account to automatically track Cibus receipts from Pluxee.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Button(
                    onClick = {
                        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                            .requestEmail()
                            .requestScopes(Scope(GmailScopes.GMAIL_READONLY))
                            .requestServerAuthCode(
                                "582879497056-uiu4iljpi4sf8qbudn5qgok3fununmcm.apps.googleusercontent.com"
                            )
                            .build()
                        signInLauncher.launch(GoogleSignIn.getClient(context, gso).signInIntent)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Connect Gmail account") }
            }

            if (message.isNotEmpty()) {
                Text(
                    message,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun NumberPicker(
    value: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit,
    label: (Int) -> String = { it.toString() }
) {
    val itemHeight = 48.dp
    val itemHeightPx = with(LocalDensity.current) { itemHeight.toPx() }
    val visibleItems = 3
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = (value - range.first)
            .coerceIn(0, range.count() - 1)
    )

    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress) {
            val index = listState.firstVisibleItemIndex
            val offset = listState.firstVisibleItemScrollOffset
            val snappedIndex = if (offset > itemHeightPx / 2) index + 1 else index
            val clamped = snappedIndex.coerceIn(0, range.count() - 1)
            listState.animateScrollToItem(clamped)
            onValueChange(range.first + clamped)
        }
    }

    Box(
        modifier = Modifier
            .width(72.dp)
            .height(itemHeight * visibleItems)
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .height(itemHeight)
                .background(
                    MaterialTheme.colorScheme.primaryContainer,
                    shape = MaterialTheme.shapes.small
                )
        )

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = itemHeight)
        ) {
            items(range.count()) { index ->
                val itemValue = range.first + index
                val isSelected = itemValue == (range.first + listState.firstVisibleItemIndex)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(itemHeight),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label(itemValue),
                        fontSize = if (isSelected) 22.sp else 16.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSelected)
                            MaterialTheme.colorScheme.onPrimaryContainer
                        else
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsDialog(
    vm: BudgetViewModel,
    onDismiss: () -> Unit,
    onReschedule: (Int, Int) -> Unit,
    onForceNotification: () -> Unit
) {
    val monthStartDay by vm.monthStartDay.collectAsState()
    val monthlyBudget by vm.monthlyBudget.collectAsState()
    val notificationHour by vm.notificationHour.collectAsState()
    val notificationMinute by vm.notificationMinute.collectAsState()
    val workingDays by vm.workingDays.collectAsState()

    var dayInput by remember { mutableStateOf(monthStartDay.toString()) }
    var budgetInput by remember { mutableStateOf(monthlyBudget.toInt().toString()) }
    var dailyLimitInput by remember { mutableStateOf(vm.dailyLimit.value?.toInt()?.toString() ?: "") }
    var hourInput by remember { mutableStateOf(notificationHour.toString()) }
    var minuteInput by remember { mutableStateOf(notificationMinute.toString().padStart(2, '0')) }
    var selectedDays by remember { mutableStateOf(workingDays) }
    var showResetConfirm by remember { mutableStateOf(false) }
    var savedMessage by remember { mutableStateOf("") }
    var showTimePicker by remember { mutableStateOf(false) }

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
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                "Settings",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            HorizontalDivider()

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
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
                    label = { Text("Daily spending limit (₪) — leave blank for no limit") },
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

                Text("Working days", style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold)

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
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
                            label = { Text(label) }
                        )
                    }
                }

                HorizontalDivider()

                Text("Notification time", style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "%02d:%02d".format(
                            hourInput.toIntOrNull() ?: 9,
                            minuteInput.toIntOrNull() ?: 0
                        ),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    OutlinedButton(onClick = { showTimePicker = true }) {
                        Text("Change")
                    }
                }

                HorizontalDivider()

                Text("Tools", style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold)

                OutlinedButton(
                    onClick = onForceNotification,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Send test notification now") }

                OutlinedButton(
                    onClick = { showResetConfirm = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("Reset all spending") }

                if (savedMessage.isNotEmpty()) {
                    Text(
                        savedMessage,
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(Modifier.height(8.dp))
            }

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
                ) { Text("Cancel") }
                Button(
                    onClick = {
                        val day = dayInput.toIntOrNull()?.coerceIn(1, 31)
                        val budget = budgetInput.toDoubleOrNull()
                        val dailyLimit = if (dailyLimitInput.isBlank()) null
                        else dailyLimitInput.toDoubleOrNull()?.takeIf { it > 0.0 }
                        val hour = hourInput.toIntOrNull()?.coerceIn(0, 23)
                        val minute = minuteInput.toIntOrNull()?.coerceIn(0, 59)
                        if (day != null) vm.saveMonthStartDay(day)
                        if (budget != null) vm.saveMonthlyBudget(budget)
                        vm.saveDailyLimit(dailyLimit)
                        if (hour != null && minute != null) {
                            vm.saveNotificationTime(hour, minute)
                            onReschedule(hour, minute)
                        }
                        if (selectedDays.isNotEmpty()) vm.saveWorkingDays(selectedDays)
                        savedMessage = "Settings saved!"
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Save & Close") }
            }
        }
    }

    if (showTimePicker) {
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("Set notification time") },
            text = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    NumberPicker(
                        value = hourInput.toIntOrNull() ?: 9,
                        range = 0..23,
                        label = { "%02d".format(it) },
                        onValueChange = { hourInput = it.toString() }
                    )
                    Text(
                        ":",
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                    NumberPicker(
                        value = minuteInput.toIntOrNull() ?: 0,
                        range = 0..59,
                        label = { "%02d".format(it) },
                        onValueChange = { minuteInput = it.toString().padStart(2, '0') }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showTimePicker = false }) { Text("Done") }
            }
        )
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
                }) { Text("Reset", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) { Text("Cancel") }
            }
        )
    }
}