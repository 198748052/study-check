package com.studycheck.app.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.studycheck.app.data.CheckinRepository
import com.studycheck.app.data.EntryRepository
import com.studycheck.app.data.TokenStore
import com.studycheck.app.data.todayString
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WorkerEntryPoint {
    fun checkinRepository(): CheckinRepository
    fun entryRepository(): EntryRepository
    fun tokenStore(): TokenStore
}

/** 上传队列 Worker：弱网/无网时由 WorkManager 自动退避重试 */
class UploadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val ep = EntryPointAccessors.fromApplication(applicationContext, WorkerEntryPoint::class.java)
        val store = ep.tokenStore()
        if (!store.canSync) return Result.success()
        val repo = ep.checkinRepository()
        val checkinOk = repo.uploadPending()
        val entryOk = ep.entryRepository().uploadPending()
        if (checkinOk && entryOk) {
            notifyGoalIfReached(repo, store)
            return Result.success()
        }
        return Result.retry()
    }

    /** 上传完成后同步当日状态，达到目标就发一次通知（按天去重） */
    private suspend fun notifyGoalIfReached(repo: CheckinRepository, store: TokenStore) {
        val goal = store.savedGoal
        if (goal <= 0) return
        val day = todayString()
        if (store.lastGoalNotifiedDay == day) return
        repo.syncDay(day)
        val sec = repo.observeDay(day).first()?.durationSec ?: 0L
        if (sec < goal * 60L) return
        store.lastGoalNotifiedDay = day
        ReminderWorker.show(
            applicationContext,
            "今日目标达成",
            "今天已自习 ${sec / 60} 分钟，目标 $goal 分钟",
            1002,
        )
    }
}

object UploadScheduler {
    private const val WORK = "studycheck-upload-queue"

    fun enqueue(context: Context) {
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE, request)
    }
}

object ReminderScheduler {
    private const val WORK = "studycheck-daily-reminder"

    /** 每天固定时间检查一次，今天没打卡就发提醒 */
    fun schedule(context: Context, hour: Int, enabled: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (!enabled) {
            wm.cancelUniqueWork(WORK)
            return
        }
        val now = LocalDateTime.now(ZoneId.systemDefault())
        var target = now.with(LocalTime.of(hour, 0))
        if (!target.isAfter(now)) target = target.plusDays(1)
        val delay = Duration.between(now, target)

        val request = PeriodicWorkRequestBuilder<ReminderWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(delay.toMinutes().coerceAtLeast(1), TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                    .build(),
            )
            .build()

        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}
