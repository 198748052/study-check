package com.studycheck.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studycheck.app.BuildConfig
import com.studycheck.app.ui.theme.Brand
import com.studycheck.app.ui.theme.Danger
import com.studycheck.app.ui.theme.Mint
import com.studycheck.app.ui.theme.Muted

@Composable
fun MeScreen(
    onSignedOut: () -> Unit,
    viewModel: MeViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var nickname by remember(ui.nickname) { mutableStateOf(ui.nickname) }
    var goal by remember(ui.goalMin) { mutableStateOf(ui.goalMin.toString()) }
    var baseUrl by remember(ui.baseUrl) { mutableStateOf(ui.baseUrl) }
    var toast by remember { mutableStateOf<String?>(null) }
    var pendingKind by remember {
        mutableStateOf(com.studycheck.app.data.ExportRepository.Kind.CHECKINS)
    }

    // 导出：先让用户选保存位置（系统文件选择器），再下载写入
    val csvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri ->
        if (uri != null) viewModel.exportTo(uri, pendingKind) { toast = it }
    }
    val zipLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri != null) viewModel.exportTo(uri, pendingKind) { toast = it }
    }
    val dayTag = remember { java.time.LocalDate.now().toString().replace("-", "") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Text("我的", fontSize = 24.sp, fontWeight = FontWeight.Bold)

        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        ui.nickname.ifBlank { ui.username.ifBlank { "未登录" } },
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text("@${ui.username}", fontSize = 11.sp, color = Muted)
                }
                Pill("管理端可用", Brand)
            }
        }

        SectionCard {
            Text("个人设置", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = nickname,
                onValueChange = { nickname = it },
                label = { Text("昵称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = goal,
                onValueChange = { goal = it.filter { c -> c.isDigit() }.take(3) },
                label = { Text("每日目标（分钟）") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it; viewModel.setBaseUrl(it) },
                label = { Text("服务器地址") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            PrimaryButton(
                text = if (ui.saving) "保存中…" else "保存",
                enabled = !ui.saving,
                onClick = {
                    toast = null
                    viewModel.save(nickname, goal.toIntOrNull() ?: 120, baseUrl) { m -> toast = m }
                },
            )
        }

        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("每日提醒", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Text("今天没打卡时提醒我", fontSize = 11.sp, color = Muted)
                }
                Switch(
                    checked = ui.reminderEnabled,
                    onCheckedChange = { on -> viewModel.setReminder(on, ui.reminderHour) },
                    colors = SwitchDefaults.colors(checkedThumbColor = androidx.compose.ui.graphics.Color.White, checkedTrackColor = Mint),
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("提醒时间", fontSize = 12.sp, color = Muted)
                Spacer(Modifier.weight(1f))
                TextButton(
                    enabled = ui.reminderEnabled,
                    onClick = { viewModel.setReminder(true, (ui.reminderHour + 23) % 24) },
                ) { Text("−", color = Brand, fontSize = 16.sp) }
                Text("%02d:00".format(ui.reminderHour), fontSize = 15.sp, fontWeight = FontWeight.Medium)
                TextButton(
                    enabled = ui.reminderEnabled,
                    onClick = { viewModel.setReminder(true, (ui.reminderHour + 1) % 24) },
                ) { Text("+", color = Brand, fontSize = 16.sp) }
            }
        }

        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("待上传照片", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Text("离线拍摄的照片会自动补传", fontSize = 11.sp, color = Muted)
                }
                Pill("${ui.pendingCount} 张", if (ui.pendingCount > 0) Brand else Mint)
            }
            Spacer(Modifier.height(10.dp))
            PrimaryButton(
                text = "立即上传",
                enabled = ui.pendingCount > 0 && !ui.saving,
                onClick = { viewModel.uploadNow { m -> toast = m } },
            )
        }

        SectionCard {
            Text("数据导出", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(
                "导成文件存在手机里，可随时备份或换机",
                fontSize = 11.sp,
                color = Muted,
            )
            Spacer(Modifier.height(6.dp))
            ExportRow(
                title = "打卡记录 CSV",
                subtitle = "日期、进出馆时间、时长、地点、备注",
                enabled = !ui.exporting,
            ) {
                pendingKind = com.studycheck.app.data.ExportRepository.Kind.CHECKINS
                csvLauncher.launch("studycheck-checkins-$dayTag.csv")
            }
            ExportRow(
                title = "笔记 CSV",
                subtitle = "每天的笔记与对应的照片文件名",
                enabled = !ui.exporting,
            ) {
                pendingKind = com.studycheck.app.data.ExportRepository.Kind.NOTES
                csvLauncher.launch("studycheck-notes-$dayTag.csv")
            }
            ExportRow(
                title = "全部照片 ZIP",
                subtitle = "打卡照片 + 过程留存照片，按日期命名",
                enabled = !ui.exporting,
                last = true,
            ) {
                pendingKind = com.studycheck.app.data.ExportRepository.Kind.PHOTOS
                zipLauncher.launch("studycheck-photos-$dayTag.zip")
            }
            if (ui.exporting) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = Brand,
                    trackColor = androidx.compose.ui.graphics.Color(0xFFE8E8E8),
                )
                Spacer(Modifier.height(4.dp))
                Text("正在导出，照片较多时需要等一会儿…", fontSize = 10.sp, color = Muted)
            }
            ui.exportNote?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, fontSize = 11.sp, color = Mint)
            }
        }

        toast?.let { Text(it, fontSize = 12.sp, color = Mint) }

        SectionCard {
            Text("关于", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            Text("自习打卡 v${BuildConfig.VERSION_NAME}", fontSize = 12.sp)
            Text("进馆、出馆各拍一张，连续打卡统计", fontSize = 11.sp, color = Muted)
        }

        TextButton(
            onClick = { viewModel.logout(onSignedOut) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("退出登录", color = Danger, fontSize = 13.sp)
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ExportRow(
    title: String,
    subtitle: String,
    enabled: Boolean,
    last: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = if (enabled) Color.Unspecified else Muted,
            )
            Text(subtitle, fontSize = 10.sp, color = Muted)
        }
        Text(
            if (enabled) "导出" else "…",
            fontSize = 12.sp,
            color = if (enabled) Brand else Muted,
            fontWeight = FontWeight.Medium,
        )
    }
    if (!last) {
        androidx.compose.material3.HorizontalDivider(
            thickness = 0.6.dp,
            color = com.studycheck.app.ui.theme.Line,
        )
    }
}
