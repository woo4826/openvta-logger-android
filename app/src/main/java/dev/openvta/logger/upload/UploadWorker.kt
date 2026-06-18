package dev.openvta.logger.upload

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.openvta.logger.OpenVtaLoggerApp
import dev.openvta.logger.domain.UploadState

class UploadWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        val sessionId = inputData.getString(KEY_SESSION_ID) ?: return Result.failure()
        val app = applicationContext as OpenVtaLoggerApp
        val repository = app.container.recordingRepository
        val session = repository.loadSession(sessionId) ?: return Result.failure()
        val settings = app.container.settingsRepository.load()

        repository.updateUploadState(sessionId, UploadState.Uploading)
        app.container.updateStatus { it.copy(lastMessage = "Uploading ${session.zipFile.name}") }

        return try {
            if (!session.zipFile.isFile) {
                repository.zipSession(session)
            }
            FtpUploadClient().upload(settings, session.zipFile)
            if (!settings.keepLocalFiles) {
                session.vtaFile.delete()
                session.zipFile.delete()
            }
            repository.updateUploadState(sessionId, UploadState.Uploaded)
            app.container.updateStatus { it.copy(lastMessage = "Upload complete: ${session.zipFile.name}") }
            Result.success()
        } catch (exception: Exception) {
            repository.updateUploadState(sessionId, UploadState.Failed, exception.message)
            app.container.updateStatus { it.copy(lastMessage = "Upload failed: ${exception.message}") }
            if (exception.isPermanentUploadFailure()) {
                Result.failure()
            } else {
                Result.retry()
            }
        }
    }

    companion object {
        private const val KEY_SESSION_ID = "session_id"

        fun enqueue(context: Context, sessionId: String) {
            val request = OneTimeWorkRequestBuilder<UploadWorker>()
                .setInputData(workDataOf(KEY_SESSION_ID to sessionId))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "upload-$sessionId",
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}

private fun Exception.isPermanentUploadFailure(): Boolean {
    val message = message.orEmpty()
    return this is IllegalArgumentException ||
        message.startsWith("FTP host is not configured") ||
        message.startsWith("FTP user is not configured") ||
        message.startsWith("FTP login failed") ||
        message.startsWith("FTP binary mode failed")
}
