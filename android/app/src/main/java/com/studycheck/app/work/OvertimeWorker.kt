package com.studycheck.app.work

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.studycheck.app.QuickAction
import com.studycheck.app.R
import com.studycheck.app.data.combineDay
import com.studycheck.app.data.todayString
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.OffsetDateTime

/**
 * 超时未出馆提醒。
 *
 * 每 30 分钟看一眼：如果今天还开着一整段「进馆没出馆」，且已经在馆超过设定的时长
 * （默认 4 小时），就提醒一次「还在馆里吗」。同一次进馆只提醒一次，出馆后再进馆会重新算。
 */
class OvertimeWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val ep = EntryPointAccessors.fromApplication(applicationContext, WorkerEntryPoint::class.java)
        val store = ep.tokenStore()
        if (!store.canSync || !store.reminderEnabled) return Result.success()

        val threshold = store.overtimeRemindMin
        if (threshold <= 0) return Result.success()

        val day = todayString()
        val repo = ep.checkinRepository()
        val snapshot = combineDay(repo.observeDay(day), repo.observePending(day)).first()

        // 今天开着的这一段的进馆时间：优先本地分段缓存，退化到聚合行的 in_at
        val openInAt = repo.latestOpenInAt(day) ?: snapshot.checkin?.inAt
        if (openInAt.isNullOrBlank()) {
            store.lastOvertimeKey = ""
            return Result.success()
        }
        // 已有出馆记录说明这一段已经结束了
        if (snapshot.checkin?.outAt != null) {
            store.lastOvertimeKey = ""
            return Result.success()
        }

        val minutes = try {
            Duration.between(OffsetDateTime.parse(openInAt).toInstant(), java.time.Instant.now()).toMinutes()
        } catch (t: Throwable) {
            return Result.success()
        }
        if (minutes < threshold) return Result.success()

        // 同一次进馆只提醒一次
        val key = "$day|$openInAt"
        if (store.lastOvertimeKey == key) return Result.success()
        store.lastOvertimeKey = key

        notify(minutes)
        return Result.success()
    }

    private fun notify(minutes: Long) {
        val ctx = applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ReminderWorker.ensureChannel(ctx)

        val hours = minutes / 60
        val title = if (hours >= 1) "已经在馆 $hours 小时了" else "已经在馆 $minutes 分钟了"
        val body = "别忘了出馆打卡，把今天的时长记下来"

        // 点开直接进「出馆拍照」
        val open = PendingIntent.getActivity(
            ctx,
            NOTIFY_ID,
            QuickAction.intent(ctx, "out"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, ReminderWorker.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_book)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(open)
            .addAction(0, "去出馆", open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify(NOTIFY_ID, n)
    }

    companion object {
        const val WORK = "studycheck-overtime-reminder"
        const val NOTIFY_ID = 1002

        fun cancel(context: Context) {
            NotificationManagerCompat.from(context).cancel(NOTIFY_ID)
        }
    }
}
