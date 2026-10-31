package com.studycheck.app.ui

import android.app.TimePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.studycheck.app.ui.theme.Brand
import com.studycheck.app.ui.theme.Danger
import com.studycheck.app.ui.theme.Line
import com.studycheck.app.ui.theme.Mint
import com.studycheck.app.ui.theme.Muted
import com.studycheck.app.ui.theme.Surface2
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val ISO_SECONDS: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")

private fun parseLocalTime(iso: String?): LocalTime? = try {
    iso?.takeIf { it.isNotBlank() }?.let { OffsetDateTime.parse(it).toLocalTime() }?.withSecond(0)?.withNano(0)
} catch (t: Throwable) {
    null
}

private fun buildIso(day: String, time: LocalTime): String =
    LocalDate.parse(day).atTime(time).atZone(ZoneId.systemDefault()).toOffsetDateTime()
        .format(ISO_SECONDS)

/**
 * 手动修正进馆/出馆时间。
 * 用于「忘了拍出馆」或「时间点错了」的场景；出馆可留空表示还没走。
 */
@Composable
fun FixTimesDialog(
    day: String,
    inAt: String?,
    outAt: String?,
    subject: String = "",
    subjectEditable: Boolean = true,
    onDismiss: () -> Unit,
    onSave: (inIso: String, outIso: String?, subject: String) -> Unit,
) {
    val context = LocalContext.current
    var inTime by remember { mutableStateOf(parseLocalTime(inAt) ?: LocalTime.of(9, 0)) }
    var outTime by remember { mutableStateOf(parseLocalTime(outAt) ?: LocalTime.of(12, 0)) }
    var stillIn by remember { mutableStateOf(outAt == null) }
    var subjectText by remember { mutableStateOf(subject) }
    var error by remember { mutableStateOf<String?>(null) }

    fun pick(initial: LocalTime, onPicked: (LocalTime) -> Unit) {
        TimePickerDialog(
            context,
            { _, h, m -> onPicked(LocalTime.of(h, m)) },
            initial.hour,
            initial.minute,
            true,
        ).show()
    }

    Dialog(onDismissRequest = onDismiss) {
        OutlinedCard(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp)) {
                Text("修正打卡时间", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(dayLabel(day), fontSize = 11.sp, color = Muted)
                Spacer(Modifier.height(14.dp))

                TimeField(
                    label = "进馆时间",
                    value = "%02d:%02d".format(inTime.hour, inTime.minute),
                    onClick = { pick(inTime) { inTime = it } },
                )
                Spacer(Modifier.height(10.dp))
                TimeField(
                    label = "出馆时间",
                    value = if (stillIn) "尚未出馆" else "%02d:%02d".format(outTime.hour, outTime.minute),
                    enabled = !stillIn,
                    onClick = { pick(outTime) { outTime = it } },
                )

                if (subjectEditable) {
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Surface2)
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("科目", fontSize = 12.sp, color = Muted)
                        Spacer(Modifier.width(10.dp))
                        androidx.compose.foundation.text.BasicTextField(
                            value = subjectText,
                            onValueChange = { if (it.length <= 20) subjectText = it },
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Medium,
                            ),
                            modifier = Modifier.weight(1f),
                            decorationBox = { inner ->
                                if (subjectText.isEmpty()) {
                                    Text("如数学、英语（可留空）", fontSize = 14.sp, color = Muted)
                                }
                                inner()
                            },
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("还没出馆", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        Text("勾选后清除出馆时间，继续计时", fontSize = 10.sp, color = Muted)
                    }
                    Switch(
                        checked = stillIn,
                        onCheckedChange = { stillIn = it; error = null },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Mint,
                        ),
                    )
                }

                if (stillIn) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "提示：清空出馆后，时长会重新按实时计算。",
                        fontSize = 10.sp,
                        color = Muted,
                    )
                }

                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = Danger, fontSize = 11.sp)
                }

                Spacer(Modifier.height(14.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(onClick = onDismiss) { Text("取消", color = Muted) }
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = {
                            if (!stillIn && !outTime.isAfter(inTime)) {
                                error = "出馆时间要晚于进馆时间"
                                return@TextButton
                            }
                            error = null
                            onSave(
                                buildIso(day, inTime),
                                if (stillIn) "" else buildIso(day, outTime),
                                subjectText.trim(),
                            )
                        },
                    ) {
                        Text("保存", color = Brand, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun TimeField(
    label: String,
    value: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Surface2)
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 12.sp, color = Muted)
        Spacer(Modifier.weight(1f))
        Text(
            value,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) com.studycheck.app.ui.theme.Ink else Muted,
        )
        Spacer(Modifier.height(1.dp))
        Text(if (enabled) "  点击修改" else "", fontSize = 10.sp, color = Brand)
    }
}
