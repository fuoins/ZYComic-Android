package com.zycomic.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp

/** 常用调色板：一行 6 个，浅色 → 深色。 */
private val PALETTE: List<List<Color>> = listOf(
    listOf(
        Color(0xFFFFFFFF), Color(0xFFE9EDF2), Color(0xFFC3CBD6), Color(0xFF7A8797),
        Color(0xFF3B4552), Color(0xFF000000),
    ),
    listOf(
        Color(0xFFFF6B6B), Color(0xFFFF9F43), Color(0xFFFFD93D), Color(0xFF6AB04C),
        Color(0xFF22A6B3), Color(0xFF4A69BD),
    ),
    listOf(
        Color(0xFFEB4D4B), Color(0xFFF0932B), Color(0xFFF9CA24), Color(0xFF2ECC71),
        Color(0xFF00CEC9), Color(0xFF3742FA),
    ),
    listOf(
        Color(0xFFE056FD), Color(0xFFBE2EDD), Color(0xFF8E44AD), Color(0xFF4834D4),
        Color(0xFF1B9CFC), Color(0xFF6DD5FA),
    ),
)

/** 颜色选择：调色板 + 颜色代码输入，应用后立即回调。 */
@Composable
fun ColorPickerDialog(
    title: String,
    initial: Color,
    onDismiss: () -> Unit,
    onConfirm: (Color) -> Unit,
) {
    var picked by remember(initial) { mutableStateOf(initial) }
    var hexText by remember(initial) { mutableStateOf(AppearanceColors.hex(initial)) }
    val outline = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(picked)
                            .border(1.dp, outline, CircleShape),
                    )
                    Spacer(Modifier.size(12.dp))
                    OutlinedTextField(
                        value = hexText,
                        onValueChange = { value ->
                            hexText = value
                            AppearanceColors.parseColor(value)?.let { picked = it }
                        },
                        label = { Text("颜色代码 #RRGGBB") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(14.dp))
                PALETTE.forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        row.forEach { swatch ->
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(34.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(swatch)
                                    .border(
                                        width = if (swatch == picked) 2.dp else 1.dp,
                                        color = if (swatch == picked) MaterialTheme.colorScheme.primary else outline,
                                        shape = RoundedCornerShape(10.dp),
                                    )
                                    .clickable {
                                        picked = swatch
                                        hexText = AppearanceColors.hex(swatch)
                                    },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(picked) }) { Text("应用") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
