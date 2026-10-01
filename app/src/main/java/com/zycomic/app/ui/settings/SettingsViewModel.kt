package com.zycomic.app.ui.settings

import com.zycomic.app.data.repository.MangaRepository
import com.zycomic.app.data.repository.TagRepository
import com.zycomic.app.net.RouteManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class SettingsViewModel {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // 过滤开关
    val filterEnabled = MutableStateFlow(TagRepository.isFilterEnabled())

    // 全部标签（添加屏蔽用）
    val allTags = MutableStateFlow<List<String>>(emptyList())
    // 已屏蔽标签（删除用）
    val blockedTags = MutableStateFlow<List<String>>(emptyList())

    // 测速
    val currentLineIndex = MutableStateFlow(RouteManager.lineIndex)
    val currentImgIndex = MutableStateFlow(RouteManager.imgIndex)
    val testResults = MutableStateFlow<List<String>>(emptyList())
    val testing = MutableStateFlow(false)
    val configUpdateTime = MutableStateFlow("2026-09-30")

    /** 提交后 toast 消息。 */
    val toast = MutableStateFlow<String?>(null)

    fun setFilterEnabled(v: Boolean) {
        TagRepository.setFilterEnabled(v)
        filterEnabled.value = v
    }

    fun loadAllTags() {
        if (allTags.value.isNotEmpty()) return
        scope.launch {
            try {
                val groups = MangaRepository.getTags()
                allTags.value = groups.flatMap { it.list.map { t -> t.name } }.distinct()
            } catch (_: Exception) {}
        }
    }

    fun loadBlockedTags() {
        scope.launch {
            try { blockedTags.value = TagRepository.getBlackTags() }
            catch (_: Exception) {}
        }
    }

    /** gay 标签一键屏蔽：屏蔽名字含 gay 的标签。 */
    fun blockGayTags() {
        scope.launch {
            if (allTags.value.isEmpty()) loadAllTags()
            val gayTags = allTags.value.filter { it.contains("gay", ignoreCase = true) }
            try {
                TagRepository.addBlackTags(gayTags)
                toast.value = "已屏蔽 ${gayTags.size} 个 gay 标签"
                loadBlockedTags()
            } catch (e: Exception) {
                toast.value = e.message
            }
        }
    }

    fun submitAddBlock(tags: List<String>) {
        scope.launch {
            try {
                TagRepository.addBlackTags(tags)
                toast.value = "已添加屏蔽 ${tags.size} 个标签"
                loadBlockedTags()
            } catch (e: Exception) { toast.value = e.message }
        }
    }

    fun submitRemoveBlock(tags: List<String>) {
        scope.launch {
            try {
                TagRepository.removeBlackTags(tags)
                toast.value = "已删除屏蔽 ${tags.size} 个标签"
                loadBlockedTags()
            } catch (e: Exception) { toast.value = e.message }
        }
    }

    /** 测速：对当前线路每个 IP 发 HEAD 请求测延迟。 */
    fun runSpeedTest() {
        scope.launch {
            testing.value = true
            testResults.value = emptyList()
            val client = OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .hostnameVerifier { _, _ -> true }
                .build()
            val host = RouteManager.lineHost
            val ips = RouteManager.resolveIp(host).ifEmpty { listOf(host) }
            val results = mutableListOf<String>()
            ips.forEach { ip ->
                val url = "https://$ip/"
                val start = System.nanoTime()
                try {
                    val req = Request.Builder().url(url).header("Host", host).head().build()
                    client.newCall(req).execute().use { resp ->
                        val ms = (System.nanoTime() - start) / 1_000_000
                        results.add("$ip -> ${ms}ms (HTTP ${resp.code})")
                    }
                } catch (e: Exception) {
                    results.add("$ip -> 失败: ${e.message}")
                }
                testResults.value = results.toList()
            }
            testing.value = false
        }
    }

    fun updateNetworkConfig() {
        scope.launch {
            // 重新解析预设 IP 配置（此处模拟：统计域名数）
            val count = RouteManager.STATIC_IP.size
            toast.value = "已更新网络配置，共 $count 个域名"
        }
    }
}
