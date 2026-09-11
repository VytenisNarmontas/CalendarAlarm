package com.calendaralarm.background

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.calendaralarm.AppContainer
import kotlinx.coroutines.CancellationException

open class ReconcileWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = try {
        AppContainer.reconciler.reconcile("Periodic background check")
        WorkScheduler.ensureContentObserver(applicationContext)
        Result.success()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) { Result.retry() }
}

class CalendarContentWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        return try {
            AppContainer.reconciler.reconcile("Background calendar change")
            WorkScheduler.appendContentObserver(applicationContext)
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) { Result.retry() }
    }
}
