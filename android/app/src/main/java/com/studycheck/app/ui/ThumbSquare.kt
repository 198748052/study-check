package com.studycheck.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.studycheck.app.ui.theme.Muted
import com.studycheck.app.ui.theme.Surface2

/**
 * 统一的小方图缩略图：首页/历史/详情都用它，避免大图占满屏幕。
 * url 可传 http 地址（String）或本地 File，Coil 都能直接加载。
 */
@Composable
fun ThumbSquare(
    url: Any?,
    size: Dp = 72.dp,
    label: String = "",
    corner: Dp = 10.dp,
    onClick: (() -> Unit)? = null,
    badge: String? = null,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(Surface2)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = label,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(label.ifBlank { "无图" }, fontSize = 10.sp, color = Muted)
        }
        if (badge != null) {
            Box(Modifier.fillMaxSize()) {
                Pill(
                    text = badge,
                    color = Muted,
                    bg = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.align(Alignment.BottomStart),
                )
            }
        }
    }
}
