package com.zycomic.app.ui.settings

import com.zycomic.app.data.AllTags
import com.zycomic.app.data.repository.TagRepository
import com.zycomic.app.data.repository.UserRepository
import com.zycomic.app.net.DevConfig
import com.zycomic.app.net.NetworkModule
import com.zycomic.app.net.RouteManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
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

    // 全部标签（内置，添加屏蔽用）
    val allTags = MutableStateFlow<List<String>>(AllTags.LIST)
    // 已屏蔽标签（删除用）
    val blockedTags = MutableStateFlow<List<String>>(emptyList())

    // 测速
    val currentLineIndex = MutableStateFlow(RouteManager.lineIndex)
    val currentImgIndex = MutableStateFlow(RouteManager.imgIndex)
    /** 手动测速进行中（设置页"重新测速"按钮） */
    val testing = MutableStateFlow(false)
    /** 启动时自动测速进行中（全屏加载层） */
    val autoSelecting = MutableStateFlow(false)
    /** 线路延迟：index -> 毫秒，失败为 Long.MAX_VALUE（初始化时从 RouteManager 恢复上次测速结果） */
    val lineDelays = MutableStateFlow<Map<Int, Long>>(RouteManager.lastLineDelays)
    /** 图源延迟：index -> 毫秒，失败为 Long.MAX_VALUE（初始化时从 RouteManager 恢复上次测速结果） */
    val imgDelays = MutableStateFlow<Map<Int, Long>>(RouteManager.lastImgDelays)
    /** 每个域名下所有 IP 的 TCP 延迟：域名 -> (IP -> 毫秒)，失败为 Long.MAX_VALUE */
    val ipDelays = MutableStateFlow<Map<String, Map<String, Long>>>(RouteManager.lastIpDelays)
    val configUpdateTime = MutableStateFlow("未更新")

    // 屏蔽标签弹窗提交状态
    val addingBlacklist = MutableStateFlow(false)
    val removingBlacklist = MutableStateFlow(false)

    // 开发者配置 JSON
    val devConfigJson = MutableStateFlow(DevConfig.getConfigJson())

    // 本地代理（SNI绕过）开关
    val proxyEnabled = MutableStateFlow(DevConfig.isProxyEnabled())

    fun setProxyEnabled(v: Boolean) {
        DevConfig.setProxyEnabled(v)
        proxyEnabled.value = v
        toast.value = if (v) "已开启本地代理，重启App生效" else "已关闭本地代理（抓包模式），重启App生效"
    }

    /** 提交后 toast 消息。 */
    val toast = MutableStateFlow<String?>(null)

    fun setFilterEnabled(v: Boolean) {
        TagRepository.setFilterEnabled(v)
        filterEnabled.value = v
    }

    /** 加载全部标签：直接使用内置 AllTags.LIST，不调用 API。 */
    fun loadAllTags() {
        allTags.value = AllTags.LIST
    }

    fun loadBlockedTags() {
        scope.launch {
            try { blockedTags.value = TagRepository.getBlackTags() }
            catch (e: Exception) {
                android.util.Log.e("SettingsViewModel", "loadBlockedTags failed", e)
            }
        }
    }

    /** gay 标签一键屏蔽：调用 addBlackTags 提交129个标签。 */
    fun blockGayTags() {
        scope.launch {
            try {
                TagRepository.addBlackTags(GAY_TAGS)
                toast.value = "已屏蔽 ${GAY_TAGS.size} 个gay相关标签"
                loadBlockedTags()
            } catch (e: Exception) {
                android.util.Log.e("SettingsViewModel", "blockGayTags failed", e)
                toast.value = e.message ?: "屏蔽失败"
            }
        }
    }

    // ==================== 屏蔽标签添加 / 删除弹窗提交 ====================

    /**
     * 提交添加屏蔽标签。UI 已做空选中校验。
     * 成功：toast + 刷新已屏蔽列表 + onSuccess（关闭弹窗）。
     * 失败：toast，弹窗保留。
     */
    fun submitAddBlacklist(tags: List<String>, onSuccess: () -> Unit) {
        scope.launch {
            addingBlacklist.value = true
            try {
                TagRepository.addBlackTags(tags)
                toast.value = "已添加 ${tags.size} 个标签到屏蔽列表"
                loadBlockedTags()
                onSuccess()
            } catch (e: Exception) {
                android.util.Log.e("SettingsViewModel", "submitAddBlacklist failed", e)
                toast.value = "添加失败，请重试"
            } finally {
                addingBlacklist.value = false
            }
        }
    }

    /**
     * 提交删除屏蔽标签。UI 已做空选中校验。
     * 成功：toast + 刷新已屏蔽列表 + onSuccess（关闭弹窗）。
     * 失败：toast，弹窗保留。
     */
    fun submitRemoveBlacklist(tags: List<String>, onSuccess: () -> Unit) {
        scope.launch {
            removingBlacklist.value = true
            try {
                TagRepository.removeBlackTags(tags)
                toast.value = "已删除 ${tags.size} 个屏蔽标签"
                loadBlockedTags()
                onSuccess()
            } catch (e: Exception) {
                android.util.Log.e("SettingsViewModel", "submitRemoveBlacklist failed", e)
                toast.value = "删除失败，请重试"
            } finally {
                removingBlacklist.value = false
            }
        }
    }

    /** 切换线路：重建网络后验证登录态，失效则自动登出。 */
    fun selectLine(index: Int) {
        RouteManager.setLine(index)
        currentLineIndex.value = index
        com.zycomic.app.net.NetworkModule.rebuild()
        toast.value = "已切换到线路 ${index + 1}"
        // 切换线路后验证登录态（cookie 可能在新线路失效）
        scope.launch {
            try {
                UserRepository.verifyLogin()
            } catch (_: Exception) {}
        }
    }

    /** 切换图源：更新 imgHost 并清除 Coil 缓存，避免旧图源图片/封面被缓存命中。 */
    fun selectImgHost(index: Int) {
        RouteManager.setImgHost(index)
        currentImgIndex.value = index
        // 清除内存 + 磁盘缓存，强制用新图源重新加载图片
        DevConfig.clearImageCaches()
        toast.value = "已切换到图源 ${index + 1}"
    }

    /** setImgHost 别名（语义化命名）：切换图源并清缓存。 */
    fun setImgHost(index: Int) = selectImgHost(index)

    // ==================== HTTP 测速 ====================

    /**
     * 针对特定 IP 构建临时测速 client：
     * - 绕过本地代理（NO_PROXY），否则代理自己做 DNS 轮询，自定义 Dns 无效。
     * - 自定义 Dns：目标域名强制解析到指定 IP，其他域名走系统 DNS。
     * - 2 秒超时。
     * - 继承 baseClient 的 cookieJar / trust-all SSL / 拦截器。
     */
    private fun ipTargetedClient(baseClient: OkHttpClient, domain: String, ip: String): OkHttpClient =
        baseClient.newBuilder()
            .proxy(Proxy.NO_PROXY)
            .dns { hostname ->
                if (hostname.equals(domain, ignoreCase = true)) {
                    listOf(InetAddress.getByName(ip))
                } else {
                    Dns.SYSTEM.lookup(hostname)
                }
            }
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build()

    /**
     * 测单条线路的指定 IP：绕过代理 + 自定义DNS将域名解析到 targetIp，GET 完整域名 URL。
     * 成功=延迟毫秒，失败=Long.MAX_VALUE。
     */
    private suspend fun measureLineIp(baseClient: OkHttpClient, lineUrl: String, domain: String, ip: String): Long = withContext(Dispatchers.IO) {
        val url = "$lineUrl/api/index/index?facility=android&deviceid=speedtest&timestamp=${System.currentTimeMillis()}"
        val client = ipTargetedClient(baseClient, domain, ip)
        val req = Request.Builder().url(url).get().build()
        val start = System.nanoTime()
        try {
            client.newCall(req).execute().use { resp ->
                resp.body?.bytes()
            }
            (System.nanoTime() - start) / 1_000_000
        } catch (e: Exception) {
            Long.MAX_VALUE
        }
    }

    /**
     * 测单个图源的指定 IP：绕过代理 + 自定义DNS，GET https://domain/。
     * 成功=延迟毫秒，失败=Long.MAX_VALUE。
     */
    private suspend fun measureImgIp(baseClient: OkHttpClient, domain: String, ip: String): Long = withContext(Dispatchers.IO) {
        val client = ipTargetedClient(baseClient, domain, ip)
        val req = Request.Builder().url("https://$domain/").get().build()
        val start = System.nanoTime()
        try {
            client.newCall(req).execute().use { resp ->
                resp.body?.bytes()
            }
            (System.nanoTime() - start) / 1_000_000
        } catch (e: Exception) {
            Long.MAX_VALUE
        }
    }

    /**
     * IP 级并行测速：对每个域名的所有预设 IP 分别发 HTTP 请求，选最快 IP。
     * 测速绕过本地代理 + 自定义 DNS 直连指定 IP，2 秒超时。
     *
     * @return Triple(线路 index->最优延迟, 图源 index->最优延迟, 域名->(IP->延迟))
     */
    private suspend fun runMeasure(): Triple<Map<Int, Long>, Map<Int, Long>, Map<String, Map<String, Long>>> = coroutineScope {
        val baseClient = NetworkModule.client
        val ipDelayMap = mutableMapOf<String, MutableMap<String, Long>>()

        // ---- 线路测速（每条线路的所有 IP 并行）----
        val lineResults = RouteManager.LINE_HOSTS.mapIndexed { index, lineUrl ->
            async {
                val domain = lineUrl.removePrefix("https://").removePrefix("http://").substringBefore('/')
                val ips = RouteManager.resolveIp(domain)
                val delays: Map<String, Long>
                val bestDelay: Long

                if (ips.isEmpty()) {
                    // 无预设 IP：退化为域名级测速，以域名本身作为唯一"IP"条目
                    val d = measureLineIp(baseClient, lineUrl, domain, domain)
                    delays = mapOf(domain to d)
                    bestDelay = d
                } else {
                    val results = ips.map { ip ->
                        async { ip to measureLineIp(baseClient, lineUrl, domain, ip) }
                    }.awaitAll().toMap()
                    delays = results
                    bestDelay = results.values.minOrNull() ?: Long.MAX_VALUE
                    // 记录最快 IP 供代理优先使用
                    if (bestDelay < Long.MAX_VALUE) {
                        val fastestIp = results.entries.first { it.value == bestDelay }.key
                        RouteManager.setFastestIp(domain, fastestIp)
                    }
                }
                synchronized(ipDelayMap) { ipDelayMap[domain] = delays.toMutableMap() }
                index to bestDelay
            }
        }.awaitAll().toMap()

        // ---- 图源测速（每个图源的所有 IP 并行）----
        val imgResults = RouteManager.IMG_DOMAINS.mapIndexed { index, domain ->
            async {
                val ips = RouteManager.resolveIp(domain)
                val delays: Map<String, Long>
                val bestDelay: Long

                if (ips.isEmpty()) {
                    val d = measureImgIp(baseClient, domain, domain)
                    delays = mapOf(domain to d)
                    bestDelay = d
                } else {
                    val results = ips.map { ip ->
                        async { ip to measureImgIp(baseClient, domain, ip) }
                    }.awaitAll().toMap()
                    delays = results
                    bestDelay = results.values.minOrNull() ?: Long.MAX_VALUE
                    if (bestDelay < Long.MAX_VALUE) {
                        val fastestIp = results.entries.first { it.value == bestDelay }.key
                        RouteManager.setFastestIp(domain, fastestIp)
                    }
                }
                synchronized(ipDelayMap) { ipDelayMap[domain] = delays.toMutableMap() }
                index to bestDelay
            }
        }.awaitAll().toMap()

        Triple(lineResults, imgResults, ipDelayMap)
    }

    /** 设置页"重新测速"按钮：仅测速展示，不自动切换线路。 */
    fun runSpeedTest() {
        scope.launch {
            testing.value = true
            try {
                val (lines, imgs, ipMap) = runMeasure()
                lineDelays.value = lines
                imgDelays.value = imgs
                ipDelays.value = ipMap
                // 持久化到 RouteManager，切换页面后不丢失
                RouteManager.setLastDelays(lines, imgs)
                RouteManager.setLastIpDelays(ipMap)
            } finally {
                testing.value = false
            }
        }
    }

    /**
     * App 启动自动测速 + 自动选择最快线路/图源。
     * 成功：调用 RouteManager.setLine/setImgHost 并重建网络，返回 (最快线路索引, 最快图源索引)。
     * 全部失败：保持当前线路，返回 (-1, -1)。
     */
    suspend fun autoSelectFastest(): Pair<Int, Int> {
        autoSelecting.value = true
        return try {
            val (lines, imgs, ipMap) = runMeasure()
            lineDelays.value = lines
            imgDelays.value = imgs
            ipDelays.value = ipMap
            RouteManager.setLastDelays(lines, imgs)
            RouteManager.setLastIpDelays(ipMap)

            val bestLine = lines.filterValues { it < Long.MAX_VALUE }.minByOrNull { it.value }?.key
            val bestImg = imgs.filterValues { it < Long.MAX_VALUE }.minByOrNull { it.value }?.key

            if (bestLine == null && bestImg == null) {
                -1 to -1
            } else {
                if (bestLine != null) {
                    RouteManager.setLine(bestLine)
                    currentLineIndex.value = bestLine
                    NetworkModule.rebuild()
                }
                if (bestImg != null) {
                    RouteManager.setImgHost(bestImg)
                    currentImgIndex.value = bestImg
                }
                (bestLine ?: RouteManager.lineIndex) to (bestImg ?: RouteManager.imgIndex)
            }
        } finally {
            autoSelecting.value = false
        }
    }

    /**
     * DoH 自动更新 IP：遍历当前 customRule 中所有域名，通过指定 DoH 服务查询 A 记录，
     * 如果 IP 列表有变化则更新。
     * @param provider DoH 服务："alidns"=阿里云, "tencent"=腾讯, "google"=Google
     * @param useProxy 是否走本地代理（Google DoH 国内被墙，走代理可测试）
     */
    fun updateNetworkConfig(provider: String = "alidns", useProxy: Boolean = false) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                // 构建独立的 DoH OkHttpClient（3秒超时，trust-all）
                val trustAll = object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                }
                val sslContext = SSLContext.getInstance("TLS")
                sslContext.init(null, arrayOf<TrustManager>(trustAll), SecureRandom())

                val dohClientBuilder = OkHttpClient.Builder()
                    .connectTimeout(3, TimeUnit.SECONDS)
                    .readTimeout(3, TimeUnit.SECONDS)
                    .sslSocketFactory(sslContext.socketFactory, trustAll)
                    .hostnameVerifier { _, _ -> true }
                if (useProxy) {
                    dohClientBuilder.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", DevConfig.getPort())))
                }
                val dohClient = dohClientBuilder.build()

                val jsonParser = Json { ignoreUnknownKeys = true }
                // 遍历当前配置（DevConfig rule）中所有域名，包括 dns.google
                val domainsToUpdate = DevConfig.getRule().keys
                val newRuleMap = mutableMapOf<String, List<String>>()
                var successCount = 0
                var failCount = 0

                for (domain in domainsToUpdate) {
                    val oldIps = DevConfig.getRule()[domain] ?: emptyList()
                    // dns.google 本身不查询（DoH 服务域名），保留旧 IP
                    if (domain == "dns.google") {
                        newRuleMap[domain] = oldIps
                        continue
                    }
                    try {
                        val dohUrl = when (provider) {
                            "tencent" -> "https://doh.pub/dns-query?name=$domain&type=A"
                            "google" -> "https://dns.google/resolve?name=$domain&type=A"
                            else -> "https://dns.alidns.com/resolve?name=$domain&type=A"
                        }
                        val req = Request.Builder().url(dohUrl).build()
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

                // 应用新 rule 并重启代理
                RouteManager.setCustomRule(newRuleMap)
                // 保存到 DevConfig（保持配置一致），然后重启代理使新 IP 生效
                val currentSni = DevConfig.getSniDomains()
                val configJson = buildString {
                    append("{\"port\":${DevConfig.getPort()},\"rule\":{")
                    newRuleMap.entries.forEachIndexed { i, (domain, ips) ->
                        if (i > 0) append(",")
                        append("\"$domain\":[")
                        ips.forEachIndexed { j, ip ->
                            if (j > 0) append(",")
                            append("\"$ip\"")
                        }
                        append("]")
                    }
                    append("},\"sni\":[")
                    currentSni.forEachIndexed { i, d ->
                        if (i > 0) append(",")
                        append("\"$d\"")
                    }
                    append("]}")
                }
                DevConfig.saveConfig(configJson)
                DevConfig.restartProxy()
                // 清空旧的最快IP缓存，强制测速重新选择
                RouteManager.clearFastestIps()
                // 重建网络客户端，让新代理配置生效
                NetworkModule.rebuild()

                toast.value = "更新完成，成功${successCount}个，失败${failCount}个（代理已重启）"
                configUpdateTime.value = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                }
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
                DevConfig.applyConfig(jsonStr)
                // 重启代理使新配置生效
                DevConfig.restartProxy()
                // 同步更新 UI 状态，关闭再打开仍显示新配置
                devConfigJson.value = jsonStr
                val ruleCount = DevConfig.getRule().size
                val sniCount = DevConfig.getSniDomains().size
                toast.value = "配置已保存并重启代理（${ruleCount}条rule, ${sniCount}条sni, port=${DevConfig.getPort()}）"
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
