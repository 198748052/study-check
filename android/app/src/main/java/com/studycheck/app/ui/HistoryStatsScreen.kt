package com.studycheck.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.studycheck.app.data.CheckinEntity
import com.studycheck.app.data.todayString
import com.studycheck.app.ui.theme.Brand
import com.studycheck.app.ui.theme.Danger
import com.studycheck.app.ui.theme.Mint
import com.studycheck.app.ui.theme.Muted
import com.studycheck.app.ui.theme.Surface2

@Composable
fun HistoryScreen(
    onOpenDay: (String) -> Unit,
    viewModel: HistoryViewModel = hiltViewModel(),
    searchViewModel: SearchViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val sUi by searchViewModel.ui.collectAsStateWithLifecycle()
    val searching = sUi.query.isNotBlank() && (sUi.searched || sUi.loading)
    var pendingDelete by remember { mutableStateOf<CheckinEntity?>(null) }
    var showManual by remember { mutableStateOf(false) }

    if (showManual) {
        ManualCheckinDialog(
            busy = ui.manualBusy,
            error = ui.manualError,
            onDismiss = { showManual = false },
            onSubmit = { day, inAt, outAt, place, note ->
                viewModel.createManual(day, inAt, outAt, place, note) { ok, _ ->
                    if (ok) showManual = false
                }
            },
        )
    }

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
            TextButton(onClick = { showManual = true }) { Text("补卡", color = Mint, fontSize = 12.sp) }
            TextButton(onClick = { viewModel.shiftMonth(-1) }) { Text("‹ 上月", color = Brand, fontSize = 12.sp) }
            TextButton(onClick = { viewModel.shiftMonth(1) }) { Text("下月 ›", color = Brand, fontSize = 12.sp) }
        }

        // 搜索：备注 / 地点 / 笔记，或直接输日期
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = sUi.query,
                onValueChange = { searchViewModel.updateQuery(it) },
                label = { Text("搜索备注、地点、笔记或 2026-10-24") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { searchViewModel.search() }) {
                Text("搜索", color = Brand, fontSize = 12.sp)
            }
            if (sUi.query.isNotBlank()) {
                TextButton(onClick = { searchViewModel.updateQuery("") }) {
                    Text("清空", color = Muted, fontSize = 12.sp)
                }
            }
        }

        ui.error?.let {
            Spacer(Modifier.height(6.dp))
            Text("加载失败：$it", color = Danger, fontSize = 12.sp)
        }
        sUi.error?.let {
            Spacer(Modifier.height(6.dp))
            Text("搜索失败：$it", color = Danger, fontSize = 12.sp)
        }

        if (searching) {
            if (sUi.loading) {
                Spacer(Modifier.height(30.dp))
                EmptyBox("搜索中…")
            } else if (sUi.items.isEmpty()) {
                Spacer(Modifier.height(30.dp))
                EmptyBox("没找到，换个关键词试试")
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
                ) {
                    items(sUi.items, key = { "${it.kind}-${it.id}" }) { item ->
                        SearchRow(
                            item = item,
                            baseUrl = ui.baseUrl,
                            onClick = { if (item.day.isNotBlank()) onOpenDay(item.day) },
                        )
                    }
                }
            }
        } else if (ui.items.isEmpty()) {
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

/** 一条搜索结果：打卡某一段，或一条过程笔记 */
@Composable
private fun SearchRow(
    item: com.studycheck.app.data.SearchItemDto,
    baseUrl: String,
    onClick: () -> Unit,
) {
    SectionCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ThumbSquare(
                url = mediaUrl(baseUrl, item.thumbUrl ?: item.photoUrl),
                size = 44.dp,
                label = if (item.kind == "entry") "记" else "段",
                corner = 8.dp,
            )
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    dayLabel(item.day) + if (item.kind == "entry") " · 随手记" else "",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(2.dp))
                val head = if (item.kind == "entry") {
                    item.place.ifBlank { "笔记" }
                } else {
                    "${fmtTime(item.inAt)} — ${if (item.outAt != null) fmtTime(item.outAt) else "进行中"}"
                }
                Text(head, fontSize = 10.sp, color = Muted)
                if (item.note.isNotBlank()) {
                    Text(item.note, fontSize = 11.sp, maxLines = 2)
                }
            }
            Spacer(Modifier.size(6.dp))
            Pill(
                when (item.match) {
                    "place" -> "地点命中"
                    "day" -> "日期命中"
                    else -> "备注命中"
                },
                Brand,
            )
        }
    }
}

@Composable
fun StatsScreen(viewModel: StatsViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val s = ui.stats

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

        ReportCard(ui = ui, onPeriod = viewModel::setPeriod, onShift = viewModel::shiftPeriod)
        AchievementsCard(ui.achievements)

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

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ReportCard(
    ui: StatsUi,
    onPeriod: (String) -> Unit,
    onShift: (Int) -> Unit,
) {
    val r = ui.report
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("周报 / 月报", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { onPeriod("week") }) {
                Text("本周", color = if (ui.period == "week") Brand else Muted, fontSize = 11.sp)
            }
            TextButton(onClick = { onPeriod("month") }) {
                Text("本月", color = if (ui.period == "month") Brand else Muted, fontSize = 11.sp)
            }
        }

        if (r == null) {
            Spacer(Modifier.height(6.dp))
            Text("加载中…", fontSize = 11.sp, color = Muted)
            return@SectionCard
        }

        Spacer(Modifier.height(2.dp))
        Text(r.label, fontSize = 11.sp, color = Muted)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReportTile(Modifier.weight(1f), "打卡", "${r.days} 天")
            ReportTile(Modifier.weight(1f), "时长", fmtMinutes(r.minutes))
            ReportTile(Modifier.weight(1f), "日均", fmtMinutes(r.avgMinutes))
            ReportTile(Modifier.weight(1f), "达标", if (r.goalMinutes > 0) "${r.goalDays} 天" else "—")
        }

        Spacer(Modifier.height(10.dp))
        Text(
            "上一期 ${r.prevDays} 天 · ${fmtMinutes(r.prevMinutes)}　本期 ${if (r.deltaMinutes > 0) "+" else ""}${r.deltaMinutes} 分钟",
            fontSize = 11.sp,
            color = when {
                r.deltaMinutes > 0 -> Mint
                r.deltaMinutes < 0 -> Danger
                else -> Muted
            },
        )

        Spacer(Modifier.height(10.dp))
        WeekdayBars(r.byWeekday)

        if (r.places.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                "常去：" + r.places.joinToString(" · ") { "${it.name} ${it.days}天" },
                fontSize = 10.sp,
                color = Muted,
                maxLines = 2,
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onShift(-1) }) { Text("‹ 上一期", color = Brand, fontSize = 11.sp) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { onShift(1) }) { Text("下一期 ›", color = Brand, fontSize = 11.sp) }
        }
    }
}

@Composable
private fun ReportTile(modifier: Modifier, label: String, value: String) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 10.sp, color = Muted)
        Text(value, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun WeekdayBars(stats: List<com.studycheck.app.data.WeekdayStat>) {
    if (stats.isEmpty()) return
    val max = stats.maxOfOrNull { it.minutes }?.coerceAtLeast(1L) ?: 1L
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        stats.forEach { w ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.height(48.dp).width(14.dp),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(
                                if (w.minutes > 0) (w.minutes.toFloat() / max).coerceIn(0.08f, 1f) else 0.04f,
                            )
                            .clip(RoundedCornerShape(50))
                            .background(if (w.minutes > 0) Brand else Surface2),
                    )
                }
                Spacer(Modifier.height(3.dp))
                Text(w.label, fontSize = 9.sp, color = Muted)
            }
        }
    }
}

@Composable
private fun AchievementsCard(a: com.studycheck.app.data.AchievementsDto?) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("成就", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            if (a != null) {
                Text("${a.achievements.count { it.unlocked }} / ${a.achievements.size}", fontSize = 11.sp, color = Muted)
            }
        }
        Spacer(Modifier.height(10.dp))

        if (a == null) {
            Text("加载中…", fontSize = 11.sp, color = Muted)
            return@SectionCard
        }

        val unlocked = a.achievements.filter { it.unlocked }
        if (unlocked.isEmpty()) {
            Text("还没有解锁成就，连续打卡 3 天就能拿到第一个", fontSize = 11.sp, color = Muted)
        } else {
            unlocked.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { item ->
                        Surface(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            color = Brand.copy(alpha = 0.10f),
                        ) {
                            Text(
                                item.title,
                                modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp),
                                fontSize = 11.sp,
                                color = Brand,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                            )
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
                Spacer(Modifier.height(6.dp))
            }
        }

        a.next?.let { next ->
            Spacer(Modifier.height(6.dp))
            Text("下一个：${next.title} · ${next.progress}/${next.target}", fontSize = 11.sp, color = Muted)
            Spacer(Modifier.height(5.dp))
            ProgressBar(
                progress = if (next.target > 0) next.progress.toFloat() / next.target else 0f,
            )
        }
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

@Composable
private fun ManualCheckinDialog(
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSubmit: (day: String, inAt: String, outAt: String, place: String, note: String) -> Unit,
) {
    var day by remember { mutableStateOf(todayString()) }
    var inAt by remember { mutableStateOf("09:00") }
    var outAt by remember { mutableStateOf("12:00") }
    var place by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }

    val timeRegex = Regex("""^\d{2}:\d{2}$""")
    val dayRegex = Regex("""^\d{4}-\d{2}-\d{2}$""")

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("手动补卡", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("补上漏打卡的一天，不需要照片", fontSize = 12.sp, color = Muted)
                OutlinedTextField(
                    value = day,
                    onValueChange = { day = it },
                    label = { Text("日期 YYYY-MM-DD") },
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = inAt,
                        onValueChange = { inAt = it },
                        label = { Text("进馆") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = outAt,
                        onValueChange = { outAt = it },
                        label = { Text("出馆") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                OutlinedTextField(
                    value = place,
                    onValueChange = { place = it },
                    label = { Text("地点(可留空)") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("备注(可留空)") },
                    singleLine = true,
                )
                val err = localError ?: error
                if (err != null) Text(err, color = Danger, fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    val d = day.trim()
                    val i = inAt.trim()
                    val o = outAt.trim()
                    when {
                        !dayRegex.matches(d) -> localError = "日期格式应为 YYYY-MM-DD"
                        !timeRegex.matches(i) -> localError = "进馆时间格式应为 HH:MM"
                        o.isNotBlank() && !timeRegex.matches(o) -> localError = "出馆时间格式应为 HH:MM"
                        o.isNotBlank() && o <= i -> localError = "出馆时间需要晚于进馆时间"
                        else -> {
                            localError = null
                            onSubmit(d, i, o, place.trim(), note.trim())
                        }
                    }
                },
            ) { Text(if (busy) "保存中…" else "保存", color = Brand) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("取消", color = Muted) }
        },
    )
}
