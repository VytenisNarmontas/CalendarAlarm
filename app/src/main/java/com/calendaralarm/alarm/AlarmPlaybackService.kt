package com.calendaralarm.alarm

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.calendaralarm.core.AlarmRecord

class AlarmPlaybackService : Service() {
    private val activeRecords = linkedMapOf<String, AlarmRecord>()
    private var mediaPlayer: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val closeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_CLOSE_ONE -> intent.getStringExtra(EXTRA_ALARM_KEY)?.let(::removeAlarm)
                ACTION_CLOSE_ALL -> stopEverything()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        ContextCompat.registerReceiver(
            this,
            closeReceiver,
            IntentFilter().apply {
                addAction(ACTION_CLOSE_ONE)
                addAction(ACTION_CLOSE_ALL)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val key = intent?.getStringExtra(EXTRA_ALARM_KEY) ?: return START_NOT_STICKY
        val record = AlarmRecord(
            key = key,
            eventId = intent.getLongExtra(EXTRA_EVENT_ID, -1L),
            occurrenceStartMillis = intent.getLongExtra(EXTRA_START, 0L),
            offsetMinutes = intent.getIntExtra(EXTRA_OFFSET, 0),
            triggerAtMillis = intent.getLongExtra(EXTRA_TRIGGER, 0L),
            eventTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "Calendar event" },
        )
        activeRecords[key] = record
        val notification = AlarmNotificationFactory.build(this, record)
        val notificationId = AlarmNotificationFactory.notificationId(key)
        if (activeRecords.size == 1) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    notificationId,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                )
            } else {
                startForeground(notificationId, notification)
            }
            acquireWakeLock()
            startSoundAndVibration()
        } else {
            getSystemService(NotificationManager::class.java).notify(notificationId, notification)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        unregisterReceiver(closeReceiver)
        releaseAlertResources()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun removeAlarm(key: String) {
        if (activeRecords.remove(key) == null) return
        getSystemService(NotificationManager::class.java)
            .cancel(AlarmNotificationFactory.notificationId(key))
        if (activeRecords.isEmpty()) {
            stopEverything()
        } else {
            val replacement = activeRecords.values.first()
            startForeground(
                AlarmNotificationFactory.notificationId(replacement.key),
                AlarmNotificationFactory.build(this, replacement),
            )
        }
    }

    private fun stopEverything() {
        activeRecords.keys.forEach { key ->
            getSystemService(NotificationManager::class.java)
                .cancel(AlarmNotificationFactory.notificationId(key))
        }
        activeRecords.clear()
        releaseAlertResources()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:alarm")
            .apply { acquire() }
    }

    private fun startSoundAndVibration() {
        if (mediaPlayer == null) {
            mediaPlayer = runCatching {
                MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                    isLooping = true
                    setDataSource(
                        this@AlarmPlaybackService,
                        Settings.System.DEFAULT_ALARM_ALERT_URI
                            ?: Settings.System.DEFAULT_NOTIFICATION_URI,
                    )
                    prepare()
                    start()
                }
            }.getOrNull()
        }

        val effect = VibrationEffect.createWaveform(longArrayOf(0, 700, 500), 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java).defaultVibrator.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            (getSystemService(VIBRATOR_SERVICE) as Vibrator).vibrate(effect)
        }
    }

    private fun releaseAlertResources() {
        mediaPlayer?.runCatching { stop() }
        mediaPlayer?.release()
        mediaPlayer = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java).cancel()
        } else {
            @Suppress("DEPRECATION")
            (getSystemService(VIBRATOR_SERVICE) as Vibrator).cancel()
        }
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    companion object {
        private const val ACTION_CLOSE_ONE = "com.calendaralarm.action.CLOSE_ONE"
        private const val ACTION_CLOSE_ALL = "com.calendaralarm.action.CLOSE_ALL"
        private const val EXTRA_ALARM_KEY = "alarm_key"
        private const val EXTRA_EVENT_ID = "event_id"
        private const val EXTRA_START = "event_start"
        private const val EXTRA_OFFSET = "offset"
        private const val EXTRA_TRIGGER = "trigger"
        private const val EXTRA_TITLE = "title"

        fun start(context: Context, record: AlarmRecord) {
            val intent = Intent(context, AlarmPlaybackService::class.java).apply {
                putExtra(EXTRA_ALARM_KEY, record.key)
                putExtra(EXTRA_EVENT_ID, record.eventId)
                putExtra(EXTRA_START, record.occurrenceStartMillis)
                putExtra(EXTRA_OFFSET, record.offsetMinutes)
                putExtra(EXTRA_TRIGGER, record.triggerAtMillis)
                putExtra(EXTRA_TITLE, record.eventTitle)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun close(context: Context, key: String) {
            context.sendBroadcast(
                Intent(ACTION_CLOSE_ONE).setPackage(context.packageName)
                    .putExtra(EXTRA_ALARM_KEY, key),
            )
        }

        fun stopAll(context: Context) {
            context.sendBroadcast(Intent(ACTION_CLOSE_ALL).setPackage(context.packageName))
            context.stopService(Intent(context, AlarmPlaybackService::class.java))
        }
    }
}
