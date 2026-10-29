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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.studycheck.app.data.CheckinEntity
import com.studycheck.app.ui.theme.Brand
import com.studycheck.app.ui.theme.Danger
import com.studycheck.app.ui.theme.Mint
import com.studycheck.app.ui.theme.Muted
import com.studycheck.app.ui.theme.Surface2

@Composable
fun HistoryScreen(
    onOpenDay: (String) -> Unit,
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<CheckinEntity?>(null) }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这条记录？", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "${dayLabel(target.day)} 的打卡记录会被删除，照片和时长一起删掉，不能恢复。",
                    fontSize = 13.sp,
                    color = Muted,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(target)
                    pendingDelete = null
                }) { Text("删除", color = Danger) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消", color = Muted) }
            },
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("历史", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("${ui.month.replace("-", " 年 ")} 月 · 共 ${ui.items.size} 天", fontSize = 12.sp, color = Muted)
            }
            TextButton(onClick = { viewModel.shiftMonth(-1) }) { Text("‹ 上月", color = Brand, fontSize = 12.sp) }
            TextButton(onClick = { viewModel.shiftMonth(1) }) { Text("下月 ›", color = Brand, fontSize = 12.sp) }
        }

        ui.error?.let {
            Spacer(Modifier.height(6.dp))
            Text("加载失败：$it", color = Danger, fontSize = 12.sp)
        }

        if (ui.items.isEmpty()) {
            Spacer(Modifier.height(40.dp))
            EmptyBox(if (ui.loading) "加载中…" else "这个月还没有记录")
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
            ) {
                items(ui.items, key = { it.day }) { item ->
                    HistoryRow(
                        item = item,
                        baseUrl = ui.baseUrl,
                        onClick = { onOpenDay(item.day) },
                        onDelete = { pendingDelete = item },
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(
    item: CheckinEntity,
    baseUrl: String,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    SectionCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ThumbSquare(
                    url = mediaUrl(baseUrl, item.inThumb ?: item.inPhoto),
                    size = 44.dp,
                    label = "进",
                    corner = 8.dp,
                )
                ThumbSquare(
                    url = mediaUrl(baseUrl, item.outThumb ?: item.outPhoto),
                    size = 44.dp,
                    label = "出",
                    corner = 8.dp,
                )
            }
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text(dayLabel(item.day), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(2.dp))
                Text(
                    "进 ${fmtTime(item.inAt)} · 出 ${fmtTime(item.outAt)}",
                    fontSize = 11.sp,
                    color = Muted,
                )
                val notes = listOf(item.inNote, item.outNote).filter { it.isNotBlank() }
                if (notes.isNotEmpty()) {
                    Text(notes.joinToString(" · "), fontSize = 10.sp, color = Muted, maxLines = 1)
                }
            }
            Spacer(Modifier.size(6.dp))
            Column(horizontalAlignment = Alignment.End) {
                if (item.outAt == null) {
                    Pill("未出馆", Brand)
                } else {
                    Pill(fmtDuration(item.durationSec), Mint)
                }
                Spacer(Modifier.height(3.dp))
                Text("详情 ›", fontSize = 10.sp, color = Brand)
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "删除",
                    tint = Muted,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
fun StatsScreen(viewModel: StatsViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val s = ui.stats

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("统计", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("坚持的力量", fontSize = 12.sp, color = Muted)
            }
            TextButton(onClick = { viewModel.reload() }) { Text("刷新", color = Brand, fontSize = 12.sp) }
        }

        ui.error?.let { Text("加载失败：$it", color = Danger, fontSize = 12.sp) }

        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("连续打卡", fontSize = 12.sp, color = Muted)
                    Text("${s?.currentStreak ?: 0} 天", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Brand)
                }
                Column(Modifier.weight(1f)) {
                    Text("最长连续", fontSize = 12.sp, color = Muted)
                    Text("${s?.longestStreak ?: 0} 天", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Mint)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(Modifier.weight(1f), "本月", "${s?.monthDays ?: 0} 天", fmtMinutes(s?.monthMinutes ?: 0))
            StatTile(Modifier.weight(1f), "本周", "${s?.weekDays ?: 0} 天", fmtMinutes(s?.weekMinutes ?: 0))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(Modifier.weight(1f), "累计天数", "${s?.totalDays ?: 0} 天", "已打卡")
            StatTile(Modifier.weight(1f), "平均时长", fmtMinutes(s?.avgMinutes ?: 0), "每天")
        }

        SectionCard {
            Text("最近半年", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(10.dp))
            Heatmap(cells = s?.heatmap ?: emptyList(), goalMin = s?.dailyGoalMin ?: 120)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("少", fontSize = 10.sp, color = Muted)
                Spacer(Modifier.size(4.dp))
                listOf(0.10f, 0.30f, 0.55f, 0.80f, 1f).forEach { a ->
                    Box(
                        Modifier
                            .size(11.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Brand.copy(alpha = a)),
                    )
                    Spacer(Modifier.size(3.dp))
                }
                Text("多", fontSize = 10.sp, color = Muted)
                Spacer(Modifier.weight(1f))
                Text("累计 ${fmtMinutes(s?.totalMinutes ?: 0)}", fontSize = 10.sp, color = Muted)
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun StatTile(modifier: Modifier = Modifier, label: String, value: String, sub: String) {
    SectionCard(modifier = modifier) {
        Text(label, fontSize = 11.sp, color = Muted)
        Spacer(Modifier.height(4.dp))
        Text(value, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text(sub, fontSize = 10.sp, color = Muted)
    }
}

@Composable
private fun Heatmap(cells: List<com.studycheck.app.data.HeatCell>, goalMin: Int) {
    if (cells.isEmpty()) {
        Text("暂无数据", fontSize = 11.sp, color = Muted)
        return
    }
    val weeks = cells.chunked(7)
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        weeks.forEach { week ->
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                week.forEach { cell ->
                    val ratio = if (goalMin > 0) (cell.minutes.toFloat() / goalMin) else 0f
                    val alpha = when {
                        cell.minutes <= 0L -> 0f
                        ratio < 0.34f -> 0.22f
                        ratio < 0.67f -> 0.45f
                        ratio < 1f -> 0.72f
                        else -> 1f
                    }
                    Box(
                        Modifier
                            .size(11.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(
                                if (alpha == 0f) Surface2 else Brand.copy(alpha = alpha),
                            ),
                    )
                }
            }
        }
    }
}
