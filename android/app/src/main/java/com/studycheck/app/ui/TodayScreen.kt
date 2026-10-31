package com.studycheck.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
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

        StaleCard(ui = ui, onClose = viewModel::closeStale)

        SessionCard(ui = ui, onFix = viewModel::fixSession, onDelete = viewModel::deleteSession)

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
                Text(
                    if (ui.sessionCount > 1)
                        "这一段进馆 ${fmtTime(ui.openSession?.inAt)} · 今天累计 ${fmtDuration(ui.totalSecNow(now))}"
                    else "进馆 ${fmtTime(ui.inAt)}",
                    fontSize = 12.sp,
                    color = Muted,
                )
                Spacer(Modifier.height(16.dp))
                PrimaryButton("拍照出馆", onClick = { onOpenCamera("out") })
            }
            DayState.Finished -> {
                val multi = ui.sessionCount > 1
                Pill(if (multi) "已完成 ${ui.sessionCount} 段" else "已完成", Mint)
                Spacer(Modifier.height(10.dp))
                Text(fmtDuration(ui.totalSecNow(now)), fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (multi) "第一次 ${fmtTime(ui.inAt)} · 最后一次 ${fmtTime(ui.outAt)}"
                    else "进馆 ${fmtTime(ui.inAt)} · 出馆 ${fmtTime(ui.outAt)}",
                    fontSize = 12.sp,
                    color = Muted,
                )
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("今日已打卡", Mint)
                    if (ui.pending == 0) Pill("已同步", Brand)
                }
                Spacer(Modifier.height(14.dp))
                // 一天可以自习多段：出馆后还能再开一段
                PrimaryButton("拍照再进馆", onClick = { onOpenCamera("in") })
            }
        }
    }
}

/** 前几天进馆忘了点出馆：提示一下，一键补出馆（服务端允许不带照片） */
@Composable
private fun StaleCard(ui: TodayUi, onClose: () -> Unit) {
    val open = ui.staleOpen ?: return
    SectionCard {
        Text("有一段还没出馆", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Danger)
        Spacer(Modifier.height(6.dp))
        Text(
            "${dayLabel(open.day)} ${fmtTime(open.inAt)} 进馆后还没出馆，补上之后今天的记录才对。",
            fontSize = 12.sp,
            color = Muted,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onClose,
                enabled = !ui.busy,
                colors = ButtonDefaults.buttonColors(containerColor = Brand),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
            ) { Text("补出馆", fontSize = 13.sp) }
            Text("时长按那天的结束算，之后可以手动改", fontSize = 11.sp, color = Muted)
        }
    }
}

/** 今日分段：一天多段时可逐段修正 / 删除 */
@Composable
private fun SessionCard(
    ui: TodayUi,
    onFix: (Long, String, String?, (String?) -> Unit) -> Unit,
    onDelete: (Long) -> Unit,
) {
    if (ui.sessions.isEmpty()) return
    var fixing by remember { mutableStateOf<com.studycheck.app.data.SessionEntity?>(null) }

    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("今日分段", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            Text(
                "${ui.sessionCount} 段 · 共 ${fmtDuration(ui.totalSecNow(System.currentTimeMillis()))}",
                fontSize = 11.sp,
                color = Muted,
            )
        }
        Spacer(Modifier.height(8.dp))
        ui.sessions.forEachIndexed { index, ss ->
            if (index > 0) {
                Spacer(Modifier.height(8.dp))
                Divider()
                Spacer(Modifier.height(8.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(20.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(Brand.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("${index + 1}", fontSize = 10.sp, color = Brand, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.size(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "${fmtTime(ss.inAt)} — ${if (ss.outAt != null) fmtTime(ss.outAt) else "进行中"}",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    val note = listOf(ss.inNote, ss.outNote).filter { it.isNotBlank() }.joinToString(" · ")
                    Text(
                        note.ifBlank { ss.inPlace.ifBlank { ss.outPlace } },
                        fontSize = 10.sp,
                        color = Muted,
                        maxLines = 1,
                    )
                }
                Text(
                    if (ss.outAt != null) fmtDuration(ss.durationSec)
                    else elapsedText(ss.inAt, System.currentTimeMillis()),
                    fontSize = 11.sp,
                    color = Muted,
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "修正",
                    fontSize = 11.sp,
                    color = Brand,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { fixing = ss }
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
                Text(
                    "删除",
                    fontSize = 11.sp,
                    color = Danger,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onDelete(ss.serverId) }
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
    }

    fixing?.let { ss ->
        FixTimesDialog(
            day = ss.day,
            inAt = ss.inAt,
            outAt = ss.outAt,
            onDismiss = { fixing = null },
            onSave = { inIso, outIso ->
                onFix(ss.serverId, inIso, outIso) { fixing = null }
            },
        )
    }
}

@Composable
private fun GoalCard(ui: TodayUi) {
    val done = (ui.totalSecNow(System.currentTimeMillis()) / 60).toInt()
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
