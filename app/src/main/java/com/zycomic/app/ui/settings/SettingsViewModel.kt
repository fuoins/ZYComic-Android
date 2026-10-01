package com.zycomic.app.ui.settings

import com.zycomic.app.data.repository.MangaRepository
import com.zycomic.app.data.repository.TagRepository
import com.zycomic.app.net.ManwaDns
import com.zycomic.app.net.RouteManager
import com.zycomic.app.net.SniBypassSSLSocketFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

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
    val configUpdateTime = MutableStateFlow("未更新")

    // 开发者配置 JSON
    val devConfigJson = MutableStateFlow(RouteManager.DEFAULT_CONFIG_JSON)

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
                val tags = MangaRepository.getTags()
                allTags.value = tags.map { it.name }.distinct()
            } catch (e: Exception) {
                android.util.Log.e("SettingsViewModel", "loadAllTags failed", e)
                toast.value = "加载标签失败: ${e.message}"
            }
        }
    }

    fun loadBlockedTags() {
        scope.launch {
            try { blockedTags.value = TagRepository.getBlackTags() }
            catch (e: Exception) {
                android.util.Log.e("SettingsViewModel", "loadBlockedTags failed", e)
            }
        }
    }

    /** gay 标签一键屏蔽：直接用硬编码的 GAY_TAGS 列表调用 addBlackTags。 */
    fun blockGayTags() {
        scope.launch {
            try {
                TagRepository.addBlackTags(GAY_TAGS)
                toast.value = "已屏蔽 ${GAY_TAGS.size} 个女性向标签"
                loadBlockedTags()
            } catch (e: Exception) {
                android.util.Log.e("SettingsViewModel", "blockGayTags failed", e)
                toast.value = e.message ?: "屏蔽失败"
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

    /** 切换线路 */
    fun selectLine(index: Int) {
        RouteManager.setLine(index)
        currentLineIndex.value = index
        com.zycomic.app.net.NetworkModule.rebuild()
        toast.value = "已切换到线路 ${index + 1}"
    }

    /** 切换图源 */
    fun selectImgHost(index: Int) {
        RouteManager.setImgHost(index)
        currentImgIndex.value = index
        toast.value = "已切换到图源 ${index + 1}"
    }

    /** 测速：遍历所有7条线路，每条线路的每个IP单独测速（5秒超时），结果按线路分组。固定用 HTTP。 */
    fun runSpeedTest() {
        scope.launch {
            testing.value = true
            testResults.value = emptyList()
            val client = OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .hostnameVerifier { _, _ -> true }
                .build()
            val results = mutableListOf<String>()
            RouteManager.LINE_HOSTS.forEachIndexed { index, lineUrl ->
                val host = lineUrl.removePrefix("https://").removePrefix("http://").substringBefore('/')
                val ips = RouteManager.resolveIp(host).ifEmpty { listOf(host) }
                val marker = if (index == RouteManager.lineIndex) " ← 当前" else ""
                results.add("── 线路${index + 1}: $host$marker ──")
                ips.forEach { ip ->
                    // 固定用 HTTP，不管原线路是 http 还是 https
                    val url = "http://$ip/"
                    val start = System.nanoTime()
                    try {
                        val req = Request.Builder().url(url).header("Host", host).head().build()
                        client.newCall(req).execute().use { resp ->
                            val ms = (System.nanoTime() - start) / 1_000_000
                            results.add("  $ip -> ${ms}ms (HTTP ${resp.code})")
                        }
                    } catch (e: Exception) {
                        results.add("  $ip -> 失败: ${e.message}")
                    }
                }
                testResults.value = results.toList()
            }
            testing.value = false
        }
    }

    /**
     * DoH 自动更新 IP：遍历当前 customRule 中所有域名，通过 dns.google 查询 A 记录，
     * 如果 IP 列表有变化则更新。
     */
    fun updateNetworkConfig() {
        scope.launch {
            try {
                // 构建独立的 DoH OkHttpClient（2秒超时，trust-all + ManwaDns + SniBypass）
                val trustAll = object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                }
                val sslContext = SSLContext.getInstance("TLS")
                sslContext.init(null, arrayOf<TrustManager>(trustAll), SecureRandom())

                val dohClient = OkHttpClient.Builder()
                    .connectTimeout(2, TimeUnit.SECONDS)
                    .readTimeout(2, TimeUnit.SECONDS)
                    .dns(ManwaDns)
                    .sslSocketFactory(SniBypassSSLSocketFactory(sslContext.socketFactory), trustAll)
                    .hostnameVerifier { _, _ -> true }
                    .build()

                val jsonParser = Json { ignoreUnknownKeys = true }
                val currentRule = RouteManager.customRule
                val newRuleMap = mutableMapOf<String, List<String>>()
                var successCount = 0
                var failCount = 0

                // 遍历当前 customRule 中的所有域名（跳过 dns.google 本身）
                for ((domain, oldIps) in currentRule) {
                    if (domain == "dns.google") {
                        newRuleMap[domain] = oldIps
                        continue
                    }
                    try {
                        val url = "https://dns.google/resolve?name=$domain&type=A"
                        val req = Request.Builder().url(url).build()
                        val resp = dohClient.newCall(req).execute()
                        val body = resp.body?.string() ?: ""
                        resp.close()

                        val root = jsonParser.parseToJsonElement(body).jsonObject
                        val answerArr = root["Answer"]?.jsonArray ?: JsonArray(emptyList())
                        val newIps = answerArr
                            .filter { it.jsonObject["type"]?.jsonPrimitive?.intOrNull == 1 }
                            .map { it.jsonObject["data"]?.jsonPrimitive?.content ?: "" }
                            .filter { it.isNotEmpty() }

                        if (newIps.isNotEmpty()) {
                            newRuleMap[domain] = newIps
                            // 只有 IP 列表有变化才算成功更新
                            if (newIps != oldIps) {
                                successCount++
                            }
                        } else {
                            // 没查到，保留旧 IP
                            newRuleMap[domain] = oldIps
                            failCount++
                        }
                    } catch (e: Exception) {
                        // 失败的域名保留旧 IP，不中断
                        newRuleMap[domain] = oldIps
                        failCount++
                        android.util.Log.e("SettingsViewModel", "DoH query failed: $domain", e)
                    }
                }

                // 应用新 rule
                RouteManager.setCustomRule(newRuleMap)

                toast.value = "更新完成，成功${successCount}个，失败${failCount}个"
                configUpdateTime.value = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
            } catch (e: Exception) {
                android.util.Log.e("SettingsViewModel", "updateNetworkConfig failed", e)
                toast.value = "更新网络配置失败: ${e.message}"
            }
        }
    }

    // ==================== 开发者配置 ====================

    /** 解析开发者配置 JSON 并应用。新格式：{"port":7891,"rule":{"domain":["ip1"]},"sni":["domain1"]} */
    fun saveDevConfig(jsonStr: String) {
        scope.launch {
            try {
                val json = Json { ignoreUnknownKeys = true }
                val root = json.parseToJsonElement(jsonStr).jsonObject

                // 解析 rule: {"domain": ["ip1", "ip2"]}
                val ruleObj = root["rule"]?.jsonObject ?: emptyMap()
                val ruleMap = mutableMapOf<String, List<String>>()
                ruleObj.forEach { (domain, arr) ->
                    val ips = arr.jsonArray.map { it.jsonPrimitive.contentOrNull ?: "" }.filter { it.isNotEmpty() }
                    ruleMap[domain] = ips
                }

                // 解析 sni: ["domain1", "domain2"]
                val sniArr = root["sni"]?.jsonArray ?: JsonArray(emptyList())
                val sniSet = sniArr.map { it.jsonPrimitive.contentOrNull ?: "" }.filter { it.isNotEmpty() }.toSet()

                // port 暂存但不使用
                // val port = root["port"]?.jsonPrimitive?.intOrNull ?: 7891

                RouteManager.setCustomRule(ruleMap)
                RouteManager.setSniDomains(sniSet)
                toast.value = "配置已保存（${ruleMap.size}条rule, ${sniSet.size}条sni）"
            } catch (e: Exception) {
                android.util.Log.e("SettingsViewModel", "saveDevConfig failed", e)
                toast.value = "配置解析失败: ${e.message}"
            }
        }
    }

    companion object {
        /** 硬编码的女性向/gay标签列表（129个），一键屏蔽用。 */
        val GAY_TAGS = listOf(
            "女性向", "腹黑攻", "傲娇受", "执著攻", "忠犬攻", "ABO", "诱受‧袭受", "美人受",
            "色气受", "掰弯", "美人攻", "强攻", "壮受", "可爱受", "诱受·袭受", "温柔攻",
            "固执受", "强受", "硬派攻", "深情攻", "忠犬受", "男孕", "女王受", "疯批攻",
            "傲娇攻", "傲慢攻", "病娇攻", "天然受", "健气受", "淫荡受", "NP", "寡默攻",
            "耽美", "后悔攻", "呆萌受", "心机攻", "直男受", "霸道攻", "纯情受", "哭包攻",
            "纯情攻", "多攻", "纯真受", "天然攻", "互攻", "创伤受", "怯懦攻", "绝伦攻",
            "执着攻", "偏执攻", "渣攻", "平凡受", "轻浮受", "婊子受", "淫乱受", "可爱攻",
            "直男攻", "狂攻系列", "软萌受", "面瘫攻", "自卑受", "硬派受", "腹黑受", "鬼畜攻",
            "哭包受", "单纯受", "年下攻", "菁英攻", "眼镜攻", "菁英受", "美男攻", "神经受",
            "伤口受", "难搞受", "狡猾攻", "多情攻", "孤儿受", "胆小受", "敬语攻", "财阀攻",
            "丑攻", "性转换", "抹布受", "疯子攻", "绝世好攻", "绝世好受", "驱魔师受", "颜控受",
            "厚脸皮攻", "抖M攻", "技巧高超攻", "痴汉攻", "双洁", "哨兵向导", "三角关系", "年上受",
            "阳光受", "吉娃娃受", "诱攻", "O攻A受", "傻子受", "大型犬攻", "单恋受", "狡黠攻",
            "不洁攻", "年下受", "强制爱", "幼稚攻", "黑发受", "0转1", "年上攻", "计谋受",
            "逃亡受", "冷血攻", "无心攻", "计谋攻", "能力受", "温柔受", "怀孕受", "矮攻",
            "创伤攻", "失恋攻", "美男受", "迷糊受", "小学生受", "虚张声势受", "19r", "19R",
            "女攻男受",
        )
    }
}
