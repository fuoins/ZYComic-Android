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
    val currentLineIdx by vm.currentLineIndex.collectAsState()
    val currentImgIdx by vm.currentImgIndex.collectAsState()
    val updateTime by vm.configUpdateTime.collectAsState()
    val devJson by vm.devConfigJson.collectAsState()

    var showGayConfirm by remember { mutableStateOf(false) }

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
                    "对屏蔽的标签漫画进行隐藏(正常会显示404)。建议登录后配合gay标签一键屏蔽使用。开启后部分分类会有大量屏蔽内容，为凑够布局会多获取几页，加载变慢属正常现象。建议选择[一般向]或[禁漫]或[搜索]，[排行]基本都是gay标签内容。如果你点击[BL向]只有两本属于正常，因为获取十页全屏蔽了。",
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
                    Text("自动屏蔽所有女性向/gay标签（${SettingsViewModel.GAY_TAGS.size}个）", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                }
                Button(onClick = { showGayConfirm = true }) { Text("一键屏蔽") }
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
            Text("网络配置更新时间：$updateTime", style = MaterialTheme.typography.bodySmall, color = TextSecondary)

            // 线路列表
            Text("线路（点击切换）：", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            RouteManager.LINE_HOSTS.forEachIndexed { index, url ->
                val host = url.removePrefix("https://").removePrefix("http://")
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { vm.selectLine(index) }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${index + 1}. $host" + if (index == currentLineIdx) "  ← 当前" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (index == currentLineIdx) com.zycomic.app.ui.theme.BluePrimary else Color.Black,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // 图源列表
            Text("图源（点击切换）：", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            RouteManager.IMG_DOMAINS.forEachIndexed { index, domain ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { vm.selectImgHost(index) }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${index + 1}. $domain" + if (index == currentImgIdx) "  ← 当前" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (index == currentImgIdx) com.zycomic.app.ui.theme.BluePrimary else Color.Black,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Button(onClick = { vm.runSpeedTest() }, enabled = !testing, modifier = Modifier.padding(top = 8.dp)) {
                Text(if (testing) "测速中..." else "开始测速 (TCP 443)")
            }

            testResults.forEach { r ->
                val isHeader = r.startsWith("──")
                Text(
                    r,
                    style = if (isHeader) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = if (isHeader) 8.dp else 2.dp),
                )
            }
        }

        // 更新网络配置
        Button(
            onClick = { vm.updateNetworkConfig() },
            modifier = Modifier.padding(16.dp),
        ) { Text("更新网络配置") }

        // 开发者设置
        SectionTitle("开发者设置")
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Text(
                "格式: {\"port\":7891,\"rule\":{\"域名\":[\"ip1\",\"ip2\"]},\"sni\":[\"域名1\",\"域名2\"]}",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
            OutlinedTextField(
                value = devJson,
                onValueChange = { vm.devConfigJson.value = it },
                label = { Text("开发者配置 (JSON)") },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                minLines = 8,
                maxLines = 15,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.saveDevConfig(devJson) }) { Text("保存配置") }
            }
        }
    }

    // gay 标签一键屏蔽确认对话框
    if (showGayConfirm) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { showGayConfirm = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White, RoundedCornerShape(12.dp))
                    .padding(24.dp),
            ) {
                Text("确认屏蔽", style = MaterialTheme.typography.titleMedium)
                Text(
                    "确认将 ${SettingsViewModel.GAY_TAGS.size} 个gay相关标签加入屏蔽列表？",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    androidx.compose.material3.TextButton(onClick = { showGayConfirm = false }) {
                        Text("取消")
                    }
                    androidx.compose.material3.TextButton(onClick = {
                        showGayConfirm = false
                        vm.blockGayTags()
                    }) {
                        Text("确认")
                    }
                }
            }
        }
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
    allowSelectAll: Boolean = true,
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
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (allowSelectAll) {
                Button(onClick = { selected = filtered.toSet() }) { Text("全选") }
            }
            Button(onClick = { selected = emptySet() }) { Text("重置") }
            Button(
                modifier = Modifier.weight(1f),
                onClick = {
                    onSubmit(selected.toList())
                    selected = emptySet()
                },
            ) { Text("确认添加 (${selected.size})") }
        }
    }
}
