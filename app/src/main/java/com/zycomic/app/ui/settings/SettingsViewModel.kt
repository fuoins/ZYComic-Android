package com.zycomic.app.ui.settings

import com.zycomic.app.data.AllTags
import com.zycomic.app.data.repository.BookBlockRepository
import com.zycomic.app.data.repository.TagRepository
import com.zycomic.app.data.repository.UserRepository
import com.zycomic.app.net.DevConfig
import com.zycomic.app.net.DomainIp
import com.zycomic.app.net.NetworkModule
import com.zycomic.app.net.PlatformSpeedTestCoordinator
import com.zycomic.app.net.RemoteConfigFetcher
import com.zycomic.app.net.RouteManager
import com.zycomic.app.net.SpeedTestUiTextPolicy
import com.zycomic.app.net.SpeedTester
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // 过滤开关
    val filterEnabled = MutableStateFlow(TagRepository.isFilterEnabled())

    // ===== 漫画屏蔽（与标签屏蔽并行的第二套屏蔽，两个开关各管一种占位卡）=====
    /** 「过滤屏蔽漫画」开关（本地持久化，默认开启）。 */
    val bookFilterEnabled = MutableStateFlow(BookBlockRepository.isFilterEnabled())
    /** 已屏蔽漫画列表（删除页数据源）。 */
    val blockedBooks = MutableStateFlow<List<BookBlockRepository.BlockedBook>>(emptyList())
    /** 删除/清空屏蔽漫画进行中，用于禁用按钮避免重复提交。 */
    val removingBooks = MutableStateFlow(false)
    /** 加载屏蔽漫画列表中标识。 */
    val loadingBooks = MutableStateFlow(false)

    // 全部标签（内置，添加屏蔽用）
    val allTags = MutableStateFlow<List<String>>(AllTags.LIST)
    // 已屏蔽标签（删除用）
    val blockedTags = MutableStateFlow<List<String>>(emptyList())

    // 测速
    val currentLineIndex = MutableStateFlow(RouteManager.lineIndex)
    val currentLineIp = MutableStateFlow(RouteManager.selectedLineIp)
    /** 用户手动固定的图源域名；null = 自动（由测速结果决定）。 */
    val imgPinned = MutableStateFlow(RouteManager.pinnedImgDomain)
    val currentImgIndex = MutableStateFlow(RouteManager.imgIndex)
    val currentImgIp = MutableStateFlow(RouteManager.selectedImgIp)
    val autoSelectEnabled = MutableStateFlow(RouteManager.autoSelectEnabled)
    val useFastestImgForAll = MutableStateFlow(RouteManager.useFastestImgForAll)
    /** 自动跟随系统 VPN 开关（默认开启；关闭=强制直连，用于抓包调试） */
    val followSystemVpn = MutableStateFlow(DevConfig.isFollowSystemVpnEnabled())
    /** VPN 单独变化后自动测速并应用排名；独立偏好默认关闭。 */
    val vpnOnlyAutoRetest = MutableStateFlow(DevConfig.isVpnOnlyAutoRetestEnabled())
    /** 手动测速进行中（设置页"重新测速"按钮） */
    val testing = MutableStateFlow(false)
    /** 启动时自动测速进行中（全屏加载层） */
    val autoSelecting = MutableStateFlow(false)
    /** 当前测速阶段与按真实解析端点统计的进度。 */
    val speedTestProgress = MutableStateFlow(SpeedTester.Progress())

    fun speedTestProgressMessage(progress: SpeedTester.Progress): String = when (progress.phase) {
        SpeedTester.Phase.PREPARING_LINES -> "正在解析线路域名和 IP…"
        SpeedTester.Phase.TESTING_LINES -> if (progress.total == 0) {
            "未解析到线路 IP，准备继续检测图源"
        } else {
            "正在测试线路 IP：${progress.completed}/${progress.total}（可用 ${progress.successful}，失败 ${progress.failed}）"
        }
        SpeedTester.Phase.APPLYING_LINE -> "线路检测完成（可用 ${progress.successful}，失败 ${progress.failed}），正在更新测速排名"
        SpeedTester.Phase.FETCHING_SAMPLE -> "正在获取图源验证样本…"
        SpeedTester.Phase.PREPARING_IMAGES -> "正在解析图源域名和 IP…"
        SpeedTester.Phase.TESTING_IMAGES -> if (progress.total == 0) {
            "未解析到图源 IP，正在整理测速结果"
        } else {
            "正在测试图源 IP：${progress.completed}/${progress.total}（可用 ${progress.successful}，连不上 ${progress.failed}，无图 ${progress.noImage}）"
        }
        SpeedTester.Phase.APPLYING_RESULTS -> "端点检测完成，正在更新排名并应用可自动切换的结果…"
        SpeedTester.Phase.COMPLETE -> if (progress.fullRetestRecommended) {
            "前五快测全部失败，本轮不再扩测；请在测速表中选择完整重新测速"
        } else if (progress.reusedImages > 0) {
            "图源本次新测 ${progress.freshImages} 项，另 ${progress.reusedImages} 项保留历史排名（本轮未测）；可手动完整重测"
        } else {
            "测速完成；自动模式应用最快结果，临时手动选择保持不变"
        }
        SpeedTester.Phase.FAILED -> "测速中断，继续使用当前线路和图源"
        SpeedTester.Phase.SKIPPED -> "已有测速任务正在运行，本轮未重复检测"
    }
    /** 线路延迟：域名/IP -> 毫秒。 */
    val lineDelays = MutableStateFlow<Map<DomainIp, Long>>(RouteManager.lastLineIpDelays)
    /** 图源延迟：域名/IP -> 毫秒。 */
    val imgDelays = MutableStateFlow<Map<DomainIp, Long>>(RouteManager.lastImgIpDelays)
    /**
     * 本轮图源探活逐 IP 结论（域名/IP -> 连不上/无图/正常）。
     * 没有它的时候"超时"这一种显示会同时掩盖三件不同的事：连不上、连上了但拿不到图、这一轮压根没探。
     */
    val imgVerdicts = MutableStateFlow<Map<DomainIp, SpeedTester.ImgVerdict>>(RouteManager.lastImgIpVerdicts)

    /** 远程 ruledns 手动检查进行中 */
    val remoteChecking = MutableStateFlow(false)
    /** 远程配置版本/更新时间/错误的汇总文案 */
    val remoteInfo = MutableStateFlow(buildRemoteInfo())
    /** 最近一次完整测速结果实际使用的远程配置版本。 */
    val speedTestResultVersion = MutableStateFlow(RouteManager.speedTestSnapshot.value.remoteVersion)

    init {
        RouteManager.refreshReusableImageSpeedTestSnapshot()
        scope.launch {
            RouteManager.lineSelectionState.collect { selection ->
                currentLineIndex.value = selection.index
                currentLineIp.value = selection.ip
            }
        }
        scope.launch {
            RouteManager.speedTestSnapshot.collect { snapshot ->
                lineDelays.value = snapshot.lineDelays
                imgDelays.value = snapshot.imgDelays
                imgVerdicts.value = snapshot.imgVerdicts
                speedTestResultVersion.value = snapshot.remoteVersion
            }
        }
        scope.launch {
            SpeedTester.progress.collect { progress ->
                speedTestProgress.value = progress
            }
        }
        scope.launch {
            DevConfig.remoteVersionFlow.collect { refreshRemoteInfo() }
        }
    }

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
                when (r.outcome) {
                    RemoteConfigFetcher.Outcome.APPLIED -> {
                        testing.value = true
                        RemoteConfigFetcher.beginConfigRetest(r.version, duringStartup = false)
                        try {
                            PlatformSpeedTestCoordinator.run(
                                remoteConfigUpdated = r.configurationUpdated,
                                onProgress = { lines, images, verdicts ->
                                    lineDelays.value = lines
                                    imgDelays.value = images
                                    imgVerdicts.value = verdicts
                                },
                                onStageProgress = { speedTestProgress.value = it },
                            )
                            syncSelectedRoutes()
                        } finally {
                            RemoteConfigFetcher.finishConfigRetest(r.version)
                        }
                    }
                    RemoteConfigFetcher.Outcome.NOT_CHANGED -> toast.value = "网络配置已是最新"
                    RemoteConfigFetcher.Outcome.FAILED -> toast.value = "更新失败：${r.message}"
                }
            } catch (e: Exception) {
                android.util.Log.e("SettingsViewModel", "checkRemoteConfig failed", e)
                toast.value = "更新失败：${e.message ?: "请稍后重试"}"
            } finally {
                testing.value = false
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
            try {
                blockedTags.value = TagRepository.getBlackTags()
            } catch (e: Exception) {
                android.util.Log.e("SettingsViewModel", "loadBlockedTags failed", e)
            }
        }
    }

    // ==================== 漫画屏蔽 ====================

    /** 切换「过滤屏蔽漫画」：内存 + SharedPreferences 由仓库负责，这里只同步 UI 状态。 */
    fun setBookFilterEnabled(enabled: Boolean) {
        BookBlockRepository.setFilterEnabled(enabled)
        bookFilterEnabled.value = enabled
        toast.value = if (enabled) "已开启：隐藏被屏蔽漫画" else "已关闭：被屏蔽漫画会以占位卡显示"
    }

    /** 拉取已屏蔽漫画列表（删除页进入时与每次变更后调用）。 */
    fun loadBlockedBooks() {
        scope.launch {
            loadingBooks.value = true
            try {
                blockedBooks.value = BookBlockRepository.getBlockedBooks()
            } catch (e: Exception) {
                android.util.Log.e("SettingsViewModel", "loadBlockedBooks failed", e)
                toast.value = e.message ?: "获取屏蔽漫画列表失败"
            } finally {
                loadingBooks.value = false
            }
        }
    }

    /** 删除选中的屏蔽漫画（服务端是逐个 toggle，串行提交）。 */
    fun submitUnblockBooks(books: List<BookBlockRepository.BlockedBook>, onSuccess: () -> Unit) {
        if (books.isEmpty()) return
        scope.launch {
            removingBooks.value = true
            try {
                val (ok, failed) = BookBlockRepository.unblockBooks(books)
                toast.value = if (failed == 0) "已取消屏蔽 $ok 本漫画" else "已取消 $ok 本，失败 $failed 本"
                loadBlockedBooks()
                if (ok > 0) onSuccess()
            } catch (e: Exception) {
                android.util.Log.e("SettingsViewModel", "submitUnblockBooks failed", e)
                toast.value = e.message ?: "删除屏蔽漫画失败"
            } finally {
                removingBooks.value = false
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

    /** 手动切换只覆盖当前进程；重启后自动模式仍会恢复最快线路。 */
    fun selectLine(index: Int, ip: String? = null) {
        val url = RouteManager.lineHosts.getOrNull(index)
        val domain = url?.let { runCatching { java.net.URI(it).host }.getOrNull() }
        if (ip != null && domain != null) RouteManager.setLineEndpoint(domain, ip) else RouteManager.setLine(index)
        currentLineIndex.value = RouteManager.lineIndex
        currentLineIp.value = RouteManager.selectedLineIp
        toast.value = if (RouteManager.autoSelectEnabled) "已临时切换到线路 ${RouteManager.lineIndex + 1}（重启后恢复自动选择）" else "已切换到线路 ${RouteManager.lineIndex + 1}"
        // rebuild() 内部要关 socket（evictAll/cancelAll），属网络操作：必须在 IO，
        // 直接在主线程调会抛 NetworkOnMainThreadException。登录态校验一并放同一个 IO 块，
        // 保证"网络已按新线路重建"发生在校验之前。
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching { NetworkModule.rebuildApi("手动选线") }
                    .onFailure { android.util.Log.e("SettingsVM", "rebuild after selectLine failed", it) }
                runCatching { NetworkModule.cookieJar.syncToAllLines() }
                runCatching { UserRepository.verifyLogin() }
            }
        }
    }

    fun setAutoSelectEnabled(v: Boolean) {
        val previousIndex = RouteManager.lineIndex
        val previousIp = RouteManager.selectedLineIp
        RouteManager.setAutoSelectEnabled(v)
        autoSelectEnabled.value = v
        currentLineIndex.value = RouteManager.lineIndex
        currentLineIp.value = RouteManager.selectedLineIp
        toast.value = if (v) "已开启自动选线" else "已关闭自动选线（手动模式）"
        if (v && (previousIndex != RouteManager.lineIndex || previousIp != RouteManager.selectedLineIp)) {
            scope.launch {
                withContext(Dispatchers.IO) {
                    runCatching { NetworkModule.rebuildApi("开启自动选线") }
                    runCatching { NetworkModule.cookieJar.syncToAllLines() }
                }
            }
        }
    }

    fun setUseFastestImgForAll(v: Boolean) {
        RouteManager.updateUseFastestImgForAll(v)
        useFastestImgForAll.value = v
        toast.value = if (v) "已开启：封面和章节用测速最快图源" else "已关闭：用服务端推荐图源"
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching { NetworkModule.rebindImageRoute("切换自动图源模式") }
            }
        }
    }

    /**
     * 「自动跟随系统 VPN」开关：持久化并立即按新生效策略重建 client，随后重测速，
     * 保证线路/图域选择与当前策略匹配。关闭=强制直连（抓包模式）。
     */
    fun setFollowSystemVpn(v: Boolean) {
        DevConfig.setFollowSystemVpnEnabled(v)
        followSystemVpn.value = v
        toast.value = if (v) "已开启自动跟随系统 VPN" else "已关闭：强制直连（抓包模式）"
        scope.launch {
            imgVerdicts.value = emptyMap()
            NetworkModule.onVpnOrNetworkChanged("follow vpn toggled")
            PlatformSpeedTestCoordinator.run(forceFresh = true)
            syncSelectedRoutes()
        }
    }

    /** 只切换 VPN-only 变化后的自动测速行为，不改变路由策略或连接清理。 */
    fun setVpnOnlyAutoRetest(enabled: Boolean) {
        DevConfig.setVpnOnlyAutoRetestEnabled(enabled)
        vpnOnlyAutoRetest.value = enabled
    }

    /** 手动切换图源：只作本次进程临时覆盖，不关闭自动选择开关。 */
    fun selectImgHost(index: Int, ip: String? = null) {
        RouteManager.pinImgHost(index, ip)
        currentImgIndex.value = RouteManager.imgIndex
        currentImgIp.value = RouteManager.selectedImgIp
        imgPinned.value = RouteManager.pinnedImgDomain
        // 清除内存 + 磁盘缓存，强制用新图源重新加载图片
        DevConfig.clearImageCaches()
        toast.value = if (RouteManager.useFastestImgForAll) {
            "已临时切换到图源 ${RouteManager.imgIndex + 1}（重启后恢复自动选择）"
        } else {
            "已固定图源 ${RouteManager.imgIndex + 1}"
        }
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching { NetworkModule.rebindImageRoute("手动固定图源") }
            }
        }
    }

    /** 恢复自动：清除手动 pin，重新由测速结果决定图源。 */
    fun resetImgHostToAuto() {
        RouteManager.clearPinnedImgDomain()
        imgPinned.value = null
        currentImgIndex.value = RouteManager.imgIndex
        currentImgIp.value = RouteManager.selectedImgIp
        toast.value = "已恢复自动选择图源"
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching { NetworkModule.rebindImageRoute("恢复自动图源") }
            }
        }
    }

    /** setImgHost 别名（语义化命名）：切换图源并清缓存。 */
    fun setImgHost(index: Int) = selectImgHost(index)

    // ==================== HTTP 测速（主体在 SpeedTester.runAndApply，三处复用）====================

    /** 设置页"重新测速"按钮：测速后自动选最快、重建网络并 Toast。 */
    fun runSpeedTest(
        onlyPlatform: com.zycomic.app.platform.ContentPlatform? = null,
    ) {
        scope.launch {
            testing.value = true
            speedTestProgress.value = SpeedTester.Progress(SpeedTester.Phase.PREPARING_LINES)
            lineDelays.value = emptyMap()
            imgDelays.value = emptyMap()
            imgVerdicts.value = emptyMap()
            try {
                PlatformSpeedTestCoordinator.run(
                    forceFresh = true,
                    onlyPlatform = onlyPlatform,
                    onProgress = { l, i, v ->
                        lineDelays.value = l
                        imgDelays.value = i
                        imgVerdicts.value = v
                    },
                    onStageProgress = { speedTestProgress.value = it },
                )
                syncSelectedRoutes()
            } catch (e: Exception) {
                android.util.Log.e("SpeedTest", "runSpeedTest failed", e)
                speedTestProgress.value = SpeedTester.Progress(SpeedTester.Phase.FAILED)
                toast.value = "测速异常：${e.message ?: "请稍后重试"}"
            } finally {
                if (SpeedTester.lastRunSkipped) {
                    speedTestProgress.value = SpeedTester.Progress(SpeedTester.Phase.SKIPPED)
                }
                testing.value = false
            }
        }
    }

    /**
     * 把本轮逐域结论发布到 UI，并返回一句人类可读的摘要（可用/连不上/无图 各多少）。
     * "连不上"与"无图"必须分开：前者是网络可达性问题（换 VPN 才有解），
     * 后者是链路通但该域没有这个图片对象/被防盗链（是探活目标或 Referer 的问题）。
     */
    private fun publishImgVerdicts(): String {
        val v = SpeedTester.lastImgIpVerdicts
        imgVerdicts.value = v
        if (v.isEmpty()) return "本轮未采集到图源结论"
        val ok = v.count { it.value == SpeedTester.ImgVerdict.OK }
        val un = v.count { it.value == SpeedTester.ImgVerdict.UNREACHABLE }
        val no = v.count { it.value == SpeedTester.ImgVerdict.NO_IMAGE }
        return "图源 可用$ok/连不上$un/无图$no"
    }

    /**
     * App 启动自动测速 + 自动选择最快线路/图源（主体见 SpeedTester.runAndApply）。
     */
    suspend fun autoSelectFastest() {
        autoSelecting.value = true
        try {
            val reports = PlatformSpeedTestCoordinator.awaitStartup()
            speedTestProgress.value = SpeedTester.progress.value
            if (reports.any { it.platform == "漫蛙" && it.status == "tested" }) {
                SpeedTestUiTextPolicy.startupNoAddressToast(
                    hasLineAddress = RouteManager.lastLineIpDelays.isNotEmpty(),
                )?.let { toast.value = it }
            }
            syncSelectedRoutes()
        } finally {
            autoSelecting.value = false
        }
    }

    private fun syncSelectedRoutes() {
        currentLineIndex.value = RouteManager.lineIndex
        currentLineIp.value = RouteManager.selectedLineIp
        currentImgIndex.value = RouteManager.imgIndex
        currentImgIp.value = RouteManager.selectedImgIp
        imgPinned.value = RouteManager.pinnedImgDomain
        lineDelays.value = RouteManager.lastLineIpDelays
        imgDelays.value = RouteManager.lastImgIpDelays
        imgVerdicts.value = RouteManager.lastImgIpVerdicts
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
