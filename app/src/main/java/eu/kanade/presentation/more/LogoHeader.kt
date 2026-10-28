package eu.kanade.presentation.more

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.R

@Composable
fun LogoHeader(bottomContent: (@Composable () -> Unit)? = null) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 品牌图有颜色，不能用 Icon（tint 会把整张图涂成单色）。
        Image(
            painter = painterResource(R.drawable.ic_zycomic),
            contentDescription = null,
            modifier = Modifier
                .padding(top = 56.dp, bottom = if (bottomContent == null) 56.dp else 0.dp)
                .size(72.dp)
                .clip(RoundedCornerShape(18.dp)),
        )

        if (bottomContent != null) {
            Box(
                modifier = Modifier.fillMaxWidth().height(56.dp),
                contentAlignment = Alignment.Center,
            ) {
                bottomContent()
            }
        }

        HorizontalDivider()
    }
}
