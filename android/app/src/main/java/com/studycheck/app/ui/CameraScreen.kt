package com.studycheck.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.studycheck.app.ui.theme.Brand
import com.studycheck.app.ui.theme.Danger
import com.studycheck.app.ui.theme.Mint
import com.studycheck.app.ui.theme.Muted
import com.studycheck.app.work.UploadScheduler
import java.io.File

@Composable
fun CameraScreen(
    kind: String,
    onClose: () -> Unit,
    viewModel: CheckinSubmitViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val saving by viewModel.saving.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var captured by remember { mutableStateOf<File?>(null) }
    var note by remember { mutableStateOf("") }
    var place by remember { mutableStateOf("图书馆") }
    // 科目：只有进馆需要；默认带出上次用的那个
    val submitVmStore = viewModel.store
    var subject by remember { mutableStateOf(if (kind == "in") submitVmStore.recentSubjects.firstOrNull() ?: "" else "") }
    val recentSubjects = remember { submitVmStore.recentSubjects }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasPermission = granted }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        when {
            !hasPermission -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("需要相机权限才能拍照打卡", color = Color.White, fontSize = 14.sp)
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                        Text("授予权限", color = Color.White)
                    }
                    TextButton(onClick = onClose) { Text("返回", color = Color.White.copy(alpha = 0.7f)) }
                }
            }

            captured != null -> {
                ConfirmPane(
                    file = captured!!,
                    kind = kind,
                    saving = saving,
                    error = error,
                    note = note,
                    place = place,
                    subject = subject,
                    recentSubjects = recentSubjects,
                    onNote = { note = it },
                    onPlace = { place = it },
                    onSubject = { subject = it },
                    onRetake = { captured = null },
                    onConfirm = {
                        val file = captured ?: return@ConfirmPane
                        viewModel.submit(kind, file, note, place, subject) {
                            if (kind == "in") submitVmStore.rememberSubject(subject)
                            UploadScheduler.enqueue(context)
                            onClose()
                        }
                    },
                    onClose = onClose,
                )
            }

            else -> {
                CameraCapture(
                    onCaptured = { captured = it },
                    onClose = onClose,
                    kind = kind,
                )
            }
        }
    }
}

@Composable
private fun CameraCapture(
    onCaptured: (File) -> Unit,
    onClose: () -> Unit,
    kind: String,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var captureError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    val future = ProcessCameraProvider.getInstance(ctx)
                    future.addListener({
                        try {
                            val provider = future.get()
                            val preview = Preview.Builder().build()
                                .also { it.surfaceProvider = surfaceProvider }
                            val capture = ImageCapture.Builder()
                                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                                .build()
                            provider.unbindAll()
                            provider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                capture,
                            )
                            imageCapture = capture
                        } catch (t: Throwable) {
                            captureError = "相机启动失败：${t.message}"
                        }
                    }, ContextCompat.getMainExecutor(ctx))
                }
            },
        )

        // 顶部工具条
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onClose) { Text("取消", color = Color.White) }
            Spacer(Modifier.weight(1f))
            Text(
                if (kind == "in") "拍摄进馆照片" else "拍摄出馆照片",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.size(56.dp))
        }

        // 底部快门
        Column(
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            captureError?.let {
                Text(it, color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(8.dp))
            }
            Box(
                modifier = Modifier
                    .size(74.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.25f)),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(58.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            if (busy) return@clickable
                            val capture = imageCapture ?: return@clickable
                            busy = true
                            val file = File(context.cacheDir, "shot_${System.currentTimeMillis()}.jpg")
                            val options = ImageCapture.OutputFileOptions.Builder(file).build()
                            capture.takePicture(
                                options,
                                ContextCompat.getMainExecutor(context),
                                object : ImageCapture.OnImageSavedCallback {
                                    override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                                        busy = false
                                        onCaptured(file)
                                    }

                                    override fun onError(exception: ImageCaptureException) {
                                        busy = false
                                        captureError = "拍照失败：${exception.message}"
                                    }
                                },
                            )
                        },
                ) {
                    if (busy) CircularProgressIndicator(color = Brand, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("对准后点按拍照", color = Color.White.copy(alpha = 0.75f), fontSize = 11.sp)
        }
    }
}

@Composable
private fun ConfirmPane(
    file: File,
    kind: String,
    saving: Boolean,
    error: String?,
    note: String,
    place: String,
    subject: String,
    recentSubjects: List<String>,
    onNote: (String) -> Unit,
    onPlace: (String) -> Unit,
    onSubject: (String) -> Unit,
    onRetake: () -> Unit,
    onConfirm: () -> Unit,
    onClose: () -> Unit,
) {
    val focusManager = LocalFocusManager.current

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            AsyncImage(
                model = file,
                contentDescription = "预览",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(com.studycheck.app.ui.theme.Card)
                // 键盘弹出、屏幕又矮时面板内容可以滚，保证输入框始终可见
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text(
                if (kind == "in") "确认进馆打卡" else "确认出馆打卡",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = place,
                onValueChange = onPlace,
                label = { Text("地点") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )
            if (kind == "in") {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = subject,
                    onValueChange = onSubject,
                    label = { Text("科目（可选）") },
                    singleLine = true,
                    placeholder = { Text("如数学、英语") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )
                if (recentSubjects.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        recentSubjects.take(4).forEach { name ->
                            val selected = name == subject
                            Text(
                                name,
                                fontSize = 11.sp,
                                color = if (selected) Color.White else Brand,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(if (selected) Brand else Brand.copy(alpha = 0.12f))
                                    .clickable { onSubject(if (selected) "" else name) }
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = note,
                onValueChange = onNote,
                label = { Text("备注（可选）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            )
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = Danger, fontSize = 12.sp)
            }
            Spacer(Modifier.height(12.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onRetake, enabled = !saving) { Text("重拍", color = Muted) }
                TextButton(onClick = onClose, enabled = !saving) { Text("取消", color = Muted) }
                Spacer(Modifier.weight(1f))
                Box(modifier = Modifier.weight(1f)) {
                    PrimaryButton(
                        text = if (saving) "保存中…" else "确认打卡",
                        onClick = onConfirm,
                        enabled = !saving,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "照片会先存在手机里，网络不好也会自动补传。",
                fontSize = 10.sp,
                color = Mint,
            )
        }
    }
}
