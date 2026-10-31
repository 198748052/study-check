package com.studycheck.app

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 外部入口（通知按钮 / 桌面小组件 / 快捷方式 / 快捷设置磁贴）想要 App 直接打开拍照页时，
 * 统一通过这里把「想做什么」传给 Compose 层：
 *   kind = "in"  → 进馆拍照
 *   kind = "out" → 出馆拍照
 */
object QuickAction {

    const val EXTRA_ACTION = "sc_action"

    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending

    fun request(kind: String?) {
        _pending.value = kind?.takeIf { it == "in" || it == "out" }
    }

    fun consume() {
        _pending.value = null
    }

    /** 从 Activity 的 Intent 里取出快捷动作（没有就返回 null） */
    fun fromIntent(intent: Intent?): String? {
        val kind = intent?.getStringExtra(EXTRA_ACTION) ?: return null
        return kind.takeIf { it == "in" || it == "out" }
    }

    /** 组一个「打开 App 并直接拍照」的 Intent（通知 / 小组件 / 磁贴共用） */
    fun intent(context: Context, kind: String): Intent =
        Intent(context, MainActivity::class.java).apply {
            putExtra(EXTRA_ACTION, kind)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
}
