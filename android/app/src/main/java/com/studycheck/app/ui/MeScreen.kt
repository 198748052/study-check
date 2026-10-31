package com.studycheck.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studycheck.app.BuildConfig
import com.studycheck.app.ui.theme.Brand
import com.studycheck.app.ui.theme.Danger
import com.studycheck.app.ui.theme.Mint
import com.studycheck.app.ui.theme.Muted
import com.studycheck.app.data.UpdateState

@Composable
fun MeScreen(
    onSignedOut: () -> Unit,
    viewModel: MeViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    // 委托属性没法智能转换，先取出来再判断
    val updateState = ui.updateState
    var nickname by remember(ui.nickname) { mutableStateOf(ui.nickname) }
    var goal by remember(ui.goalMin) { mutableStateOf(ui.goalMin.toString()) }
    var baseUrl by remember(ui.baseUrl) { mutableStateOf(ui.baseUrl) }
    var toast by remember { mutableStateOf<String?>(null) }
    var showPassword by remember { mutableStateOf(false) }
    var showDeleteAccount by remember { mutableStateOf(false) }
    var resetTarget by remember { mutableStateOf<com.studycheck.app.data.AdminUserDto?>(null) }
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
    val context = androidx.compose.ui.platform.LocalContext.current

    // 进页面先看本地缓存的结论，超过一天才联网查一次
    LaunchedEffect(Unit) { viewModel.checkUpdateIfStale() }

    fun openDownload() {
        try {
            context.startActivity(
                android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(viewModel.openReleasePage()),
                ),
            )
        } catch (_: Exception) {
            toast = "没有可用的浏览器"
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
                if (ui.isAdmin) Pill("管理员", Brand)
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
                title = "打卡明细 CSV（按段）",
                subtitle = "一天多段时每段一行，时间更精确",
                enabled = !ui.exporting,
            ) {
                pendingKind = com.studycheck.app.data.ExportRepository.Kind.SESSIONS
                csvLauncher.launch("studycheck-sessions-$dayTag.csv")
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
            Text("账号安全", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text("改密码后，其它设备上的登录会失效", fontSize = 11.sp, color = Muted)
            Spacer(Modifier.height(10.dp))
            PrimaryButton(
                text = "修改密码",
                onClick = { viewModel.clearPasswordMessage(); showPassword = true },
            )
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showDeleteAccount = true }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("注销账号", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Danger)
                    Text("删除全部数据，不可恢复", fontSize = 10.sp, color = Muted)
                }
                Text(if (ui.deleting) "处理中…" else "去注销", fontSize = 12.sp, color = Danger)
            }
        }

        if (ui.isAdmin) {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("用户管理", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text("忘记密码的账号可以在这里重置", fontSize = 11.sp, color = Muted)
                    }
                    TextButton(onClick = { viewModel.loadUsers() }) {
                        Text("刷新", color = Brand, fontSize = 12.sp)
                    }
                }
                if (ui.users.isEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text("还没有读取到用户列表", fontSize = 11.sp, color = Muted)
                }
                ui.users.forEach { u ->
                    androidx.compose.material3.HorizontalDivider(
                        thickness = 0.6.dp,
                        color = com.studycheck.app.ui.theme.Line,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                (u.nickname.ifBlank { u.username }) + if (u.isAdmin) " · 管理员" else "",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Text("${u.username} · ${u.days} 天", fontSize = 10.sp, color = Muted)
                        }
                        Text(
                            "重置密码",
                            fontSize = 12.sp,
                            color = Brand,
                            modifier = Modifier
                                .clickable { resetTarget = u }
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }

        SectionCard {
            Text("关于", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("版本 v${BuildConfig.VERSION_NAME}", fontSize = 12.sp)
                    Text(
                        updateHint(updateState),
                        fontSize = 11.sp,
                        color = when (updateState) {
                            is UpdateState.Available -> Brand
                            is UpdateState.Failed -> Danger
                            else -> Muted
                        },
                    )
                }
                if (updateState is UpdateState.Checking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = Brand,
                    )
                } else {
                    TextButton(
                        onClick = {
                            if (updateState is UpdateState.Available) openDownload()
                            else viewModel.checkUpdate()
                        },
                    ) {
                        Text(
                            if (updateState is UpdateState.Available) "去更新" else "检查更新",
                            color = Brand,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
            if (updateState is UpdateState.Available) {
                Spacer(Modifier.height(2.dp))
                Text(
                    "新版本 ${updateState.manifest.version} 已发布，点击跳转浏览器下载安装",
                    fontSize = 11.sp,
                    color = Muted,
                )
            }
            Spacer(Modifier.height(6.dp))
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

    if (showPassword) {
        ChangePasswordDialog(
            busy = ui.passwordBusy,
            errorText = ui.passwordMessage,
            onDismiss = { showPassword = false; viewModel.clearPasswordMessage() },
            onSubmit = { oldPw, newPw ->
                viewModel.changePassword(oldPw, newPw) { err ->
                    if (err == null) {
                        showPassword = false
                        toast = "密码已更新，其它设备需要重新登录"
                    }
                }
            },
        )
    }

    if (showDeleteAccount) {
        DeleteAccountDialog(
            busy = ui.deleting,
            onDismiss = { showDeleteAccount = false },
            onSubmit = { pw ->
                viewModel.deleteAccount(pw) { err ->
                    if (err == null) {
                        showDeleteAccount = false
                        onSignedOut()
                    } else {
                        toast = err
                        showDeleteAccount = false
                    }
                }
            },
        )
    }

    resetTarget?.let { target ->
        ResetPasswordDialog(
            username = target.username,
            onDismiss = { resetTarget = null },
            onSubmit = { newPw ->
                viewModel.adminResetPassword(target.id, newPw) { err ->
                    resetTarget = null
                    toast = err ?: "已重置 ${target.username} 的密码"
                }
            },
        )
    }
}

/** 版本检查状态的文案。 */
private fun updateHint(state: UpdateState): String = when (state) {
    is UpdateState.Idle -> "点击检查是否有新版本"
    is UpdateState.Checking -> "正在检查…"
    is UpdateState.UpToDate -> "已是最新版本"
    is UpdateState.Available -> "发现新版本 v${state.manifest.version}"
    is UpdateState.Failed -> "检查失败：${state.reason}"
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
