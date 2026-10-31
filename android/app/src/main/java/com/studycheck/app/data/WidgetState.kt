package com.studycheck.app.data

import java.time.Duration
import java.time.OffsetDateTime

/**
 * 桌面小组件要显示的快照（纯数据，不依赖数据库）。
 *
 * 由 App 内的今日页在数据变化时写入 [TokenStore.widgetState]，
 * 小组件只负责读出来画，因此没有网络 / 没打开过 App 也能显示上次的状态。
 */
data class TodayWidgetState(
    val day: String = "",
    /** 当前还开着的这一段的进馆时间（没有就是 null） */
    val inAt: String? = null,
    /** 今天最近一次出馆时间 */
    val outAt: String? = null,
    /** 已经结束的段一共多少秒 */
    val doneSec: Long = 0,
) {

    fun encode(): String =
        "$day|${inAt.orEmpty()}|${outAt.orEmpty()}|$doneSec"

    /** 今天累计时长：已结束的段 + 正在进行的这一段（实时算） */
    fun totalSec(): Long = doneSec + liveSec()

    private fun liveSec(): Long = try {
        if (inAt.isNullOrBlank()) 0L
        else Duration.between(OffsetDateTime.parse(inAt).toInstant(), java.time.Instant.now()).seconds
            .coerceAtLeast(0)
    } catch (t: Throwable) {
        0L
    }

    fun totalText(): String {
        val sec = totalSec()
        if (sec <= 0) return "0 分钟"
        val h = sec / 3600
        val m = (sec % 3600) / 60
        return if (h > 0) "$h 小时 $m 分" else "$m 分钟"
    }

    companion object {
        fun decode(raw: String): TodayWidgetState {
            val parts = raw.split("|")
            if (parts.size < 4) return TodayWidgetState()
            return TodayWidgetState(
                day = parts[0],
                inAt = parts[1].ifBlank { null },
                outAt = parts[2].ifBlank { null },
                doneSec = parts[3].toLongOrNull() ?: 0L,
            )
        }
    }
}
