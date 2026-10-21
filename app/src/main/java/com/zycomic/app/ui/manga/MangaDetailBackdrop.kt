package com.zycomic.app.ui.manga

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage

/** Shared detail backdrop; each platform supplies its own isolated image loader. */
@Composable
fun MangaDetailBackdrop(coverModel: Any?, imageLoader: ImageLoader, modifier: Modifier = Modifier) {
    Box(modifier) {
        AsyncImage(
            model = coverModel,
            contentDescription = null,
            imageLoader = imageLoader,
            modifier = Modifier.matchParentSize().blur(7.dp).alpha(0.2f),
            contentScale = ContentScale.Crop,
        )
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(
                    0.5f to Color.Transparent,
                    1f to MaterialTheme.colorScheme.background,
                ),
            ),
        )
    }
}
