package com.volleyballlab.player.data.session

import android.content.Context
import androidx.work.*
import com.volleyballlab.player.VolleyballLabApplication
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/** Persistent transfers retry after lost connectivity or process death. */
class RecordingRestoreWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val uid = inputData.getString("uid") ?: return Result.failure()
        val id = inputData.getString("sessionId") ?: return Result.failure()
        val app = (applicationContext as VolleyballLabApplication).container
        if (app.authRepository.currentUid != uid) return Result.success()
        return try {
            val session = app.sessionRepository.byId(id) ?: return Result.success()
            if (session.videoPath?.let { File(it).isFile } == true) return Result.success()
            val path = app.sharedRecordingStore.download(session, uid) ?: return Result.success()
            val repository = app.sessionRepository as DataStoreSessionRepository
            repository.attachRestoredVideo(uid, id, path)
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        fun schedule(context: Context, uid: String, sessionId: String) {
            val request = OneTimeWorkRequestBuilder<RecordingRestoreWorker>()
                .setInputData(workDataOf("uid" to uid, "sessionId" to sessionId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "recording-restore-$uid-$sessionId", ExistingWorkPolicy.KEEP, request,
            )
        }
    }
}
