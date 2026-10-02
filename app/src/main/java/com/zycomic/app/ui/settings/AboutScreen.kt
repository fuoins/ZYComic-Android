package com.zycomic.app.ui.settings

import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.zycomic.app.BuildConfig
import eu.kanade.presentation.more.LogoHeader
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import tachiyomi.presentation.core.components.ScrollbarLazyColumn

/**
 * 关于页面：Logo + 版本号 + 开源许可（预留）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("关于") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { contentPadding ->
        ScrollbarLazyColumn(contentPadding = contentPadding) {
            item {
                LogoHeader()
            }
            item {
                TextPreferenceWidget(
                    title = "版本",
                    subtitle = com.zycomic.app.BuildConfig.VERSION_NAME,
                    onPreferenceClick = {
                        Toast.makeText(context, "ZYComic v${com.zycomic.app.BuildConfig.VERSION_NAME}", Toast.LENGTH_SHORT).show()
                    },
                )
            }
            item {
                TextPreferenceWidget(
                    title = "开源许可",
                    onPreferenceClick = {
                        Toast.makeText(context, "即将上线", Toast.LENGTH_SHORT).show()
                    },
                )
            }
        }
    }
}
