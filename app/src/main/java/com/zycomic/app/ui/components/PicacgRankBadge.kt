package com.zycomic.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Matches YuzongRankContent's RankBadge without changing other platforms' presentation. */
@Composable
internal fun PicacgRankBadge(rank: Int, modifier: Modifier = Modifier) {
    val bg = when (rank) {
        1 -> Color(0xFFFFC107)
        2 -> Color(0xFFB0BEC5)
        3 -> Color(0xFFCD7F32)
        else -> Color(0x80000000)
    }
    Box(
        modifier = modifier
            .padding(4.dp)
            .size(20.dp)
            .clip(CircleShape)
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = rank.toString(),
            color = if (rank <= 3) Color.Black else Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
    }
}
