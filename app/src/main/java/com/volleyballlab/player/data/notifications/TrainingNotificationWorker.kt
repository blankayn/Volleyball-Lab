package com.volleyballlab.player.data.notifications

import android.content.Context
import androidx.work.*
import com.volleyballlab.player.VolleyballLabApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import java.util.concurrent.TimeUnit

class TrainingNotificationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        withTimeout(25_000) { (applicationContext as VolleyballLabApplication).container.notificationRepository.refresh() }
        Result.success()
    } catch (failure: Exception) {
        if (failure is CancellationException && failure !is kotlinx.coroutines.TimeoutCancellationException) throw failure
        Result.retry()
    }
    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<TrainingNotificationWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("training-notification-sync", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
