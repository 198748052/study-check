package com.studycheck.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.util.Log
import android.widget.RemoteViews
import com.studycheck.app.QuickAction
import com.studycheck.app.R
import com.studycheck.app.data.TokenStore
import com.studycheck.app.data.TodayWidgetState
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 桌面小组件：显示今天学了多久 + 当前状态，两个按钮直接进馆 / 出馆。
 *
 * 数据不用联网：App 每次刷新今日数据时把快照写进 [TokenStore]，
 * 小组件只负责把它画出来（updatePeriodMillis 半小时一次，或 App 内变化时主动刷新）。
 *
 * 注意：这是 BroadcastReceiver，系统可能在 App 进程刚被拉起、Hilt 还没初始化时就唤起它
 * （把小组件加到桌面、系统周期性更新、点小组件按钮）。所以这里所有 Hilt 访问都要容错：
 * 拿不到就跳过这次更新，等 App 真正跑起来后会再刷新，绝不能让 App 崩在这里。
 */
class TodayWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = buildOrNull(context) ?: return
        ids.forEach { id -> manager.updateAppWidget(id, views) }
    }

    override fun onReceive(context: Context, intent: android.content.Intent) {
        // 系统广播也可能早于 Hilt 初始化，兜底不让它把进程搞崩
        runCatching { super.onReceive(context, intent) }
            .onFailure { Log.w(TAG, "widget 广播处理失败：${it.message}") }
    }

    companion object {
        private const val TAG = "TodayWidget"

        @EntryPoint
        @InstallIn(SingletonComponent::class)
        interface WidgetEntryPoint {
            fun tokenStore(): TokenStore
        }

        /** Hilt 还没就绪时返回 null（调用方直接跳过） */
        private fun storeOrNull(context: Context): TokenStore? = runCatching {
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                WidgetEntryPoint::class.java,
            ).tokenStore()
        }.onFailure { Log.w(TAG, "Hilt 未就绪，跳过这次小组件更新") }
            .getOrNull()

        /** App 内数据变了：把快照存下来，再让所有小组件重画 */
        fun push(context: Context, state: TodayWidgetState) {
            val store = storeOrNull(context) ?: return
            val encoded = state.encode()
            if (store.widgetState == encoded) return
            store.widgetState = encoded
            refresh(context)
        }

        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, TodayWidget::class.java))
            if (ids.isEmpty()) return
            val views = buildOrNull(context) ?: return
            ids.forEach { id -> manager.updateAppWidget(id, views) }
        }

        private fun pending(context: Context, kind: String, code: Int): PendingIntent =
            PendingIntent.getActivity(
                context,
                code,
                QuickAction.intent(context, kind),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        /** 取不到 Hilt / 画不出视图时返回 null，不抛异常 */
        private fun buildOrNull(context: Context): RemoteViews? {
            val store = storeOrNull(context) ?: return null
            val state = runCatching { TodayWidgetState.decode(store.widgetState) }
                .getOrDefault(TodayWidgetState())
            return runCatching { build(context, state) }
                .onFailure { Log.w(TAG, "小组件渲染失败：${it.message}") }
                .getOrNull()
        }

        private fun build(context: Context, state: TodayWidgetState): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_today)
            views.setTextViewText(R.id.widget_total, state.totalText())

            val (status, hint) = when {
                state.inAt != null && state.outAt == null ->
                    "在馆中" to "从 ${hhmm(state.inAt)} 开始，记得出馆打卡"
                state.inAt != null && state.outAt != null ->
                    "今天已完成" to "${hhmm(state.inAt)} – ${hhmm(state.outAt)}"
                else -> "还没打卡" to "点下面「进馆」开始记录"
            }
            views.setTextViewText(R.id.widget_status, status)
            views.setTextViewText(R.id.widget_hint, hint)

            // 在馆中只给出馆按钮；不在馆只给进馆按钮（分段学习可以再进一次）
            val running = state.inAt != null && state.outAt == null
            views.setViewVisibility(
                R.id.widget_btn_in,
                if (running) android.view.View.GONE else android.view.View.VISIBLE,
            )
            views.setViewVisibility(
                R.id.widget_btn_out,
                if (running) android.view.View.VISIBLE else android.view.View.GONE,
            )

            views.setOnClickPendingIntent(R.id.widget_btn_in, pending(context, "in", 2001))
            views.setOnClickPendingIntent(R.id.widget_btn_out, pending(context, "out", 2002))
            views.setOnClickPendingIntent(R.id.widget_root, openApp(context))
            return views
        }

        private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
            context,
            2000,
            context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?: QuickAction.intent(context, "in"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        private fun hhmm(iso: String?): String = try {
            if (iso.isNullOrBlank()) "—"
            else OffsetDateTime.parse(iso)
                .atZoneSameInstant(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("HH:mm"))
        } catch (t: Throwable) {
            "—"
        }
    }
}
