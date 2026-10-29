package com.studycheck.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.studycheck.app.ui.theme.Brand
import com.studycheck.app.ui.theme.Danger
import com.studycheck.app.ui.theme.Mint
import com.studycheck.app.ui.theme.Muted
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(
    viewModel: LoginViewModel = hiltViewModel(),
    onLoggedIn: () -> Unit,
    onSkipLogin: () -> Unit = onLoggedIn,
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var nickname by remember { mutableStateOf("") }
    var registerMode by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var baseUrl by remember { mutableStateOf(viewModel.baseUrl()) }
    // 没配默认服务器地址时，直接展开地址输入框，引导用户先填地址
    var showServer by remember { mutableStateOf(baseUrl.isBlank()) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(72.dp))
        Text("📚", fontSize = 44.sp)
        Spacer(Modifier.height(12.dp))
        Text(
            "自习打卡",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "进馆拍一张，出馆拍一张",
            fontSize = 13.sp,
            color = Muted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))

        SectionCard {
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("用户名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("密码") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            )
            if (registerMode) {
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = nickname,
                    onValueChange = { nickname = it },
                    label = { Text("昵称（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(16.dp))
            if (busy) {
                CircularProgressIndicator(color = Brand, modifier = Modifier.height(28.dp))
            } else {
                PrimaryButton(
                    text = if (registerMode) "注册并登录" else "登录",
                    onClick = {
                        scope.launch {
                            busy = true
                            error = null
                            val r = if (registerMode) {
                                viewModel.register(username, password, nickname)
                            } else {
                                viewModel.login(username, password)
                            }
                            busy = false
                            r.onSuccess { onLoggedIn() }.onFailure { error = it.friendly() }
                        }
                    },
                )
            }
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = { registerMode = !registerMode }, modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (registerMode) "已有账号，去登录" else "首次使用？创建账号",
                    color = Brand,
                    fontSize = 13.sp,
                )
            }
        }

        error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = Danger, fontSize = 12.sp, textAlign = TextAlign.Center)
        }

        Spacer(Modifier.height(20.dp))
        TextButton(onClick = { showServer = !showServer }) {
            Text(
                if (baseUrl.isBlank()) "未设置服务器地址，点这里填写" else "服务器：$baseUrl",
                color = if (baseUrl.isBlank()) Brand else Muted,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
            )
        }
        if (showServer) {
            OutlinedTextField(
                value = baseUrl,
                onValueChange = {
                    baseUrl = it
                    viewModel.setBaseUrl(it)
                },
                label = { Text("服务器地址") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "填你部署好的服务器地址，例如 https://study.example.com/（模拟器本机调试用 http://10.0.2.2:8080/）",
                fontSize = 11.sp,
                color = Muted,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            if (registerMode) "第一个注册的账号自动成为管理员" else "演示账号 demo / demo1234",
            fontSize = 11.sp,
            color = Mint,
        )
        Spacer(Modifier.height(4.dp))
        // 后端开了 SC_DEV_NO_AUTH 时可直接进入，跳过登录（联调用）
        TextButton(onClick = onSkipLogin) {
            Text("跳过登录，直接进入（测试）", color = Muted, fontSize = 12.sp)
        }
        Spacer(Modifier.height(48.dp))
    }
}
