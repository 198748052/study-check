package com.studycheck.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.studycheck.app.ui.AppRoot
import com.studycheck.app.ui.theme.Bg
import com.studycheck.app.ui.theme.Brand
import com.studycheck.app.ui.theme.Ink
import com.studycheck.app.ui.theme.Muted
import com.studycheck.app.ui.theme.Surface2
import com.studycheck.app.ui.theme.StudyCheckTheme
import dagger.hilt.android.AndroidEntryPoint
import java.io.PrintWriter
import java.io.StringWriter

/**
 * 主界面。
 *
 * 带「启动兜底」：万一 Compose 首屏 / Hilt 注入崩了，进程该崩还是崩（保证不留半死不活的状态），
 * 但崩溃堆栈会先落盘；下次打开时如果检测到上次是启动阶段崩的，就直接显示错误页 +
 * 「重试」按钮，而不是又一次白屏闪退。用户把截图发回来就能定位。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        QuickAction.request(QuickAction.fromIntent(intent))
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()

        // 兜底：把崩溃堆栈写进 SharedPreferences，供下次启动展示
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                val cause = generateSequence(error.cause) { it.cause }
                    .take(3)
                    .joinToString("\n") { "Caused by: ${it.javaClass.name}: ${it.message}" }
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(KEY_LAST_CRASH, sw.toString() + (if (cause.isBlank()) "" else "\n\n$cause"))
                    .putLong(KEY_AT, System.currentTimeMillis())
                    .apply()
            }
            prev?.uncaughtException(thread, error) ?: run {
                Process.killProcess(Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }

        // 上次是启动阶段崩的：直接显示堆栈，不再硬闯一次
        val lastCrash = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString(KEY_LAST_CRASH, null)
            ?.takeIf { it.isNotBlank() }
        if (lastCrash != null) {
            setContent {
                StudyCheckTheme {
                    CrashScreen(lastCrash) {
                        // 用户点「重试」：清掉记录再正常启动
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .remove(KEY_LAST_CRASH).apply()
                        recreate()
                    }
                }
            }
            return
        }

        setContent {
            StudyCheckTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Bg) {
                    AppRoot()
                }
            }
        }
    }

    /** 通知按钮 / 小组件 / 快捷方式再次打开 App 时，接住「进馆 / 出馆」动作 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        QuickAction.request(QuickAction.fromIntent(intent))
    }

    /** Android 13+ 需要显式申请通知权限，否则每日提醒无法弹出 */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    companion object {
        private const val PREFS = "studycheck_startup"
        private const val KEY_LAST_CRASH = "last_crash"
        private const val KEY_AT = "at"
    }
}

/** 启动失败时把堆栈摊开给用户看（截图发回来就能定位） */
@androidx.compose.runtime.Composable
private fun CrashScreen(text: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("App 启动失败了", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink)
        Text("下面是错误信息，截图发给我就能修：", fontSize = 12.sp, color = Muted)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(Surface2)
                .padding(12.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(text, fontSize = 10.sp, color = Ink, fontFamily = FontFamily.Monospace)
            }
        }
        Button(
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth().height(46.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Brand),
        ) { Text("重试", color = Color.White) }
        Text(
            "重试也打不开的话：设置 → 应用 → 研读 → 存储 → 清除数据（会丢本地未上传的照片）",
            fontSize = 10.sp,
            color = Muted,
        )
    }
}
