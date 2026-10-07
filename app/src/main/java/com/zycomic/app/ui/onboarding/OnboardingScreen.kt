package com.zycomic.app.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
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
import androidx.compose.material3.MultiChoiceSegmentedButtonRow
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.zycomic.app.data.repository.UserRepository
import com.zycomic.app.ui.login.LoginScreen
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val pager = rememberPagerState(pageCount = { 3 })
    val scope = rememberCoroutineScope()
    val user by UserRepository.userFlow.collectAsState()
    val context = LocalContext.current
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
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize().padding(pd), userScrollEnabled = !(pager.currentPage == 1 && user == null)) { page ->
            when (page) {
                0 -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
                    Text("外观设置", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(8.dp))
                    Text("后续也可在设置中修改", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(24.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        listOf(0xFFDF0090.toInt(), 0xFF6750A4.toInt(), 0xFF00658E.toInt(), 0xFF3F5F3F.toInt(), 0xFF8E0000.toInt()).forEach { c ->
                            val cur = prefs.getInt("pref_color_theme", 0xFFDF0090.toInt())
                            Box(Modifier.size(36.dp).clip(androidx.compose.foundation.shape.CircleShape).background(androidx.compose.ui.graphics.Color(c)).then(if (cur == c) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, androidx.compose.foundation.shape.CircleShape) else Modifier).clickable { prefs.edit().putInt("pref_color_theme", c).apply() })
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                    val cur = prefs.getString("pref_theme_mode_key", "SYSTEM") ?: "SYSTEM"
                    MultiChoiceSegmentedButtonRow {
                        listOf("跟随系统" to "SYSTEM", "浅色" to "LIGHT", "深色" to "DARK").forEachIndexed { i, (label, m) ->
                            SegmentedButton(checked = cur == m, onCheckedChange = { prefs.edit().putString("pref_theme_mode_key", m).apply(); eu.kanade.domain.ui.model.setAppCompatDelegateThemeMode(eu.kanade.domain.ui.model.ThemeMode.valueOf(m)) }, shape = SegmentedButtonDefaults.itemShape(i, 3)) { Text(label) }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("纯黑AMOLED", modifier = Modifier.weight(1f))
                        androidx.compose.material3.Switch(checked = prefs.getBoolean("pref_theme_dark_amoled_key", false), onCheckedChange = { prefs.edit().putBoolean("pref_theme_dark_amoled_key", it).apply() })
                    }
                }
                1 -> Box(Modifier.fillMaxSize()) { LoginScreen(onClose = {}) }
                2 -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("标签设置", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { com.zycomic.app.ui.settings.SettingsViewModel().blockGayTags() }) { Text("gay标签一键屏蔽") }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { android.widget.Toast.makeText(context, "请在设置中管理屏蔽标签", android.widget.Toast.LENGTH_SHORT).show() }) { Text("过滤屏蔽标签") }
                    Spacer(Modifier.height(16.dp))
                    Text("登录后建议点击gay标签一键屏蔽一次，会把所有的gay标签屏蔽掉，有些遗漏的是因为会影响其他正常漫画的观看，所以会有部分gay漫画仍然显示", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    androidx.activity.compose.BackHandler(enabled = true) {}
}
