package com.studycheck.app.data

import android.os.Build
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** GitHub Release 上的更新清单（latest-<arch>.json），字段与服务端 updater 一致。 */
@Serializable
data class UpdateManifest(
    val version: String = "",
    val arch: String = "",
    val url: String = "",
    val sha256: String = "",
    val size: Long = 0,
    val notes: String = "",
)

/** 版本检查结果。 */
sealed interface UpdateState {
    /** 还没查过 */
    data object Idle : UpdateState

    /** 正在查 */
    data object Checking : UpdateState

    /** 已是最新 */
    data class UpToDate(val version: String) : UpdateState

    /** 发现新版本 */
    data class Available(val manifest: UpdateManifest) : UpdateState

    /** 检查失败（网络问题、仓库没发布等） */
    data class Failed(val reason: String) : UpdateState
}

/**
 * App 版本检查：读 GitHub Release 的 latest-<arch>.json，
 * 有新版只提示 + 跳浏览器下载（不做静默安装，省掉安装权限申请）。
 */
@Singleton
class UpdateRepository @Inject constructor() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /** 把 Android ABI 名映射成发布清单里的 arch。 */
    fun arch(): String = when (Build.SUPPORTED_ABIS.firstOrNull()) {
        "arm64-v8a" -> "arm64"
        "armeabi-v7a", "armeabi" -> "arm"
        "x86_64" -> "amd64"
        "x86" -> "386"
        else -> "arm64"
    }

    fun releasePageUrl(): String = "https://github.com/${com.studycheck.app.BuildConfig.UPDATE_REPO}/releases/latest"

    fun apkUrl(): String =
        "https://github.com/${com.studycheck.app.BuildConfig.UPDATE_REPO}/releases/latest/download/studycheck.apk"

    /** 拉取并解析更新清单；失败时抛异常，由调用方转成 Failed 状态。 */
    suspend fun check(repo: String = com.studycheck.app.BuildConfig.UPDATE_REPO): UpdateManifest {
        val url = "https://github.com/$repo/releases/latest/download/latest-${arch()}.json"
        val req = Request.Builder().url(url).header("Accept", "application/json").build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("读取更新信息失败（HTTP ${resp.code}）")
            val body = resp.body?.string().orEmpty()
            if (body.isBlank()) throw IllegalStateException("更新信息为空")
            return json.decodeFromString(UpdateManifest.serializer(), body)
        }
    }

    companion object {
        /**
         * 版本号比较：按点分段逐段比数字。
         * 返回 >0 表示 remote 更新，<0 表示更旧，0 表示相同；无法解析时视为相同。
         */
        fun compare(local: String, remote: String): Int {
            fun parts(v: String) = v.trim().removePrefix("v").split(".")
                .map { p -> p.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
            val a = parts(local)
            val b = parts(remote)
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return if (x > y) 1 else -1
            }
            return 0
        }

        /** 当前是否比远端旧。 */
        fun hasUpdate(local: String, remote: String): Boolean = compare(local, remote) < 0
    }
}
