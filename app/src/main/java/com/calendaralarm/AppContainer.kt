package com.calendaralarm

import android.annotation.SuppressLint
import android.content.Context
import com.calendaralarm.alarm.AndroidAlarmScheduler
import com.calendaralarm.calendar.CalendarProviderSource
import com.calendaralarm.calendar.CalendarReconciler
import com.calendaralarm.core.AlarmRecord
import com.calendaralarm.data.AlarmStore
import com.calendaralarm.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object AppContainer {
    val alarmMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val uiRequests = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private lateinit var appContext: Context

    lateinit var alarmStore: AlarmStore
        private set
    @SuppressLint("StaticFieldLeak")
    lateinit var settingsRepository: SettingsRepository
        private set
    @SuppressLint("StaticFieldLeak")
    lateinit var alarmScheduler: AndroidAlarmScheduler
        private set
    @SuppressLint("StaticFieldLeak")
    lateinit var reconciler: CalendarReconciler
        private set

    private val mutableRecords = MutableStateFlow<List<AlarmRecord>>(emptyList())
    val records: StateFlow<List<AlarmRecord>> = mutableRecords.asStateFlow()

    fun initialize(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        alarmStore = AlarmStore(appContext)
        settingsRepository = SettingsRepository(appContext)
        alarmScheduler = AndroidAlarmScheduler(appContext)
        reconciler = CalendarReconciler(
            context = appContext,
            calendarSource = CalendarProviderSource(appContext),
            store = alarmStore,
            settingsRepository = settingsRepository,
            alarmScheduler = alarmScheduler,
            onRecordsChanged = ::refreshRecords,
            mutex = alarmMutex,
        )
        mutableRecords.value = alarmStore.all()
        scope.launch {
            for (request in uiRequests) {
                try {
                    request()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    reconciler.reportActionError("Could not finish this change. Check the scan result and try again.")
                }
            }
        }
    }

    fun requestScan(reason: String) {
        uiRequests.trySend { reconciler.reconcile(reason); Unit }
    }

    fun updateSetting(update: suspend SettingsRepository.() -> Unit) {
        // FIFO, application lifetime: rapid taps and leaving the screen cannot lose a save.
        uiRequests.trySend {
            alarmMutex.withLock { settingsRepository.update() }
            reconciler.reconcile("Alarm settings changed")
        }
    }

    suspend fun refreshRecords() = withContext(Dispatchers.IO) {
        mutableRecords.value = alarmStore.all()
    }

    fun requireContext(): Context = appContext
}
