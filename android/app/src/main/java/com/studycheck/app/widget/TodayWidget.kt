package com.studycheck.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.widget.RemoteViews
import com.studycheck.app.QuickAction
import com.studycheck.app.R
import com.studycheck.app.data.TodayWidgetState
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.time.OffsetDateTime

/**
 * 桌面小组件：显示今天学了多久 + 当前状态，两个按钮直接进馆 / 出馆。
 *
 * 数据不用联网：App 每次刷新今日数据时把快照写进 [com.studycheck.app.data.TokenStore]，
 * 小组件只负责把它画出来（updatePeriodMillis 半小时一次，或 App 内变化时主动刷新）。
 */
class TodayWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { id -> manager.updateAppWidget(id, build(context)) }
    }

    companion object {
        @EntryPoint
        @InstallIn(SingletonComponent::class)
        interface WidgetEntryPoint {
            fun tokenStore(): com.studycheck.app.data.TokenStore
        }

        /** App 内数据变了：把快照存下来，再让所有小组件重画 */
        fun push(context: Context, state: TodayWidgetState) {
            val ep = EntryPointAccessors.fromApplication(
                context.applicationContext,
                WidgetEntryPoint::class.java,
            )
            val encoded = state.encode()
            if (ep.tokenStore().widgetState == encoded) return
            ep.tokenStore().widgetState = encoded
            refresh(context)
        }

        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, TodayWidget::class.java))
            if (ids.isEmpty()) return
            val views = build(context)
            ids.forEach { id -> manager.updateAppWidget(id, views) }
        }

        private fun pending(context: Context, kind: String, code: Int): PendingIntent =
            PendingIntent.getActivity(
                context,
                code,
                QuickAction.intent(context, kind),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        private fun build(context: Context): RemoteViews {
            val ep = EntryPointAccessors.fromApplication(
                context.applicationContext,
                WidgetEntryPoint::class.java,
            )
            val state = TodayWidgetState.decode(ep.tokenStore().widgetState)

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
                .atZoneSameInstant(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        } catch (t: Throwable) {
            "—"
        }

    }
}
