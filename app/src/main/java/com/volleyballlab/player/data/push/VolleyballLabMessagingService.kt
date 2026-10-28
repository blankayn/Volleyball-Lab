package com.volleyballlab.player.data.push

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.volleyballlab.player.VolleyballLabApplication
import com.volleyballlab.player.domain.model.NotificationEvents
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout

/** WorkManager keeps processing alive after the short-lived FCM service returns. */
class VolleyballLabMessagingService : FirebaseMessagingService() {
    override fun onRegistered(token: String) {
        WorkManager.getInstance(this).enqueue(
            OneTimeWorkRequestBuilder<PushRegistrationWorker>()
                .setInputData(workDataOf("token" to token)).build(),
        )
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val keys = listOf("uid", "type", "eventId", "title", "body", "createdAt", "referenceId")
        val data = workDataOf(*keys.map { it to message.data[it]?.take(512) }.toTypedArray())
        WorkManager.getInstance(this).enqueue(
            OneTimeWorkRequestBuilder<PushNotificationWorker>().setInputData(data).build(),
        )
    }
}

class PushRegistrationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try {
            val container = (applicationContext as VolleyballLabApplication).container
            val uid = container.authRepository.currentUid ?: return Result.success()
            val token = inputData.getString("token") ?: return Result.failure()
            withTimeout(20_000) { container.pushTokenRegistrar.store(uid, token) }
            Result.success()
        } catch (failure: Exception) {
            if (failure is CancellationException && failure !is kotlinx.coroutines.TimeoutCancellationException) throw failure
            Result.retry()
        }
    }
}

class PushNotificationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as VolleyballLabApplication).container
        val uid = container.authRepository.currentUid ?: return Result.success()
        val data = inputData.keyValueMap.mapNotNull { (key, value) -> (value as? String)?.let { key to it } }.toMap()
        val event = NotificationEvents.push(data, uid) ?: return Result.success()
        container.notificationRepository.ingest(uid, listOf(event))
        return Result.success()
    }
}
