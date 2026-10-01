package com.zycomic.app.ui.manga

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.zycomic.app.ui.components.ErrorView
import com.zycomic.app.ui.theme.TextSecondary

@Composable
fun ReaderScreen(
    chapterId: Int,
    onClose: () -> Unit,
) {
    val vm = remember { ReaderViewModel(chapterId) }
    val pages by vm.pages.collectAsState()
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White)
            }
            error != null -> ErrorView(message = error ?: "", onRetry = { vm.load() })
            else -> LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(pages) { url ->
                    AsyncImage(
                        model = url,
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp),
        ) {
            Icon(Icons.Default.ArrowBack, contentDescription = "关闭", tint = Color.White)
        }
    }
}

/** 阅读器覆盖层（full-screen Dialog，盖在详情之上）。 */
@Composable
fun ReaderOverlay(
    chapterId: Int,
    onClose: () -> Unit,
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose) {
        ReaderScreen(chapterId = chapterId, onClose = onClose)
    }
}
