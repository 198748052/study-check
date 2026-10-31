package com.studycheck.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.studycheck.app.ui.theme.Brand
import com.studycheck.app.ui.theme.Danger
import com.studycheck.app.ui.theme.Muted

/**
 * 修改密码。改完服务端会作废其它设备的登录，本机换成新 token（由 ViewModel 处理）。
 */
@Composable
fun ChangePasswordDialog(
    busy: Boolean,
    errorText: String?,
    onDismiss: () -> Unit,
    onSubmit: (oldPw: String, newPw: String) -> Unit,
) {
    var oldPw by remember { mutableStateOf("") }
    var newPw by remember { mutableStateOf("") }
    var newPw2 by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        OutlinedCard(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp)) {
                Text("修改密码", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("改完其它设备需要重新登录", fontSize = 11.sp, color = Muted)
                Spacer(Modifier.height(14.dp))

                PwField("当前密码", oldPw) { oldPw = it; localError = null }
                Spacer(Modifier.height(8.dp))
                PwField("新密码（至少 6 位）", newPw) { newPw = it; localError = null }
                Spacer(Modifier.height(8.dp))
                PwField("再输一次新密码", newPw2) { newPw2 = it; localError = null }

                val msg = localError ?: errorText
                if (msg != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(msg, color = Danger, fontSize = 11.sp)
                }

                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(onClick = onDismiss) { Text("取消", color = Muted) }
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        enabled = !busy,
                        onClick = {
                            when {
                                newPw.length < 6 -> localError = "新密码至少 6 位"
                                newPw != newPw2 -> localError = "两次输入的新密码不一致"
                                oldPw.isBlank() -> localError = "请先输入当前密码"
                                else -> {
                                    localError = null
                                    onSubmit(oldPw, newPw)
                                }
                            }
                        },
                    ) {
                        Text(if (busy) "提交中…" else "确认修改", color = Brand, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

/**
 * 注销账号：要输密码二次确认，且提示不可恢复。
 */
@Composable
fun DeleteAccountDialog(
    busy: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (password: String) -> Unit,
) {
    var pw by remember { mutableStateOf("") }
    var confirmText by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        OutlinedCard(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp)) {
                Text("注销账号", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Danger)
                Spacer(Modifier.height(4.dp))
                Text(
                    "会删掉这个账号的全部打卡、笔记和照片，无法恢复。",
                    fontSize = 11.sp,
                    color = Muted,
                )
                Spacer(Modifier.height(12.dp))
                PwField("输入密码", pw) { pw = it; localError = null }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = confirmText,
                    onValueChange = { confirmText = it; localError = null },
                    label = { Text("输入「注销」两个字确认") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (localError != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(localError!!, color = Danger, fontSize = 11.sp)
                }
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onDismiss) { Text("取消", color = Muted) }
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        enabled = !busy,
                        onClick = {
                            when {
                                pw.isBlank() -> localError = "请输入密码"
                                confirmText.trim() != "注销" -> localError = "请输入「注销」两个字"
                                else -> onSubmit(pw)
                            }
                        },
                    ) {
                        Text(if (busy) "处理中…" else "确认注销", color = Danger, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

/** 管理员给某个账号重置密码（忘记密码兜底） */
@Composable
fun ResetPasswordDialog(
    username: String,
    onDismiss: () -> Unit,
    onSubmit: (newPw: String) -> Unit,
) {
    var pw by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        OutlinedCard(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp)) {
                Text("重置密码", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("@$username", fontSize = 11.sp, color = Muted)
                Spacer(Modifier.height(12.dp))
                PwField("新密码（至少 6 位）", pw) { pw = it; localError = null }
                if (localError != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(localError!!, color = Danger, fontSize = 11.sp)
                }
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onDismiss) { Text("取消", color = Muted) }
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = {
                            if (pw.length < 6) localError = "密码至少 6 位" else onSubmit(pw)
                        },
                    ) {
                        Text("确认重置", color = Brand, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun PwField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
}
