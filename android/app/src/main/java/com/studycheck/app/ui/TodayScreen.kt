package com.studycheck.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.studycheck.app.ui.theme.Brand
import com.studycheck.app.ui.theme.Danger
import com.studycheck.app.ui.theme.Line
import com.studycheck.app.ui.theme.Mint
import com.studycheck.app.ui.theme.Muted
import com.studycheck.app.ui.theme.Surface2
import kotlinx.coroutines.delay

@Composable
fun TodayScreen(
    onOpenCamera: (String) -> Unit,
    onOpenDetail: () -> Unit,
    viewModel: TodayViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(ui.state, ui.inAt) {
        while (ui.state == DayState.InLibrary) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("今天", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                Text(dayLabel(ui.day), fontSize = 12.sp, color = Muted)
            }
            if (ui.streak > 0) Pill("连续 ${ui.streak} 天", Mint)
            Spacer(Modifier.size(6.dp))
            if (ui.busy) CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Brand, strokeWidth = 2.dp)
        }

        StatusCard(ui = ui, now = now, onOpenCamera = onOpenCamera)

        if (ui.error != null) {
            SectionCard {
                Text("同步失败：${ui.error}", color = Danger, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                Text("网络恢复后会自动重试；已有照片都保存在手机里不会丢。", fontSize = 11.sp, color = Muted)
            }
        }

        GoalCard(ui = ui)

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MiniStat(Modifier.weight(1f), "本周", "${ui.weekDays} 天")
            MiniStat(Modifier.weight(1f), "本周时长", fmtMinutes(ui.weekMinutes))
            MiniStat(Modifier.weight(1f), "最长连续", "${ui.longest} 天")
        }

        if (ui.pending > 0) {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Pill("${ui.pending} 张待上传", Brand)
                    Spacer(Modifier.weight(1f))
                    Text("点击重试", color = Brand, fontSize = 12.sp, modifier = Modifier.padding(4.dp))
                }
                Spacer(Modifier.height(6.dp))
                Text("照片已保存在手机本地，联网后会自动补传。", fontSize = 11.sp, color = Muted)
            }
        }

        PhotoRow(ui = ui)

        DetailEntryCard(onOpenDetail = onOpenDetail)

        Spacer(Modifier.height(4.dp))
        Text(
            "服务器：${ui.baseUrl}",
            fontSize = 10.sp,
            color = Muted,
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun StatusCard(ui: TodayUi, now: Long, onOpenCamera: (String) -> Unit) {
    SectionCard {
        when (ui.state) {
            DayState.NotStarted -> {
                Pill("未打卡", Muted)
                Spacer(Modifier.height(10.dp))
                Text("还没进馆", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text("拍下图书馆门口的照片，开始今天的自习。", fontSize = 12.sp, color = Muted)
                Spacer(Modifier.height(16.dp))
                PrimaryButton("拍照进馆", onClick = { onOpenCamera("in") })
            }
            DayState.InLibrary -> {
                Pill("在馆中", Mint)
                Spacer(Modifier.height(10.dp))
                Text(
                    elapsedText(ui.inAt, now),
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text("进馆 ${fmtTime(ui.inAt)}", fontSize = 12.sp, color = Muted)
                Spacer(Modifier.height(16.dp))
                PrimaryButton("拍照出馆", onClick = { onOpenCamera("out") })
            }
            DayState.Finished -> {
                Pill("已完成", Mint)
                Spacer(Modifier.height(10.dp))
                Text(fmtDuration(ui.elapsedSec), fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Text(
                    "进馆 ${fmtTime(ui.inAt)} · 出馆 ${fmtTime(ui.outAt)}",
                    fontSize = 12.sp,
                    color = Muted,
                )
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("今日已打卡", Mint)
                    if (ui.pending == 0) Pill("已同步", Brand)
                }
            }
        }
    }
}

@Composable
private fun GoalCard(ui: TodayUi) {
    val done = when (ui.state) {
        DayState.Finished -> (ui.elapsedSec / 60).toInt()
        else -> 0
    }
    val ratio = if (ui.goalMin > 0) done.toFloat() / ui.goalMin else 0f
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("今日目标", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            Text("$done / ${ui.goalMin} 分钟", fontSize = 12.sp, color = Muted)
        }
        Spacer(Modifier.height(10.dp))
        ProgressBar(progress = ratio, color = if (ratio >= 1f) Mint else Brand)
        Spacer(Modifier.height(6.dp))
        Text(
            when {
                ratio >= 1f -> "目标达成，保持住 🎉"
                ui.state == DayState.InLibrary -> "计时中，出馆后自动计入"
                else -> "今天还没有完成打卡"
            },
            fontSize = 11.sp,
            color = Muted,
        )
    }
}

@Composable
private fun MiniStat(modifier: Modifier = Modifier, label: String, value: String) {
    SectionCard(modifier = modifier) {
        Text(value, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(2.dp))
        Text(label, fontSize = 11.sp, color = Muted)
    }
}

@Composable
private fun PhotoRow(ui: TodayUi) {
    if (ui.inThumb == null && ui.outThumb == null) return
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("打卡照片", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            Text("点击查看大图", fontSize = 10.sp, color = Muted)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PhotoMini(
                modifier = Modifier.weight(1f),
                url = mediaUrl(ui.baseUrl, ui.inThumb),
                label = "进馆 ${fmtTime(ui.inAt)}",
                note = ui.inNote,
            )
            PhotoMini(
                modifier = Modifier.weight(1f),
                url = mediaUrl(ui.baseUrl, ui.outThumb),
                label = "出馆 ${fmtTime(ui.outAt)}",
                note = ui.outNote,
            )
        }
    }
}

/** 小图 + 右侧两行文字，图片不再占满屏 */
@Composable
private fun PhotoMini(modifier: Modifier = Modifier, url: String?, label: String, note: String) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ThumbSquare(url = url, size = 56.dp, label = "无照片", corner = 9.dp)
        Spacer(Modifier.size(8.dp))
        Column {
            Text(label, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(
                note.ifBlank { if (url == null) "未拍" else "已留存" },
                fontSize = 10.sp,
                color = Muted,
                maxLines = 2,
            )
        }
    }
}

/** 二级页面入口：当天的过程留存（连拍照片 + 笔记） */
@Composable
private fun DetailEntryCard(onOpenDetail: () -> Unit) {
    SectionCard(modifier = Modifier.clickable { onOpenDetail() }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("今日详情", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(2.dp))
                Text("连拍记录学习过程、写下当天笔记", fontSize = 11.sp, color = Muted)
            }
            Text("进入 ›", fontSize = 12.sp, color = Brand, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
fun EmptyBox(text: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White)
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Muted, fontSize = 12.sp)
    }
}

@Composable
fun Divider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Line),
    )
}
