package com.zycomic.app.ui.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.zycomic.app.net.RouteManager
import com.zycomic.app.ui.components.FilterChip
import com.zycomic.app.ui.theme.BlueContainer
import com.zycomic.app.ui.theme.TextSecondary

@Composable
fun SettingsScreen() {
    val vm = remember { SettingsViewModel() }
    val context = LocalContext.current
    val filterEnabled by vm.filterEnabled.collectAsState()
    val allTags by vm.allTags.collectAsState()
    val blockedTags by vm.blockedTags.collectAsState()
    val testResults by vm.testResults.collectAsState()
    val testing by vm.testing.collectAsState()

    LaunchedEffect(Unit) {
        vm.loadAllTags()
        vm.loadBlockedTags()
        vm.toast.collect { msg ->
            if (msg != null) {
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                vm.toast.value = null
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
    ) {
        Text("设置", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))

        // 过滤屏蔽标签开关
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("过滤屏蔽标签", style = MaterialTheme.typography.titleMedium)
                Text(
                    "开启后，分类/最近更新/排行/搜索会自动隐藏命中屏蔽标签的漫画，并自动多页加载凑满目标条数；关闭则原样显示。开启会略微增加请求次数。",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            }
            Switch(checked = filterEnabled, onCheckedChange = { vm.setFilterEnabled(it) })
        }

        // ===== 标签屏蔽添加 =====
        SectionTitle("添加屏蔽标签")

        // gay 一键屏蔽卡片（primaryContainer 背景区分）
        Box(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                .background(BlueContainer, RoundedCornerShape(12.dp))
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("gay 标签一键屏蔽", style = MaterialTheme.typography.titleMedium)
                    Text("自动屏蔽所有含 gay 的标签", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                }
                Button(onClick = { vm.blockGayTags() }) { Text("一键屏蔽") }
            }
        }

        TagMultiSelect(
            title = "全部标签（搜索后多选提交）",
            tags = allTags,
            onSubmit = { selected -> vm.submitAddBlock(selected) },
        )

        // ===== 标签屏蔽删除 =====
        SectionTitle("删除屏蔽标签")
        TagMultiSelect(
            title = "已屏蔽标签",
            tags = blockedTags,
            allowSelectAll = true,
            onSubmit = { selected -> vm.submitRemoveBlock(selected) },
        )

        // ===== 测速日志 =====
        SectionTitle("测速日志")
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Text("当前线路：${RouteManager.baseUrl}", style = MaterialTheme.typography.bodyMedium)
            Text("当前图源：${RouteManager.imgHost}", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
            Text("网络配置更新时间：${vm.configUpdateTime.collectAsState().value}", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            Button(onClick = { vm.runSpeedTest() }, enabled = !testing) {
                Text(if (testing) "测速中..." else "开始测速")
            }
            testResults.forEach { r ->
                Text(r, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }
        }

        // 更新网络配置
        Button(
            onClick = { vm.updateNetworkConfig() },
            modifier = Modifier.padding(16.dp),
        ) { Text("更新网络配置") }

        // 开发者设置
        SectionTitle("开发者设置")
        var ruleJson by remember { mutableStateOf("{}") }
        OutlinedTextField(
            value = ruleJson,
            onValueChange = { ruleJson = it },
            label = { Text("rule (JSON: 域名->IP列表)") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        var sni by remember { mutableStateOf("") }
        OutlinedTextField(
            value = sni,
            onValueChange = { sni = it },
            label = { Text("sni") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Button(
            onClick = { Toast.makeText(context, "已保存开发者配置", Toast.LENGTH_SHORT).show() },
            modifier = Modifier.padding(16.dp),
        ) { Text("保存开发者配置") }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun TagMultiSelect(
    title: String,
    tags: List<String>,
    allowSelectAll: Boolean = false,
    onSubmit: (List<String>) -> Unit,
) {
    var keyword by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        OutlinedTextField(
            value = keyword,
            onValueChange = { keyword = it },
            placeholder = { Text("搜索标签") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        val filtered = if (keyword.isBlank()) tags else tags.filter { it.contains(keyword, ignoreCase = true) }
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(vertical = 8.dp),
        ) {
            filtered.forEach { tag ->
                FilterChip(
                    text = tag,
                    selected = selected.contains(tag),
                    onClick = {
                        selected = if (selected.contains(tag)) selected - tag else selected + tag
                    },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 16.dp)) {
            if (allowSelectAll) {
                Button(onClick = { selected = filtered.toSet() }) { Text("全选") }
            }
            Button(onClick = {
                onSubmit(selected.toList())
                selected = emptySet()
            }) { Text("提交 (${selected.size})") }
        }
    }
}
