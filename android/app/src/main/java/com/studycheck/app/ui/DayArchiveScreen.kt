package com.studycheck.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.studycheck.app.ui.theme.Bg
import com.studycheck.app.ui.theme.Brand
import com.studycheck.app.ui.theme.Danger
import com.studycheck.app.ui.theme.Mint
import com.studycheck.app.ui.theme.Muted
import com.studycheck.app.ui.theme.Surface2

/**
 * 历史某一天的详情：打卡照片 + 备注 + 当天的过程留存（照片/笔记）。
 * 从「历史」列表点任意一天进入，照片点击可看大图。
 */
@Composable
fun DayArchiveScreen(
    onBack: () -> Unit,
    viewModel: DayArchiveViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var preview by remember { mutableStateOf<String?>(null) }
    var showFix by remember { mutableStateOf(false) }
    var fixingSession by remember { mutableStateOf<com.studycheck.app.data.SessionEntity?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }
    val c = ui.checkin
    val photos = ui.entries.count { !it.photo.isNullOrBlank() } +
        listOfNotNull(c?.inPhoto, c?.outPhoto).size

    Column(modifier = Modifier.fillMaxSize().background(Bg)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(dayLabel(ui.day), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text("$photos 张照片 · ${ui.entries.size} 条记录", fontSize = 11.sp, color = Muted)
            }
            TextButton(onClick = { viewModel.refresh() }) {
                Text(if (ui.loading) "加载中…" else "刷新", color = Brand, fontSize = 12.sp)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ui.error?.let {
                SectionCard { Text("加载详情失败：$it", color = Danger, fontSize = 12.sp) }
            }

            toast?.let {
                Text(it, fontSize = 11.sp, color = Mint)
            }

            // 打卡本身
            if (c == null) {
                EmptyBox("这一天没有打卡记录")
            } else {
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("图书馆打卡", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.weight(1f))
                        if (c.outAt == null) {
                            Pill("未出馆", Brand)
                        } else {
                            Pill(fmtDuration(c.durationSec), Mint)
                        }
                        TextButton(onClick = { showFix = true }) {
                            Text("修正时间", color = Brand, fontSize = 11.sp)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CheckinPhoto(
                            modifier = Modifier.weight(1f),
                            title = "进馆 ${fmtTime(c.inAt)}",
                            place = c.inPlace,
                            note = c.inNote,
                            thumb = mediaUrl(ui.baseUrl, c.inThumb ?: c.inPhoto),
                            full = mediaUrl(ui.baseUrl, c.inPhoto),
                            onOpen = { preview = it },
                        )
                        CheckinPhoto(
                            modifier = Modifier.weight(1f),
                            title = "出馆 ${fmtTime(c.outAt)}",
                            place = c.outPlace,
                            note = c.outNote,
                            thumb = mediaUrl(ui.baseUrl, c.outThumb ?: c.outPhoto),
                            full = mediaUrl(ui.baseUrl, c.outPhoto),
                            onOpen = { preview = it },
                        )
                    }
                }
            }

            // 当天分段（一天可以多段）
            if (ui.sessions.size > 1) {
                Text(
                    "当天分段（${ui.sessions.size}）",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                ui.sessions.forEachIndexed { index, ss ->
                    SectionCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${index + 1} · ${fmtTime(ss.inAt)} — ${if (ss.outAt != null) fmtTime(ss.outAt) else "进行中"}",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Spacer(Modifier.weight(1f))
                            Pill(
                                if (ss.outAt != null) fmtDuration(ss.durationSec) else "计时中",
                                if (ss.outAt != null) Mint else Brand,
                            )
                        }
                        val note = listOf(ss.inNote, ss.outNote).filter { it.isNotBlank() }.joinToString(" · ")
                        if (note.isNotBlank() || ss.inPlace.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                listOf(note, ss.inPlace.ifBlank { ss.outPlace }).filter { it.isNotBlank() }
                                    .joinToString(" · "),
                                fontSize = 11.sp,
                                color = Muted,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            TextButton(onClick = { fixingSession = ss }) {
                                Text("修正这一段", color = Brand, fontSize = 11.sp)
                            }
                            TextButton(onClick = { viewModel.deleteSession(ss.serverId) }) {
                                Text("删除这一段", color = Danger, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }

            // 过程留存
            Text(
                "过程留存（${ui.entries.size}）",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (ui.entries.isEmpty()) {
                EmptyBox("这一天没有留存照片或笔记")
            } else {
                ui.entries.forEach { e ->
                    ArchiveEntryRow(
                        thumb = mediaUrl(ui.baseUrl, e.thumb ?: e.photo),
                        full = mediaUrl(ui.baseUrl, e.photo),
                        note = e.note,
                        time = fmtTime(e.createdAt),
                        onOpen = { preview = it },
                    )
                }
            }

            Spacer(Modifier.height(28.dp))
        }
    }

    if (showFix && c != null && c.inAt != null) {
        FixTimesDialog(
            day = ui.day,
            inAt = c.inAt,
            outAt = c.outAt,
            subjectEditable = false,
            onDismiss = { showFix = false },
            onSave = { i, o, _ ->
                showFix = false
                viewModel.fixTimes(c.serverId, i, o) { err ->
                    toast = err ?: "已修正打卡时间，统计会同步更新"
                }
            },
        )
    }

    fixingSession?.let { ss ->
        FixTimesDialog(
            day = ss.day,
            inAt = ss.inAt,
            outAt = ss.outAt,
            subject = ss.subject,
            onDismiss = { fixingSession = null },
            onSave = { i, o, sub ->
                val target = ss
                fixingSession = null
                viewModel.fixSession(target.serverId, i, o, sub) { err ->
                    toast = err ?: "已修正这一段"
                }
            },
        )
    }

    preview?.let { url ->
        Dialog(onDismissRequest = { preview = null }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black)
                    .clickable { preview = null },
            ) {
                AsyncImage(
                    model = url,
                    contentDescription = "照片",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().height(420.dp),
                )
                Text(
                    "点击任意处关闭",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 11.sp,
                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                )
            }
        }
    }
}

@Composable
private fun CheckinPhoto(
    modifier: Modifier = Modifier,
    title: String,
    place: String,
    note: String,
    thumb: String?,
    full: String?,
    onOpen: (String) -> Unit,
) {
    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(96.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Surface2)
                .then(
                    if (full != null) {
                        Modifier.clickable { onOpen(full) }
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (thumb != null) {
                AsyncImage(
                    model = thumb,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text("无照片", fontSize = 10.sp, color = Muted)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(title, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        if (place.isNotBlank()) {
            Text(place, fontSize = 10.sp, color = Muted, maxLines = 1)
        }
        if (note.isNotBlank()) {
            Text(note, fontSize = 10.sp, color = Muted, maxLines = 3)
        }
    }
}

@Composable
private fun ArchiveEntryRow(
    thumb: String?,
    full: String?,
    note: String,
    time: String,
    onOpen: (String) -> Unit,
) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (thumb != null) {
                ThumbSquare(
                    url = thumb,
                    size = 60.dp,
                    corner = 9.dp,
                    onClick = { full?.let(onOpen) },
                )
                Spacer(Modifier.size(10.dp))
            }
            Column(Modifier.weight(1f)) {
                if (note.isNotBlank()) {
                    Text(note, fontSize = 12.sp, maxLines = 4)
                } else {
                    Text(
                        if (thumb != null) "点图看大图" else "（空记录）",
                        fontSize = 11.sp,
                        color = Muted,
                    )
                }
                Spacer(Modifier.height(3.dp))
                Text(time, fontSize = 10.sp, color = Muted)
            }
        }
    }
}
