package com.studycheck.app.data

import android.content.Context
import androidx.core.content.edit

/**
 * 会话与配置存储。
 * 说明：数据放在 App 私有目录（Android 沙箱隔离），个人打卡应用足够；
 * 若要更高强度，可换成 EncryptedSharedPreferences / Keystore 混合方案。
 */
class TokenStore(context: Context) {

    private val prefs = context.getSharedPreferences("studycheck", Context.MODE_PRIVATE)

    var accessToken: String?
        get() = prefs.getString(KEY_ACCESS, null)
        set(v) = prefs.edit { putString(KEY_ACCESS, v) }

    var refreshToken: String?
        get() = prefs.getString(KEY_REFRESH, null)
        set(v) = prefs.edit { putString(KEY_REFRESH, v) }

    /** access token 过期时间（epoch millis） */
    var expiresAt: Long
        get() = prefs.getLong(KEY_EXPIRES, 0L)
        set(v) = prefs.edit { putLong(KEY_EXPIRES, v) }

    var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, null) ?: DEFAULT_BASE_URL
        set(v) = prefs.edit { putString(KEY_BASE_URL, v) }

    var username: String?
        get() = prefs.getString(KEY_USERNAME, null)
        set(v) = prefs.edit { putString(KEY_USERNAME, v) }

    var nickname: String?
        get() = prefs.getString(KEY_NICKNAME, null)
        set(v) = prefs.edit { putString(KEY_NICKNAME, v) }

    /** 服务端下发的每日目标缓存，离线时用它显示进度 */
    var savedGoal: Int
        get() = prefs.getInt(KEY_GOAL, 120)
        set(v) = prefs.edit { putInt(KEY_GOAL, v) }

    var reminderHour: Int
        get() = prefs.getInt(KEY_REMIND_HOUR, 20)
        set(v) = prefs.edit { putInt(KEY_REMIND_HOUR, v) }

    var reminderEnabled: Boolean
        get() = prefs.getBoolean(KEY_REMIND_ON, true)
        set(v) = prefs.edit { putBoolean(KEY_REMIND_ON, v) }

    /** 最近用过的科目（逗号分隔，最多 8 个），打卡时一键选 */
    var recentSubjects: List<String>
        get() = (prefs.getString(KEY_SUBJECTS, "") ?: "")
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        set(v) = prefs.edit { putString(KEY_SUBJECTS, v.distinct().take(8).joinToString(",")) }

    /** 记住一个科目（最近用过的排最前） */
    fun rememberSubject(name: String) {
        val n = name.trim()
        if (n.isEmpty()) return
        recentSubjects = listOf(n) + recentSubjects.filter { it != n }
    }

    /** 外观：auto / light / dark */
    var themeMode: String
        get() = prefs.getString(KEY_THEME, "auto") ?: "auto"
        set(v) = prefs.edit { putString(KEY_THEME, v) }

    /** 主色 id（1.10.0 起可自选），见 com.studycheck.app.ui.theme.BRANDS */
    var themeColor: String
        get() = prefs.getString(KEY_THEME_COLOR, "amber") ?: "amber"
        set(v) = prefs.edit { putString(KEY_THEME_COLOR, v) }

    /** 连续在馆多久提醒一次（分钟）；0 表示不提醒 */
    var overtimeRemindMin: Int
        get() = prefs.getInt(KEY_OVERTIME, 240)
        set(v) = prefs.edit { putInt(KEY_OVERTIME, v) }

    /** 已经为哪一次进馆发过「超时未出馆」提醒（格式 day|inAt），避免反复打扰 */
    var lastOvertimeKey: String
        get() = prefs.getString(KEY_OVERTIME_KEY, "") ?: ""
        set(v) = prefs.edit { putString(KEY_OVERTIME_KEY, v) }

    /** 桌面小组件要显示的快照："day|state|inAtLocal|seconds"（state: in/finished/none） */
    var widgetState: String
        get() = prefs.getString(KEY_WIDGET, "") ?: ""
        set(v) = prefs.edit { putString(KEY_WIDGET, v) }

    /* ---- 每周学习小结 ---- */

    /** 周报提醒开关（周日晚上发一条本周小结） */
    var weeklyReportEnabled: Boolean
        get() = prefs.getBoolean(KEY_WEEKLY_ON, false)
        set(v) = prefs.edit { putBoolean(KEY_WEEKLY_ON, v) }

    /** 周报提醒时间：周几（1=周一 … 7=周日）与小时 */
    var weeklyReportWeekday: Int
        get() = prefs.getInt(KEY_WEEKLY_DAY, 7)
        set(v) = prefs.edit { putInt(KEY_WEEKLY_DAY, v) }

    var weeklyReportHour: Int
        get() = prefs.getInt(KEY_WEEKLY_HOUR, 20)
        set(v) = prefs.edit { putInt(KEY_WEEKLY_HOUR, v) }

    /* ---- 专注（番茄钟）：纯本地状态，关掉 App 再回来接着走 ---- */

    /** 当前阶段：focus 专注 / break 休息 */
    var focusMode: String
        get() = prefs.getString(KEY_FOCUS_MODE, "focus") ?: "focus"
        set(v) = prefs.edit { putString(KEY_FOCUS_MODE, v) }

    /** 这一段的总秒数（25 分钟 = 1500） */
    var focusTotalSec: Int
        get() = prefs.getInt(KEY_FOCUS_TOTAL, 25 * 60)
        set(v) = prefs.edit { putInt(KEY_FOCUS_TOTAL, v) }

    /** 结束时间（epoch millis）；0 表示暂停中，此时看 focusLeftSec */
    var focusEndAt: Long
        get() = prefs.getLong(KEY_FOCUS_END, 0L)
        set(v) = prefs.edit { putLong(KEY_FOCUS_END, v) }

    /** 暂停时剩下的秒数 */
    var focusLeftSec: Int
        get() = prefs.getInt(KEY_FOCUS_LEFT, 25 * 60)
        set(v) = prefs.edit { putInt(KEY_FOCUS_LEFT, v) }

    /** 今天已完成的专注段数 */
    var focusRounds: Int
        get() = prefs.getInt(KEY_FOCUS_ROUNDS, 0)
        set(v) = prefs.edit { putInt(KEY_FOCUS_ROUNDS, v) }

    /** 上次检查 App 新版本的时间（epoch millis），进「我的」页按天节流 */
    var lastUpdateCheckAt: Long
        get() = prefs.getLong(KEY_UPDATE_CHECK, 0L)
        set(v) = prefs.edit { putLong(KEY_UPDATE_CHECK, v) }

    /** 已经发过「目标达成」通知的日期（YYYY-MM-DD），避免一天重复提醒 */
    var lastGoalNotifiedDay: String?
        get() = prefs.getString(KEY_GOAL_NOTIFIED, null)
        set(v) = prefs.edit { putString(KEY_GOAL_NOTIFIED, v) }

    /** 当前账号是不是管理员（决定「我的」页是否显示用户管理） */
    var isAdmin: Boolean
        get() = prefs.getBoolean(KEY_IS_ADMIN, false)
        set(v) = prefs.edit { putBoolean(KEY_IS_ADMIN, v) }

    /** 免登录联调模式（后端 SC_DEV_NO_AUTH 时使用），App 只做本地标记 */
    var devMode: Boolean
        get() = prefs.getBoolean(KEY_DEV, false)
        set(v) = prefs.edit { putBoolean(KEY_DEV, v) }

    val isLoggedIn: Boolean get() = !accessToken.isNullOrBlank()

    /** 能正常发请求：有 token，或处于免登录联调模式 */
    val canSync: Boolean get() = isLoggedIn || devMode

    fun saveSession(access: String, refresh: String, expiresAtMillis: Long) {
        prefs.edit {
            putString(KEY_ACCESS, access)
            putString(KEY_REFRESH, refresh)
            putLong(KEY_EXPIRES, expiresAtMillis)
        }
    }

    fun clearSession() {
        prefs.edit {
            remove(KEY_ACCESS)
            remove(KEY_REFRESH)
            remove(KEY_EXPIRES)
            remove(KEY_IS_ADMIN)
        }
    }

    companion object {
        private const val KEY_ACCESS = "access_token"
        private const val KEY_REFRESH = "refresh_token"
        private const val KEY_EXPIRES = "expires_at"
        private const val KEY_DEV = "dev_mode"
        private const val KEY_IS_ADMIN = "is_admin"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_USERNAME = "username"
        private const val KEY_NICKNAME = "nickname"
        private const val KEY_GOAL = "goal_min"
        private const val KEY_REMIND_HOUR = "remind_hour"
        private const val KEY_REMIND_ON = "remind_enabled"
        private const val KEY_UPDATE_CHECK = "last_update_check_at"
        private const val KEY_GOAL_NOTIFIED = "last_goal_notified_day"
        private const val KEY_SUBJECTS = "recent_subjects"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_THEME_COLOR = "theme_color"
        private const val KEY_OVERTIME = "overtime_remind_min"
        private const val KEY_OVERTIME_KEY = "overtime_reminder_key"
        private const val KEY_WIDGET = "widget_state"
        private const val KEY_FOCUS_MODE = "focus_mode"
        private const val KEY_FOCUS_TOTAL = "focus_total"
        private const val KEY_FOCUS_END = "focus_end_at"
        private const val KEY_FOCUS_LEFT = "focus_left"
        private const val KEY_FOCUS_ROUNDS = "focus_rounds"
        private const val KEY_WEEKLY_ON = "weekly_report_on"
        private const val KEY_WEEKLY_DAY = "weekly_report_day"
        private const val KEY_WEEKLY_HOUR = "weekly_report_hour"

        /** 模拟器访问宿主机用 10.0.2.2；真机请改成本机局域网 IP 或公网域名 */
        var DEFAULT_BASE_URL = "http://10.0.2.2:8080/"
    }
}
