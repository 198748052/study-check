package com.studycheck.app.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import com.studycheck.app.data.todayString
import dagger.hilt.android.EntryPointAccessors
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.TimeUnit

/**
 * 每周学习小结：周日晚上（默认 20:00）用本地缓存算一下这周学了多久，
 * 发一条「本周 X 小时 / N 天，比上周多 Y」的通知，点一下回到 App。
 *
 * 数据来自本地 Room 缓存（App 打开时会同步），所以离线也能算；
 * 只做提醒，不写任何数据。
 */
class WeeklyReportWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val ep = EntryPointAccessors.fromApplication(applicationContext, WeeklyEntryPoint::class.java)
        val store = ep.tokenStore()
        if (!store.canSync || !store.weeklyReportEnabled) return Result.success()

        val dao = ep.checkinDao()
        val today = LocalDate.parse(todayString())
        // 本周：周一 → 今天；上周：上一个周一 → 上周日
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val thisWeek = dao.between(monday.toString(), today.toString())
        if (thisWeek.isEmpty()) return Result.success() // 这周没学过，不打扰

        val lastMonday = monday.minusWeeks(1)
        val lastWeek = dao.between(lastMonday.toString(), lastMonday.plusDays(6).toString())

        val curSec = thisWeek.sumOf { it.durationSec }
        val prevSec = lastWeek.sumOf { it.durationSec }
        val days = thisWeek.size

        val diff = curSec - prevSec
        val cmp = when {
            lastWeek.isEmpty() -> ""
            diff > 300 -> "，比上周多 ${fmt(diff)}"
            diff < -300 -> "，比上周少 ${fmt(-diff)}"
            else -> "，和上周差不多"
        }
        ReminderWorker.show(
            applicationContext,
            "本周学了 ${fmt(curSec)}",
            "打卡 $days 天$cmp。看看统计页，下周继续。",
            1003,
        )
        return Result.success()
    }

    private fun fmt(sec: Long): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        return if (h > 0) "${h} 小时 ${m} 分" else "${m} 分钟"
    }

    companion object {
        const val WORK = "studycheck-weekly-report"

        /** 到下一个「周几 + 几点」还有多少分钟，用来设置首次延迟 */
        fun delayToNext(weekday: Int, hour: Int, now: LocalDate = LocalDate.now(), nowMin: Int = 0): Long {
            val target = DayOfWeek.of(weekday.coerceIn(1, 7))
            var d = now.with(TemporalAdjusters.nextOrSame(target))
            if (d == now && nowMin >= hour * 60) d = d.plusWeeks(1)
            val days = java.time.temporal.ChronoUnit.DAYS.between(now, d)
            val minutes = days * 24L * 60 + (hour * 60L - nowMin)
            return minutes.coerceAtLeast(1)
        }

        fun schedule(context: Context, enabled: Boolean, weekday: Int, hour: Int) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) {
                wm.cancelUniqueWork(WORK)
                return
            }
            val delayMin = delayToNext(weekday, hour)
            val request = PeriodicWorkRequestBuilder<WeeklyReportWorker>(7, TimeUnit.DAYS)
                .setInitialDelay(delayMin, TimeUnit.MINUTES)
                .build()
            wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}

@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface WeeklyEntryPoint {
    fun tokenStore(): com.studycheck.app.data.TokenStore
    fun checkinDao(): com.studycheck.app.data.CheckinDao
}
