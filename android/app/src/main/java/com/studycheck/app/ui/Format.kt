package com.studycheck.app.ui

import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

fun mediaUrl(baseUrl: String, path: String?): String? {
    if (path.isNullOrBlank()) return null
    return baseUrl.trimEnd('/') + path
}

fun fmtDuration(sec: Long): String {
    if (sec <= 0) return "—"
    val h = sec / 3600
    val m = (sec % 3600) / 60
    return if (h > 0) "${h} 小时 ${m} 分" else "${m} 分钟"
}

fun fmtMinutes(min: Long): String {
    if (min <= 0) return "0 分"
    val h = min / 60
    val m = min % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

fun fmtTime(iso: String?): String {
    if (iso.isNullOrBlank()) return "—"
    return try {
        OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("HH:mm"))
    } catch (t: Throwable) {
        "—"
    }
}

fun parseMillis(iso: String?): Long = try {
    if (iso.isNullOrBlank()) 0L else OffsetDateTime.parse(iso).toInstant().toEpochMilli()
} catch (t: Throwable) {
    0L
}

/** 在馆时长计时文本 HH:mm:ss */
fun elapsedText(fromIso: String?, nowMillis: Long): String {
    val from = parseMillis(fromIso)
    if (from == 0L) return "00:00:00"
    val diff = ((nowMillis - from) / 1000).coerceAtLeast(0)
    val h = diff / 3600
    val m = (diff % 3600) / 60
    val s = diff % 60
    return "%02d:%02d:%02d".format(h, m, s)
}

private val WEEK = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

fun dayLabel(day: String): String = try {
    val d = LocalDate.parse(day)
    "${d.monthValue} 月 ${d.dayOfMonth} 日 · ${WEEK[d.dayOfWeek.value - 1]}"
} catch (t: Throwable) {
    day
}

fun todayLocal(): String = LocalDate.now(ZoneId.systemDefault()).toString()

fun isSameMonth(day: String, yearMonth: String): Boolean = day.startsWith(yearMonth)
