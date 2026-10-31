package com.studycheck.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LoginReq(val username: String, val password: String)

@Serializable
data class RegisterReq(val username: String, val password: String, val nickname: String = "")

@Serializable
data class UpdateMeReq(
    val nickname: String? = null,
    @SerialName("daily_goal_min") val dailyGoalMin: Int? = null,
)

@Serializable
data class UserDto(
    val id: Long,
    val username: String,
    val nickname: String = "",
    @SerialName("daily_goal_min") val dailyGoalMin: Int = 120,
    @SerialName("is_admin") val isAdmin: Boolean = false,
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable
data class AuthResp(
    val token: String,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("refresh_token") val refreshToken: String = "",
    @SerialName("refresh_expires_at") val refreshExpiresAt: String = "",
    val user: UserDto,
)

@Serializable
data class RefreshReq(@SerialName("refresh_token") val refreshToken: String)

@Serializable
data class CheckinDto(
    val id: Long,
    val day: String,
    @SerialName("in_at") val inAt: String? = null,
    @SerialName("out_at") val outAt: String? = null,
    @SerialName("in_photo_url") val inPhotoUrl: String? = null,
    @SerialName("out_photo_url") val outPhotoUrl: String? = null,
    @SerialName("in_thumb_url") val inThumbUrl: String? = null,
    @SerialName("out_thumb_url") val outThumbUrl: String? = null,
    @SerialName("in_note") val inNote: String = "",
    @SerialName("out_note") val outNote: String = "",
    @SerialName("in_place") val inPlace: String = "",
    @SerialName("out_place") val outPlace: String = "",
    @SerialName("duration_sec") val durationSec: Long = 0,
    @SerialName("updated_at") val updatedAt: String = "",
)

@Serializable
data class TodayResp(
    val day: String,
    val state: String,
    val checkin: CheckinDto? = null,
    val now: String = "",
)

@Serializable
data class ListResp(
    val items: List<CheckinDto> = emptyList(),
    val count: Int = 0,
    @SerialName("server_time") val serverTime: String = "",
)

@Serializable
data class StatsDto(
    val today: String = "",
    @SerialName("total_days") val totalDays: Int = 0,
    @SerialName("completed_days") val completedDays: Int = 0,
    @SerialName("current_streak") val currentStreak: Int = 0,
    @SerialName("longest_streak") val longestStreak: Int = 0,
    @SerialName("total_minutes") val totalMinutes: Long = 0,
    @SerialName("today_minutes") val todayMinutes: Long = 0,
    @SerialName("avg_minutes") val avgMinutes: Long = 0,
    @SerialName("month_days") val monthDays: Int = 0,
    @SerialName("month_minutes") val monthMinutes: Long = 0,
    @SerialName("week_days") val weekDays: Int = 0,
    @SerialName("week_minutes") val weekMinutes: Long = 0,
    @SerialName("daily_goal_min") val dailyGoalMin: Int = 120,
    val heatmap: List<HeatCell> = emptyList(),
    @SerialName("server_time") val serverTime: String = "",
)

@Serializable
data class HeatCell(
    val day: String,
    val minutes: Long = 0,
    @SerialName("has_photo") val hasPhoto: Boolean = false,
)

@Serializable
data class HealthDto(
    val status: String = "",
    val version: String = "",
    @SerialName("min_supported_version") val minSupportedVersion: String = "",
)

/** 服务端统一错误体：{"error":{"code":"...","message":"..."}} */
@Serializable
data class ApiErrorBody(val error: ApiError? = null) {
    @Serializable
    data class ApiError(val code: String = "", val message: String = "")
}

/** 每日详情：当天随手拍的照片 + 笔记（过程留存，和「进/出馆打卡」分开） */
@Serializable
data class EntryDto(
    val id: Long,
    val day: String = "",
    @SerialName("photo_url") val photoUrl: String? = null,
    @SerialName("thumb_url") val thumbUrl: String? = null,
    val note: String = "",
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable
data class EntryListResp(
    val items: List<EntryDto> = emptyList(),
    val count: Int = 0,
    val day: String = "",
)

@Serializable
data class EntryCreateResp(
    val items: List<EntryDto> = emptyList(),
    val count: Int = 0,
    val day: String = "",
)

/** 手动修正打卡时间。null 字段表示不改；outAt 传空串表示清空（还没出馆） */
@Serializable
data class PatchCheckinReq(
    @SerialName("in_at") val inAt: String? = null,
    @SerialName("out_at") val outAt: String? = null,
    @SerialName("in_note") val inNote: String? = null,
    @SerialName("out_note") val outNote: String? = null,
    @SerialName("in_place") val inPlace: String? = null,
    @SerialName("out_place") val outPlace: String? = null,
)

/** 手动补卡：只需日期与时间，不传照片 */
@Serializable
data class ManualCheckinReq(
    val day: String,
    @SerialName("in_at") val inAt: String,
    @SerialName("out_at") val outAt: String = "",
    @SerialName("in_note") val inNote: String = "",
    @SerialName("out_note") val outNote: String = "",
    @SerialName("in_place") val inPlace: String = "",
    @SerialName("out_place") val outPlace: String = "",
)

@Serializable
data class ReportDto(
    val period: String = "week",
    val start: String = "",
    val end: String = "",
    val label: String = "",
    val days: Int = 0,
    @SerialName("completed_days") val completedDays: Int = 0,
    val minutes: Long = 0,
    @SerialName("avg_minutes") val avgMinutes: Long = 0,
    @SerialName("goal_minutes") val goalMinutes: Int = 0,
    @SerialName("goal_days") val goalDays: Int = 0,
    @SerialName("goal_rate") val goalRate: Int = 0,
    @SerialName("best_day") val bestDay: ReportBestDay = ReportBestDay(),
    @SerialName("by_weekday") val byWeekday: List<WeekdayStat> = emptyList(),
    val places: List<PlaceStat> = emptyList(),
    @SerialName("prev_days") val prevDays: Int = 0,
    @SerialName("prev_minutes") val prevMinutes: Long = 0,
    @SerialName("delta_minutes") val deltaMinutes: Long = 0,
)

@Serializable
data class ReportBestDay(val day: String = "", val minutes: Long = 0)

@Serializable
data class WeekdayStat(
    val weekday: Int = 0,
    val label: String = "",
    val days: Int = 0,
    val minutes: Long = 0,
)

@Serializable
data class PlaceStat(val name: String = "", val days: Int = 0, val times: Int = 0)

@Serializable
data class AchievementsDto(
    @SerialName("current_streak") val currentStreak: Int = 0,
    @SerialName("longest_streak") val longestStreak: Int = 0,
    @SerialName("total_days") val totalDays: Long = 0,
    @SerialName("total_minutes") val totalMinutes: Long = 0,
    val achievements: List<AchievementDto> = emptyList(),
    val next: AchievementDto? = null,
)

@Serializable
data class AchievementDto(
    val id: String = "",
    val category: String = "",
    val title: String = "",
    val desc: String = "",
    val target: Long = 0,
    val progress: Long = 0,
    val unlocked: Boolean = false,
    @SerialName("achieved_on") val achievedOn: String = "",
)
