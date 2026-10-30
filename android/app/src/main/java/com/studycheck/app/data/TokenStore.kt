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

    /** 上次检查 App 新版本的时间（epoch millis），进「我的」页按天节流 */
    var lastUpdateCheckAt: Long
        get() = prefs.getLong(KEY_UPDATE_CHECK, 0L)
        set(v) = prefs.edit { putLong(KEY_UPDATE_CHECK, v) }

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
        }
    }

    companion object {
        private const val KEY_ACCESS = "access_token"
        private const val KEY_REFRESH = "refresh_token"
        private const val KEY_EXPIRES = "expires_at"
        private const val KEY_DEV = "dev_mode"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_USERNAME = "username"
        private const val KEY_NICKNAME = "nickname"
        private const val KEY_GOAL = "goal_min"
        private const val KEY_REMIND_HOUR = "remind_hour"
        private const val KEY_REMIND_ON = "remind_enabled"
        private const val KEY_UPDATE_CHECK = "last_update_check_at"

        /** 模拟器访问宿主机用 10.0.2.2；真机请改成本机局域网 IP 或公网域名 */
        var DEFAULT_BASE_URL = "http://10.0.2.2:8080/"
    }
}
