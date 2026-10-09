package com.zycomic.app.ui.settings

import com.zycomic.app.data.AllTags
import com.zycomic.app.data.repository.TagRepository
import com.zycomic.app.data.repository.UserRepository
import com.zycomic.app.net.Crypto
import com.zycomic.app.net.DevConfig
import com.zycomic.app.net.ManwaInterceptor
import com.zycomic.app.net.NetworkModule
import com.zycomic.app.net.RemoteConfigFetcher
import com.zycomic.app.net.RouteManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Request

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
    val autoSelectEnabled = MutableStateFlow(RouteManager.autoSelectEnabled)
    val useFastestImgForAll = MutableStateFlow(RouteManager.useFastestImgForAll)
    /** 手动测速进行中（设置页"重新测速"按钮） */
    val testing = MutableStateFlow(false)
    /** 启动时自动测速进行中（全屏加载层） */
    val autoSelecting = MutableStateFlow(false)
    /** 线路延迟：index -> 毫秒，失败为 Long.MAX_VALUE（初始化时从 RouteManager 恢复上次测速结果） */
    val lineDelays = MutableStateFlow<Map<Int, Long>>(RouteManager.lastLineDelays)
    /** 图源延迟：index -> 毫秒，失败为 Long.MAX_VALUE（初始化时从 RouteManager 恢复上次测速结果） */
    val imgDelays = MutableStateFlow<Map<Int, Long>>(RouteManager.lastImgDelays)
    val configUpdateTime = MutableStateFlow("未更新")

    /** 远程 ruledns 手动检查进行中 */
    val remoteChecking = MutableStateFlow(false)
    /** 远程配置版本/更新时间/错误的汇总文案 */
    val remoteInfo = MutableStateFlow(buildRemoteInfo())

    /** 重新读取远程配置状态文案（版本/更新时间/错误）。 */
    fun refreshRemoteInfo() {
        remoteInfo.value = buildRemoteInfo()
    }

    private fun buildRemoteInfo(): String {
        val v = DevConfig.remoteVersion()
        val updated = DevConfig.remoteUpdatedAt()
        val err = DevConfig.remoteError()
        val parts = mutableListOf<String>()
        parts += if (v > 0) "远程配置 v$v" else "当前为内置配置"
        if (!updated.isNullOrBlank()) parts += "更新于 $updated"
        if (!err.isNullOrBlank()) parts += "上次错误：$err"
        return parts.joinToString("　")
    }

    /** 测速页"检查更新"：手动拉取远程 ruledns（成功/未更新/失败均 Toast）。 */
    fun checkRemoteConfig() {
        scope.launch {
            remoteChecking.value = true
            try {
                val r = RemoteConfigFetcher.checkAndApply(manual = true)
                toast.value = when (r.outcome) {
                    RemoteConfigFetcher.Outcome.APPLIED -> "网络配置已更新（v${r.version}）"
                    RemoteConfigFetcher.Outcome.NOT_CHANGED -> "网络配置已是最新"
                    RemoteConfigFetcher.Outcome.FAILED -> "更新失败：${r.message}"
                }
            } catch (e: Exception) {
                android.util.Log.e("SettingsViewModel", "checkRemoteConfig failed", e)
                toast.value = "更新失败：${e.message ?: "请稍后重试"}"
            } finally {
                remoteChecking.value = false
                refreshRemoteInfo()
            }
        }
    }

    // 屏蔽标签弹窗提交状态
    val addingBlacklist = MutableStateFlow(false)
    val removingBlacklist = MutableStateFlow(false)

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

    /** 切换线路：重建网络后验证登录态，失效则自动登出。手动选线会关闭自动模式。 */
    fun selectLine(index: Int) {
        if (autoSelectEnabled.value) {
            RouteManager.setAutoSelectEnabled(false)
            autoSelectEnabled.value = false
        }
        RouteManager.setLine(index)
        currentLineIndex.value = index
        com.zycomic.app.net.NetworkModule.rebuild()
        com.zycomic.app.net.NetworkModule.cookieJar.syncToAllLines()
        toast.value = "已切换到线路 ${index + 1}"
        // 切换线路后验证登录态（cookie 可能在新线路失效）
        scope.launch {
            try {
                UserRepository.verifyLogin()
            } catch (_: Exception) {}
        }
    }

    fun setAutoSelectEnabled(v: Boolean) {
        RouteManager.setAutoSelectEnabled(v)
        autoSelectEnabled.value = v
        toast.value = if (v) "已开启自动选线" else "已关闭自动选线（手动模式）"
    }

    fun setUseFastestImgForAll(v: Boolean) {
        RouteManager.updateUseFastestImgForAll(v)
        useFastestImgForAll.value = v
        toast.value = if (v) "已开启：封面和章节用测速最快图源" else "已关闭：用服务端推荐图源"
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

    /** 方案B域名级测速：RuleDns client 直接 GET，不逐 IP。协程层 3s 硬超时（F2），超时/异常返回 Long.MAX_VALUE。 */
    private suspend fun measureLineDomain(lineUrl: String): Long {
        android.util.Log.d("SpeedTest", "measureLine start url=$lineUrl")
        val ts = System.currentTimeMillis().toString()
        val url = "$lineUrl/api/index/index?facility=android&deviceid=${ManwaInterceptor.DEVICE_ID}&timestamp=$ts"
        val req = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", ManwaInterceptor.UA)
            .header("devid", ts)
            .header("X-Token", Crypto.md5Hex(ts + Crypto.XTOKEN_SALT))
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", ManwaInterceptor.ACCEPT_LANGUAGE)
            .header("Origin", ManwaInterceptor.ORIGIN)
            .header("Referer", ManwaInterceptor.REFERER)
            .header("Connection", "keep-alive")
            .build()
        val start = System.nanoTime()
        return try {
            withTimeout(3000) {
                withContext(Dispatchers.IO) {
                    NetworkModule.newSpeedTestClient().newCall(req).execute().use { it.body?.bytes() }
                    val d = (System.nanoTime() - start) / 1_000_000
                    android.util.Log.d("SpeedTest", "measureLine done url=$lineUrl delay=${d}ms")
                    d
                }
            }
        } catch (e: Exception) {
            // 含 withTimeout 的 TimeoutCancellationException（系统 DNS 卡住）与 OkHttp 异常
            android.util.Log.d("SpeedTest", "measureLine fail url=$lineUrl err=${e.message}")
            Long.MAX_VALUE
        }
    }

    private suspend fun measureImgDomain(domain: String): Long {
        android.util.Log.d("SpeedTest", "measureImg start domain=$domain")
        val req = Request.Builder().url("https://$domain/").get()
            .header("User-Agent", ManwaInterceptor.UA)
            .build()
        val start = System.nanoTime()
        return try {
            withTimeout(3000) {
                withContext(Dispatchers.IO) {
                    NetworkModule.newSpeedTestClient().newCall(req).execute().use { it.body?.bytes() }
                    val d = (System.nanoTime() - start) / 1_000_000
                    android.util.Log.d("SpeedTest", "measureImg done domain=$domain delay=${d}ms")
                    d
                }
            }
        } catch (e: Exception) {
            android.util.Log.d("SpeedTest", "measureImg fail domain=$domain err=${e.message}")
            Long.MAX_VALUE
        }
    }

    private suspend fun runMeasure(): Pair<Map<Int, Long>, Map<Int, Long>> = coroutineScope {
        android.util.Log.d("SpeedTest", "runMeasure start, lines=${RouteManager.lineHosts.size}, imgs=${RouteManager.imgDomains.size}")
        // F1：先清空，随后每条结果完成即增量发布（不可变 copy + 原子 update），UI 逐条刷新
        lineDelays.value = emptyMap()
        imgDelays.value = emptyMap()

        val lineJobs = RouteManager.lineHosts.mapIndexed { index, lineUrl ->
            async {
                val v = measureLineDomain(lineUrl)
                lineDelays.update { it + (index to v) }
                index to v
            }
        }
        val imgJobs = RouteManager.imgDomains.mapIndexed { index, domain ->
            async {
                val v = measureImgDomain(domain)
                imgDelays.update { it + (index to v) }
                index to v
            }
        }
        val lineResults = lineJobs.awaitAll().toMap()
        val imgResults = imgJobs.awaitAll().toMap()
        android.util.Log.d("SpeedTest", "runMeasure done, lines=$lineResults, imgs=$imgResults")
        lineResults to imgResults
    }

    /** 设置页"重新测速"按钮：测速后自动选最快、重建网络并 Toast（P1）。 */
    fun runSpeedTest() {
        scope.launch {
            testing.value = true
            try {
                val (bestLine, bestImg) = measureAndSelectFastest()
                val hasLine = bestLine >= 0 && RouteManager.lastLineDelays[bestLine]?.let { it < Long.MAX_VALUE } == true
                toast.value = if (hasLine) {
                    "已选择线路 ${bestLine + 1}、图源 ${bestImg + 1}"
                } else {
                    "测速失败，使用当前线路"
                }
            } catch (e: Exception) {
                android.util.Log.e("SpeedTest", "runSpeedTest failed", e)
                toast.value = "测速异常：${e.message ?: "请稍后重试"}"
            } finally {
                // F3 兜底：任何缺失 key 统一补"超时"，杜绝"--"
                fillMissingAsTimeout()
                testing.value = false
            }
        }
    }

    /**
     * P1：测速 + 自动选择最快线路/图源的共享主体（启动自动测速与设置页"重新测速"复用）。
     * 成功：调用 RouteManager.setLine/setImgHost 并重建网络，返回 (最快线路索引, 最快图源索引)。
     * 全部失败：保持当前线路，返回 (-1, -1)。
     */
    private suspend fun measureAndSelectFastest(): Pair<Int, Int> {
        val (lines, imgs) = runMeasure()
        lineDelays.value = lines
        imgDelays.value = imgs
        RouteManager.setLastDelays(lines, imgs)

        val bestLine = lines.filterValues { it < Long.MAX_VALUE }.minByOrNull { it.value }?.key
        val bestImg = imgs.filterValues { it < Long.MAX_VALUE }.minByOrNull { it.value }?.key

        if (bestLine != null) {
            RouteManager.setLine(bestLine)
            currentLineIndex.value = bestLine
        }
        if (bestImg != null && bestImg != RouteManager.imgIndex) {
            RouteManager.setImgHost(bestImg)
            currentImgIndex.value = bestImg
        }
        if (bestLine != null || bestImg != null) {
            NetworkModule.rebuild()
        }
        val r = (bestLine ?: -1) to (bestImg ?: -1)
        android.util.Log.d("SpeedTest", "measureAndSelectFastest done, bestLine=${r.first}, bestImg=${r.second}")
        return r
    }

    /**
     * App 启动自动测速 + 自动选择最快线路/图源。
     */
    suspend fun autoSelectFastest(): Pair<Int, Int> {
        android.util.Log.d("SpeedTest", "autoSelectFastest start")
        autoSelecting.value = true
        return try {
            measureAndSelectFastest()
        } finally {
            // F3：被 8s 整体超时取消时，NonCancellable 中保留已增量发布的结果，缺失 key 补"超时"
            withContext(NonCancellable) {
                fillMissingAsTimeout()
            }
            autoSelecting.value = false
        }
    }

    /**
     * F3：对当前线路/图源列表中缺失延迟 key 的 index 统一补 Long.MAX_VALUE（显示"超时"），杜绝"--"。
     * StateFlow Map 用不可变 copy + 原子 update，保证线程安全。
     */
    fun fillMissingAsTimeout() {
        lineDelays.update { cur ->
            val m = cur.toMutableMap()
            RouteManager.lineHosts.indices.forEach { if (it !in m) m[it] = Long.MAX_VALUE }
            m
        }
        imgDelays.update { cur ->
            val m = cur.toMutableMap()
            RouteManager.imgDomains.indices.forEach { if (it !in m) m[it] = Long.MAX_VALUE }
            m
        }
    }

    companion object {
        @Volatile
        var autoTestDone: Boolean = false
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
            "创伤攻", "失恋攻", "美男受", "迷糊受", "小学生受", "虚张声势受",
            "女攻男受",
        )
    }
}
