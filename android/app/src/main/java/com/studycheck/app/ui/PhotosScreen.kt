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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.window.Dialog
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import coil.compose.AsyncImage
import com.studycheck.app.data.PhotoDayDto
import com.studycheck.app.data.PhotoItemDto
import com.studycheck.app.ui.theme.Brand
import com.studycheck.app.ui.theme.Card
import com.studycheck.app.ui.theme.Ink
import com.studycheck.app.ui.theme.Line
import com.studycheck.app.ui.theme.Muted
import com.studycheck.app.ui.theme.Surface2
import java.time.YearMonth

/**
 * 照片时间轴：一个月一个月份组，组内按天、天内按时刻排好。
 * 点缩略图看大图（复用相册里那套全屏预览）。
 */
@Composable
fun PhotosScreen(viewModel: PhotosViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var preview by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("照片", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(
                    ui.data.let { "${it.count} 张 · ${it.month.replace('-', '/')}" },
                    fontSize = 12.sp,
                    color = Muted,
                )
            }
            TextButton(onClick = { viewModel.reload() }) { Text("刷新", color = Brand, fontSize = 12.sp) }
        }

        // 翻月
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { viewModel.shift(-1) }) { Text("上月", color = Brand, fontSize = 12.sp) }
            Text(
                ui.data.month.replace('-', '/'),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = Ink,
            )
            TextButton(
                onClick = { viewModel.shift(1) },
                enabled = !ui.data.nextMonth.isBlank(),
            ) { Text("下月", color = Brand, fontSize = 12.sp) }
        }

        when {
            ui.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Brand)
            }

            ui.data.days.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("这个月还没有照片", fontSize = 13.sp, color = Muted)
                    Spacer(Modifier.height(6.dp))
                    Text("打卡、连拍、随手记的照片都会出现在这里", fontSize = 11.sp, color = Muted)
                }
            }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(ui.data.days, key = { it.day }) { day ->
                    PhotoDayCard(day, ui.baseUrl) { url -> preview = url }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }

    preview?.let { url ->
        PhotoPreview(url) { preview = null }
    }
}

@Composable
private fun PhotoDayCard(day: PhotoDayDto, baseUrl: String, onOpen: (String) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                day.day,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = Ink,
            )
            Spacer(Modifier.weight(1f))
            Text("${day.count} 张", fontSize = 10.sp, color = Muted)
        }
        Spacer(Modifier.height(8.dp))
        // 三列网格，竖图横图都不拉伸
        val rows = day.items.chunked(3)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            rows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { item ->
                        Box(Modifier.weight(1f)) {
                            PhotoCell(item, baseUrl, onOpen)
                        }
                    }
                    repeat(3 - row.size) { Box(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun PhotoCell(item: PhotoItemDto, baseUrl: String, onOpen: (String) -> Unit) {
    val full = mediaUrl(baseUrl, item.photoUrl.ifBlank { null })
    val thumb = mediaUrl(baseUrl, item.thumbUrl.ifBlank { item.photoUrl.ifBlank { null } })
    val time = item.at.takeLast(14).take(5)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(Surface2)
            .clickable(enabled = full != null) { full?.let(onOpen) },
    ) {
        if (thumb != null) {
            AsyncImage(
                model = thumb,
                contentDescription = item.kind,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("无图", fontSize = 10.sp, color = Muted)
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Card.copy(alpha = 0.82f))
                .padding(horizontal = 5.dp, vertical = 2.dp),
        ) {
            Text(
                text = labelOf(item),
                fontSize = 9.sp,
                color = Muted,
            )
        }
        if (time.isNotBlank()) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Card.copy(alpha = 0.82f))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            ) {
                Text(time, fontSize = 9.sp, color = Muted)
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(1.dp)
                .background(Line.copy(alpha = 0.3f)),
        )
    }
}

private fun labelOf(item: PhotoItemDto): String = when (item.kind) {
    "in" -> "进馆"
    "out" -> "出馆"
    else -> item.subject.ifBlank { item.note.ifBlank { "随手拍" } }.take(4)
}

/** 全屏预览（点时间轴里的缩略图） */
@Composable
private fun PhotoPreview(url: String, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose) {
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
            TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                Text("关闭", color = Color.White)
            }
        }
    }
}

/** 当前月份（YYYY-MM）默认取今天所在月 */
fun currentMonth(): String = YearMonth.now().toString()
