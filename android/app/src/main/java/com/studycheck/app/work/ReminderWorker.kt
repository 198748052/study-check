package com.studycheck.app.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.studycheck.app.MainActivity
import com.studycheck.app.R
import com.studycheck.app.data.combineDay
import com.studycheck.app.data.todayString
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first

class ReminderWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val ep = EntryPointAccessors.fromApplication(applicationContext, WorkerEntryPoint::class.java)
        val store = ep.tokenStore()
        if (!store.canSync) return Result.success()

        val day = todayString()
        val repo = ep.checkinRepository()
        val snapshot = combineDay(repo.observeDay(day), repo.observePending(day)).first()
        val checkedIn = snapshot.checkin?.inAt != null || snapshot.pending.any { it.kind == "in" }
        val checkedOut = snapshot.checkin?.outAt != null || snapshot.pending.any { it.kind == "out" }

        when {
            !checkedIn -> notify("今天还没进馆", "去图书馆坐下，拍张照开始今天的计时吧")
            !checkedOut -> notify("还在馆里吗", "别忘了出馆打卡，把今天的时长记下来")
            else -> Unit // 已完成，不打扰
        }
        return Result.success()
    }

    private fun notify(title: String, body: String) = show(applicationContext, title, body, 1001)

    companion object {
        const val CHANNEL_ID = "daily_reminder"

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val mgr = context.getSystemService(NotificationManager::class.java) ?: return
            if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "每日打卡提醒", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "提醒你进馆/出馆打卡与目标达成"
                },
            )
        }

        /** 供上传 Worker 复用：目标达成等即时提醒 */
        fun show(context: Context, title: String, body: String, id: Int) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            ensureChannel(context)
            val intent = Intent(context, MainActivity::class.java)
            val pi = PendingIntent.getActivity(
                context,
                id,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_book)
                .setContentTitle(title)
                .setContentText(body)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(id, notification)
        }
    }
}
