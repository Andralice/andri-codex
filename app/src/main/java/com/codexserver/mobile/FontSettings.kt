package com.codexserver.mobile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun FontSettingsButton() {
    var open by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { ActionLabel("settings", "字体设置") }
    if (open) FontSettingsDialog { open = false }
}

@Composable
internal fun FontSettingsDialog(onDismiss: () -> Unit) {
    val selection = LocalAppFontSelection.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("字体设置") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("选择后立即生效，下次打开仍会保留。", style = MaterialTheme.typography.bodySmall)
                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AppFont.entries.forEach { font ->
                        Row(Modifier.fillMaxWidth()
                            .selectable(selected = selection.selected == font, role = Role.RadioButton,
                                onClick = { selection.select(font) })
                            .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            RadioButton(selected = selection.selected == font, onClick = null, modifier = Modifier.size(24.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(font.label, fontFamily = font.fontFamily, fontSize = 15.sp)
                                Text(font.description, fontFamily = font.fontFamily, fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                Surface(shape = MaterialTheme.shapes.medium,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("工作，随你而行。", style = MaterialTheme.typography.titleMedium)
                        Text("你可以随时回来，接着上次的进度继续。", style = MaterialTheme.typography.bodyMedium)
                        Text("Andri Codex 0123456789", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text("代码和命令输出保留等宽字体。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("字体致谢：MiSans 小米字体；Noto Sans / Noto Serif（SIL OFL 1.1）；霞鹜文楷屏幕阅读版（SIL OFL 1.1）。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } }
    )
}
