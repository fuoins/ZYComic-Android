package com.zycomic.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * 封面加载失败占位：错误时显示 BrokenImage 图标，与阅读器失败体验一致。
 * 用法：AsyncImage(... error = { CoverError(Modifier.fillMaxSize()) })
 */
@Composable
fun CoverError(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Outlined.BrokenImage,
            contentDescription = "封面加载失败",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
