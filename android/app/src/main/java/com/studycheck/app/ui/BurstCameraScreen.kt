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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import com.studycheck.app.work.UploadScheduler
import java.io.File

/**
 * 连拍页：按一次快门留一张，可连续拍多张，最后一次性全部上传。
 * 只使用相机，没有相册选择入口。
 */
@Composable
fun BurstCameraScreen(
    onClose: () -> Unit,
    viewModel: BurstViewModel = hiltViewModel(),
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
    val shots = remember { mutableStateListOf<File>() }
    var note by remember { mutableStateOf("") }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasPermission = granted }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (!hasPermission) {
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("需要相机权限才能拍照留存", color = Color.White, fontSize = 14.sp)
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text("授予权限", color = Color.White)
                }
                TextButton(onClick = onClose) { Text("返回", color = Color.White.copy(alpha = 0.7f)) }
            }
            return@Box
        }

        BurstCapture(
            shots = shots,
            saving = saving,
            note = note,
            onNote = { note = it },
            error = error,
            onCancel = onClose,
            onDone = {
                viewModel.submit(shots.toList(), note) {
                    UploadScheduler.enqueue(context)
                    onClose()
                }
            },
        )
    }
}

@Composable
private fun BurstCapture(
    shots: MutableList<File>,
    saving: Boolean,
    note: String,
    onNote: (String) -> Unit,
    error: String?,
    onCancel: () -> Unit,
    onDone: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
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

        // 顶部栏
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onCancel, enabled = !saving) {
                Text("取消", color = Color.White)
            }
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("连拍记录", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                if (shots.isNotEmpty()) {
                    Text("已拍 ${shots.size} 张", color = Color.White.copy(alpha = 0.75f), fontSize = 10.sp)
                }
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDone, enabled = shots.isNotEmpty() && !saving) {
                Text(
                    if (saving) "上传中…" else "完成 ${if (shots.isEmpty()) "" else "(${shots.size})"}",
                    color = if (shots.isEmpty()) Color.White.copy(alpha = 0.4f) else Brand,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        // 底部：笔记 + 胶片条 + 快门
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            OutlinedTextField(
                value = note,
                onValueChange = onNote,
                placeholder = { Text("给这组照片写一句（可选，会一起保存）", fontSize = 11.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            )

            if (shots.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    shots.takeLast(5).forEach { f ->
                        Box {
                            AsyncImage(
                                model = f,
                                contentDescription = "已拍",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(46.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                            )
                            Box(
                                modifier = Modifier
                                    .size(16.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.6f))
                                    .align(Alignment.TopEnd)
                                    .clickable { shots.remove(f); f.delete() },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "删除这张",
                                    tint = Color.White,
                                    modifier = Modifier.size(11.dp),
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            (captureError ?: error)?.let {
                Text(it, color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(bottom = 6.dp))
                if (error != null) Text("照片已保存在手机，联网后补传", color = Brand, fontSize = 10.sp)
            }

            Box(
                modifier = Modifier
                    .size(72.dp)
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
                            if (busy || saving) return@clickable
                            val capture = imageCapture ?: return@clickable
                            busy = true
                            val file = File(context.cacheDir, "burst_${System.currentTimeMillis()}.jpg")
                            val options = ImageCapture.OutputFileOptions.Builder(file).build()
                            capture.takePicture(
                                options,
                                ContextCompat.getMainExecutor(context),
                                object : ImageCapture.OnImageSavedCallback {
                                    override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                                        busy = false
                                        shots.add(file)
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
            Spacer(Modifier.height(6.dp))
            Text(
                "连点快门可连拍，拍完点右上角「完成」一次上传",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 10.sp,
            )
        }
    }
}
