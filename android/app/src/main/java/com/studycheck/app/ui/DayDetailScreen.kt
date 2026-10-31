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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
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
import com.studycheck.app.ui.theme.Line
import com.studycheck.app.ui.theme.Mint
import com.studycheck.app.ui.theme.Muted
import com.studycheck.app.ui.theme.Surface2
import com.studycheck.app.work.UploadScheduler
import java.io.File

/**
 * 二级页面：当天详情。
 * 图书馆进/出馆打卡在首页（一级页面）直接拍照；
 * 这里放「过程留存」：连拍多张照片 + 文字笔记。
 */
@Composable
fun DayDetailScreen(
    onBack: () -> Unit,
    onOpenBurst: () -> Unit,
    viewModel: DayDetailViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var noteDraft by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<String?>(null) }
    var showFix by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    val checkin = ui.checkin

    Column(modifier = Modifier.fillMaxSize().background(Bg)) {
        // 顶部栏
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = MaterialTheme.colorScheme.onBackground)
            }
            Column(Modifier.weight(1f)) {
                Text("今日详情", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(dayLabel(ui.day) + " · 共 ${ui.synced.size + ui.pending.size} 条", fontSize = 11.sp, color = Muted)
            }
            TextButton(onClick = { viewModel.refresh() }) {
                Text(if (ui.loading) "同步中…" else "刷新", color = Brand, fontSize = 12.sp)
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
                SectionCard { Text("同步失败：$it", color = Danger, fontSize = 12.sp) }
            }

            // 图书馆打卡（时间可手动修正）
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("图书馆打卡", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.weight(1f))
                    if (checkin != null && checkin.inAt != null) {
                        TextButton(onClick = { showFix = true }) {
                            Text("修正时间", color = Brand, fontSize = 12.sp)
                        }
                    }
                }
                if (checkin == null || checkin.inAt == null) {
                    Text("今天还没有打卡，回首页拍照进馆", fontSize = 11.sp, color = Muted)
                } else {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "进馆 ${fmtTime(checkin.inAt)} · 出馆 ${fmtTime(checkin.outAt)}",
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (checkin.outAt == null) {
                            Pill("在馆中", Mint)
                        } else {
                            Pill(fmtDuration(checkin.durationSec), Mint)
                        }
                        Text(
                            "时间记错了可以手动修正",
                            fontSize = 10.sp,
                            color = Muted,
                            modifier = Modifier.align(Alignment.CenterVertically),
                        )
                    }
                }
            }

            toast?.let { Text(it, fontSize = 11.sp, color = Mint) }

            // 连拍入口
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("拍照留存过程", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "进入相机后可连拍多张，一次性上传",
                            fontSize = 11.sp,
                            color = Muted,
                        )
                    }
                    Spacer(Modifier.size(8.dp))
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) {
                        PrimaryButton(text = "连拍记录", onClick = onOpenBurst)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text("仅支持相机拍摄，不支持从相册选择。", fontSize = 10.sp, color = Muted)
            }

            // 笔记
            SectionCard {
                Text("今日笔记", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = noteDraft,
                    onValueChange = { noteDraft = it },
                    placeholder = { Text("今天学了什么、卡在哪、明天的计划…", fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth().height(110.dp),
                    shape = RoundedCornerShape(12.dp),
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${noteDraft.length} 字", fontSize = 10.sp, color = Muted)
                    Spacer(Modifier.weight(1f))
                    Box(Modifier.weight(1f)) {
                        PrimaryButton(
                            text = if (ui.savingNote) "保存中…" else "保存笔记",
                            enabled = noteDraft.isNotBlank() && !ui.savingNote,
                            onClick = {
                                viewModel.saveNote(noteDraft) { noteDraft = "" }
                                UploadScheduler.enqueue(context)
                            },
                        )
                    }
                }
            }

            // 记录列表（照片 + 笔记混合，按时间倒序）
            if (ui.synced.isEmpty() && ui.pending.isEmpty()) {
                EmptyBox("今天还没有留存记录，点上面「连拍记录」开始")
            } else {
                if (ui.pending.isNotEmpty()) {
                    SectionCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Pill("${ui.pending.size} 张待上传", Brand)
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = {
                                viewModel.refresh()
                                UploadScheduler.enqueue(context)
                            }) { Text("立即重试", color = Brand, fontSize = 11.sp) }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ui.pending.take(6).forEach { p ->
                                Box {
                                    ThumbSquare(
                                        url = File(p.photoPath).absolutePath,
                                        size = 64.dp,
                                        corner = 8.dp,
                                        badge = "待传",
                                    )
                                    IconButton(
                                        onClick = { viewModel.deletePending(p.id) },
                                        modifier = Modifier.size(20.dp).align(Alignment.TopEnd),
                                    ) {
                                        Icon(
                                            Icons.Filled.Delete,
                                            contentDescription = "删除",
                                            tint = Color.White,
                                            modifier = Modifier.size(14.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                ui.synced.forEach { e ->
                    EntryRow(
                        thumbUrl = mediaUrl(ui.baseUrl, e.thumb),
                        photoUrl = mediaUrl(ui.baseUrl, e.photo),
                        note = e.note,
                        time = fmtTime(e.createdAt),
                        onPreview = { preview = mediaUrl(ui.baseUrl, e.photo) },
                        onDelete = { viewModel.deleteSynced(e.serverId) },
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showFix && checkin != null && checkin.inAt != null) {
        FixTimesDialog(
            day = ui.day,
            inAt = checkin.inAt,
            outAt = checkin.outAt,
            subjectEditable = false,
            onDismiss = { showFix = false },
            onSave = { i, o, _ ->
                showFix = false
                viewModel.fixTimes(checkin.serverId, i, o) { err ->
                    toast = err ?: "已修正打卡时间"
                }
            },
        )
    }

    // 全屏预览
    preview?.let { url ->
        Dialog(onDismissRequest = { preview = null }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black),
            ) {
                AsyncImage(
                    model = url,
                    contentDescription = "预览",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().aspectRatio(3f / 4f),
                )
                TextButton(
                    onClick = { preview = null },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("关闭", color = Color.White) }
            }
        }
    }
}

@Composable
private fun EntryRow(
    thumbUrl: String?,
    photoUrl: String?,
    note: String,
    time: String,
    onPreview: () -> Unit,
    onDelete: () -> Unit,
) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (thumbUrl != null) {
                ThumbSquare(url = thumbUrl, size = 56.dp, corner = 8.dp, onClick = onPreview)
                Spacer(Modifier.size(10.dp))
            }
            Column(Modifier.weight(1f)) {
                if (note.isNotBlank()) {
                    Text(note, fontSize = 12.sp, maxLines = 3)
                } else {
                    Text("（只有照片）", fontSize = 12.sp, color = Muted)
                }
                Spacer(Modifier.height(2.dp))
                Text(time, fontSize = 10.sp, color = Muted)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "删除", tint = Muted, modifier = Modifier.size(18.dp))
            }
        }
    }
}
