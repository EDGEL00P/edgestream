package com.edgestream.tv.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.edgestream.tv.EdgeStreamApp
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async

/** Keeps playlists and the guide fresh in the background, so the app opens instantly. */
class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = EdgeStreamApp.instance
        val repo = app.repository
        val id = inputData.getLong(KEY_SOURCE, -1)
        // A large provider's catalogue plus guide can outlive WorkManager's normal limit; run as a
        // foreground service so Android does not stop the refresh mid-way.
        runCatching { setForeground(foregroundInfo()) }
        // Run in the app's scope: WorkManager stops jobs after 10 minutes, and a big provider's
        // catalogue plus guide can take longer. If the process dies, the unfinished job reruns.
        app.appScope.async {
            when {
                id < 0 -> repo.refreshAll(includeEpg = true)
                inputData.getBoolean(KEY_EPG_ONLY, false) -> repo.refreshEpg(id)
                inputData.getBoolean(KEY_CHECK, false) -> repo.checkStreams(id)
                else -> repo.refreshSource(id)
            }
        }.await()
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo()

    private fun foregroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Library updates", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Playlist and guide refresh"
                    setShowBadge(false)
                },
            )
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Updating library")
            .setContentText("Refreshing playlists and guide")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val NAME = "edgestream-refresh"
        private const val CHANNEL = "edgestream-refresh"
        private const val NOTIFICATION_ID = 7101
        private const val KEY_SOURCE = "source"
        private const val KEY_EPG_ONLY = "epgOnly"
        private const val KEY_CHECK = "check"

        /**
         * Runs a refresh now as a WorkManager job. If the app is killed part-way (a cleaner app,
         * low memory), WorkManager reruns it the next time EdgeStream starts.
         */
        fun runNow(context: Context, sourceId: Long = -1, epgOnly: Boolean = false, checkStreams: Boolean = false) {
            val request = androidx.work.OneTimeWorkRequestBuilder<RefreshWorker>()
                .setInputData(androidx.work.workDataOf(KEY_SOURCE to sourceId, KEY_EPG_ONLY to epgOnly, KEY_CHECK to checkStreams))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            val kind = when { epgOnly -> "epg"; checkStreams -> "check"; else -> "full" }
            WorkManager.getInstance(context).enqueueUniqueWork("$NAME-$sourceId-$kind", androidx.work.ExistingWorkPolicy.KEEP, request)
        }

        fun schedule(context: Context, hours: Int) {
            val request = PeriodicWorkRequestBuilder<RefreshWorker>(hours.coerceAtLeast(1).toLong(), TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(hours.coerceAtLeast(1).toLong(), TimeUnit.HOURS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
