package com.zycomic.app.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zycomic.app.data.repository.UserRepository
import com.zycomic.app.ui.login.LoginScreen
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val pager = rememberPagerState(pageCount = { 3 })
    val scope = rememberCoroutineScope()
    val user by UserRepository.userFlow.collectAsState()
    LaunchedEffect(user) { if (pager.currentPage == 1 && user != null) { kotlinx.coroutines.delay(500); pager.animateScrollToPage(2) } }

    Scaffold(bottomBar = {
        Column {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.Center) {
                repeat(3) { i ->
                    Box(Modifier.padding(4.dp).size(8.dp).clip(androidx.compose.foundation.shape.CircleShape).background(if (pager.currentPage == i) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)).clickable { scope.launch { pager.animateScrollToPage(i) } })
                }
            }
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            if (pager.currentPage > 0) OutlinedButton(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }) { Text("上一步") }
            else Spacer(Modifier)
            Button(onClick = {
                scope.launch {
                    when (pager.currentPage) {
                        0 -> pager.animateScrollToPage(1)
                        1 -> pager.animateScrollToPage(2)
                        2 -> onDone()
                    }
                }
            }, enabled = pager.currentPage != 1 || user != null) {
                Text(when (pager.currentPage) { 0 -> "下一步"; 1 -> "下一步"; else -> "完成" })
            }
            }
        }
    }) { pd ->
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize().padding(pd)) { page ->
            when (page) {
                0 -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("外观设置", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(16.dp))
                    Text("在系统设置或后续设置页中调整深色模式与主题色", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                1 -> Box(Modifier.fillMaxSize()) { LoginScreen(onClose = {}) }
                2 -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("标签设置", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(16.dp))
                    Text("登录后建议点击gay标签一键屏蔽一次，会把所有的gay标签屏蔽掉，有些遗漏的是因为会影响其他正常漫画的观看，所以会有部分gay漫画仍然显示", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    androidx.activity.compose.BackHandler(enabled = true) {}
}
