package com.studycheck.app.widget

import android.os.Build
import android.service.quicksettings.TileService
import com.studycheck.app.QuickAction

/**
 * 快捷设置磁贴：从下拉通知栏点一下直接进「进馆拍照」。
 * 磁贴本身不做网络请求（系统会随时唤醒它），只负责把 App 打开到拍照页。
 */
class CheckTileService : TileService() {

    override fun onClick() {
        super.onClick()
        val intent = QuickAction.intent(this, "in")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                android.app.PendingIntent.getActivity(
                    this,
                    3001,
                    intent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                        android.app.PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            label = "研读进馆"
            state = android.service.quicksettings.Tile.STATE_INACTIVE
            updateTile()
        }
    }
}
