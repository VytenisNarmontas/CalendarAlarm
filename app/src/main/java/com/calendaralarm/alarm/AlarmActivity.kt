package com.calendaralarm.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Build
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calendaralarm.AppContainer
import com.calendaralarm.core.AlarmRecord
import com.calendaralarm.core.AlarmState
import com.calendaralarm.ui.CalendarAlarmTheme
import java.text.DateFormat
import java.util.Date

class AlarmActivity : ComponentActivity() {
    private lateinit var alarmKey: String
    private var closeReceiverRegistered = false

    private val closeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getStringExtra(AlarmActionReceiver.EXTRA_ALARM_KEY) == alarmKey) finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        alarmKey = intent.getStringExtra(EXTRA_ALARM_KEY).orEmpty()
        if (alarmKey.isBlank()) {
            finish()
            return
        }
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = Unit
            },
        )
        ContextCompat.registerReceiver(
            this,
            closeReceiver,
            IntentFilter(ACTION_ALARM_CLOSED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        closeReceiverRegistered = true
        setContent {
            CalendarAlarmTheme {
                val records by AppContainer.records.collectAsStateWithLifecycle()
                val record = records.firstOrNull { it.key == alarmKey }
                LaunchedEffect(record) {
                    if (record == null || record.state != AlarmState.RINGING) finish()
                }
                if (record != null) {
                    AlarmScreen(
                        record = record,
                        onSnooze = { performAction(AlarmActionReceiver.ACTION_SNOOZE) },
                        onDismiss = { performAction(AlarmActionReceiver.ACTION_DISMISS) },
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        if (closeReceiverRegistered) unregisterReceiver(closeReceiver)
        super.onDestroy()
    }

    private fun performAction(action: String) {
        sendBroadcast(
            Intent(this, AlarmActionReceiver::class.java).apply {
                this.action = action
                putExtra(AlarmActionReceiver.EXTRA_ALARM_KEY, alarmKey)
            },
        )
        finish()
    }

    companion object {
        const val EXTRA_ALARM_KEY = "alarm_key"
        const val ACTION_ALARM_CLOSED = "com.calendaralarm.action.ALARM_CLOSED"
    }
}

@Composable
private fun AlarmScreen(
    record: AlarmRecord,
    onSnooze: () -> Unit,
    onDismiss: () -> Unit,
) {
    val start = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        .format(Date(record.occurrenceStartMillis))
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("Calendar alarm", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(20.dp))
                Text(
                    record.eventTitle,
                    style = MaterialTheme.typography.headlineLarge,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Event starts $start",
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(48.dp))
                Button(
                    onClick = onSnooze,
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                ) {
                    Text("Snooze 10 minutes", style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(16.dp))
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                ) {
                    Text("Dismiss", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}
