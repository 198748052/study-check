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
    /** 1.8.0 起：科目 / 标签，只对分段有效 */
    val subject: String = "",
)

@Serializable
data class TodayResp(
    val day: String,
    val state: String,
    val checkin: CheckinDto? = null,
    val now: String = "",
    /** 1.7.0 起：当天的所有分段（按进馆时间正序） */
    val sessions: List<CheckinDto> = emptyList(),
    @SerialName("sessions_count") val sessionsCount: Int = 0,
    @SerialName("total_sec") val totalSec: Long = 0,
    /** 1.7.1 起：还没出馆的那一段（可能属于前几天，跨天自习 / 忘了出馆） */
    @SerialName("open_session") val openSession: CheckinDto? = null,
    /** 1.7.1 起：open_session 不是今天的（需要先补出馆） */
    @SerialName("stale_open") val staleOpen: Boolean = false,
)

/** 某一天的分段列表 */
@Serializable
data class SessionListResp(
    val day: String = "",
    val items: List<CheckinDto> = emptyList(),
    val count: Int = 0,
)

/** 手动补一段（不需要照片）。note/place 同时用于进馆侧 */
@Serializable
data class SessionCreateReq(
    val day: String,
    @SerialName("in_at") val inAt: String,
    @SerialName("out_at") val outAt: String = "",
    val note: String = "",
    val place: String = "",
    @SerialName("in_note") val inNote: String? = null,
    @SerialName("out_note") val outNote: String? = null,
    @SerialName("in_place") val inPlace: String? = null,
    @SerialName("out_place") val outPlace: String? = null,
    val subject: String? = null,
)

@Serializable
data class SessionCreateResp(
    val session: CheckinDto? = null,
    val checkin: CheckinDto? = null,
)

@Serializable
data class SessionPatchResp(
    val session: CheckinDto? = null,
    val checkin: CheckinDto? = null,
)

/** 全局搜索：备注 / 地点 / 笔记；kind = session | entry */
@Serializable
data class SearchItemDto(
    val kind: String = "session",
    val id: Long = 0,
    val day: String = "",
    val at: String = "",
    @SerialName("in_at") val inAt: String? = null,
    @SerialName("out_at") val outAt: String? = null,
    val note: String = "",
    val place: String = "",
    @SerialName("photo_url") val photoUrl: String? = null,
    @SerialName("thumb_url") val thumbUrl: String? = null,
    @SerialName("duration_sec") val durationSec: Long = 0,
    val match: String = "",
)

@Serializable
data class SearchResp(
    val query: String = "",
    val count: Int = 0,
    val items: List<SearchItemDto> = emptyList(),
)

@Serializable
data class ChangePasswordReq(
    @SerialName("old_password") val oldPassword: String,
    @SerialName("new_password") val newPassword: String,
)

@Serializable
data class DeleteAccountReq(val password: String)

@Serializable
data class ResetPasswordReq(@SerialName("new_password") val newPassword: String)

@Serializable
data class AdminUserDto(
    val id: Long = 0,
    val username: String = "",
    val nickname: String = "",
    @SerialName("daily_goal_min") val dailyGoalMin: Int = 0,
    @SerialName("is_admin") val isAdmin: Boolean = false,
    @SerialName("created_at") val createdAt: String = "",
    val days: Int = 0,
)

@Serializable
data class AdminUsersResp(
    val count: Int = 0,
    val items: List<AdminUserDto> = emptyList(),
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
    /** 1.8.0 起：科目 / 标签，传空串表示清空 */
    val subject: String? = null,
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
    val subject: String = "",
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
    /** 1.8.0 起：本期各科目时长 */
    val subjects: List<SubjectStat> = emptyList(),
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

/** 科目统计：GET /api/v1/stats/subjects */
@Serializable
data class SubjectStat(
    val subject: String = "",
    val minutes: Long = 0,
    val seconds: Long = 0,
    val days: Int = 0,
    val sessions: Int = 0,
)

@Serializable
data class SubjectStatsResp(
    val from: String = "",
    val to: String = "",
    val count: Int = 0,
    @SerialName("total_sec") val totalSec: Long = 0,
    @SerialName("untagged_sec") val untaggedSec: Long = 0,
    val items: List<SubjectStat> = emptyList(),
)

/** 照片时间轴：GET /api/v1/photos */
@Serializable
data class PhotosResp(
    val month: String = "",
    val from: String = "",
    val to: String = "",
    val count: Int = 0,
    @SerialName("prev_month") val prevMonth: String = "",
    @SerialName("next_month") val nextMonth: String = "",
    val days: List<PhotoDayDto> = emptyList(),
)

@Serializable
data class PhotoDayDto(
    val day: String = "",
    val count: Int = 0,
    val items: List<PhotoItemDto> = emptyList(),
)

@Serializable
data class PhotoItemDto(
    val id: Long = 0,
    val kind: String = "entry",
    @SerialName("photo_url") val photoUrl: String = "",
    @SerialName("thumb_url") val thumbUrl: String = "",
    val at: String = "",
    val day: String = "",
    val subject: String = "",
    val note: String = "",
    @SerialName("in_place") val inPlace: String = "",
    @SerialName("out_place") val outPlace: String = "",
)

/** 导入一行（JSON 导入用） */
@Serializable
data class ImportItem(
    val day: String = "",
    val in_at: String = "",
    val out_at: String = "",
    val subject: String = "",
    val in_note: String = "",
    val out_note: String = "",
    val in_place: String = "",
    val out_place: String = "",
)

@Serializable
data class ImportReq(val items: List<ImportItem> = emptyList())

@Serializable
data class ImportRowError(
    val line: Int = 0,
    val day: String = "",
    val msg: String = "",
)

@Serializable
data class ImportResp(
    val imported: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
    val errors: List<ImportRowError> = emptyList(),
)

/** 专注（番茄钟）快照：存在本地 SharedPreferences，不联网 */
data class FocusSnapshot(
    val mode: String = "focus",
    val totalSec: Int = 25 * 60,
    val endAt: Long = 0,
    val leftSec: Int = 25 * 60,
    val rounds: Int = 0,
) {
    /** 进行中？ */
    val running: Boolean get() = endAt > System.currentTimeMillis()

    /** 现在这一刻剩余秒数（暂停时读 leftSec） */
    fun leftNow(): Int {
        val left = if (running) ((endAt - System.currentTimeMillis()) / 1000).toInt() else leftSec
        return left.coerceIn(0, totalSec)
    }
}
