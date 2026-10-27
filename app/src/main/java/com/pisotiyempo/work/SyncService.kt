package com.pisotiyempo.work

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.pisotiyempo.PisoApp
import com.pisotiyempo.data.repo.AlertRepository
import com.pisotiyempo.data.repo.AnalysisRepository
import com.pisotiyempo.data.repo.MANILA
import com.pisotiyempo.data.repo.MarketRepository
import com.pisotiyempo.data.repo.PortfolioRepository
import com.pisotiyempo.data.settings.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * The daily pipeline: latest prices -> PSEi -> history backfill -> scoring -> disclosures and dividends
 * for the user's stocks -> alerts. Runs from WorkManager after the close, and on demand from the UI.
 */
class SyncService(
    private val context: Context,
    private val market: MarketRepository,
    private val analysis: AnalysisRepository,
    private val portfolio: PortfolioRepository,
    private val alerts: AlertRepository,
    private val settings: SettingsStore,
) {
    private val mutex = Mutex()

    suspend fun run(onProgress: suspend (String) -> Unit = {}): String = mutex.withLock {
        onProgress("Fetching latest prices")
        val snapshot = market.syncSnapshot()
        market.syncPsei()

        val mine = portfolio.holdingSymbols() + portfolio.watchSymbols()
        val universe = (market.mostActive(UNIVERSE_SIZE) + mine).distinct()
        onProgress("Updating history for ${universe.size} stocks")
        universe.forEach { market.ensureHistory(it) }

        onProgress("Scanning patterns")
        val scored = analysis.refresh(universe)

        onProgress("Checking disclosures and dividends")
        mine.forEach {
            market.refreshDisclosures(it)
            market.refreshDividends(it)
        }

        val fired = alerts.evaluate()
        fired.forEach { Notifications.alert(context, it) }
        market.prune()

        val message = buildString {
            append("${snapshot.count} prices as of ${snapshot.date}; scored $scored stocks")
            if (fired.isNotEmpty()) append("; ${fired.size} alert${if (fired.size == 1) "" else "s"}")
            snapshot.notes.firstOrNull()?.let { append(". $it") }
        }
        settings.recordSync(snapshot.date, snapshot.source, message)
        message
    }

    companion object {
        /** Top stocks by daily value to analyze, on top of the user's holdings and watchlist. */
        const val UNIVERSE_SIZE = 120
        private const val PERIODIC = "daily-sync"
        private const val ONE_OFF = "sync-now"

        /** Schedules the daily run for about 6 PM Manila time, after EODHD and the backend refresh. */
        fun schedule(context: Context) {
            val now = ZonedDateTime.now(MANILA)
            var next = now.with(LocalTime.of(18, 0))
            if (!next.isAfter(now)) next = next.plusDays(1)
            val request = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(Duration.between(now, next).toMinutes(), TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun syncNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(ONE_OFF, ExistingWorkPolicy.KEEP, request)
        }

        /** Progress text while a manual sync runs, or null when idle. */
        fun progress(context: Context): Flow<String?> =
            WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(ONE_OFF).map { infos ->
                infos.firstOrNull { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
                    ?.let { it.progress.getString(SyncWorker.PROGRESS) ?: "Waiting for network" }
            }
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as PisoApp
        return try {
            val message = app.container.sync.run { setProgress(workDataOf(PROGRESS to it)) }
            Log.i(TAG, message)
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "sync failed", e)
            app.container.settings.recordSync(null, null, "Sync failed: ${e.message ?: e.javaClass.simpleName}")
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val PROGRESS = "progress"
        private const val TAG = "SyncWorker"
    }
}
