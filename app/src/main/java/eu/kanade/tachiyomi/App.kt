package eu.kanade.tachiyomi

import android.annotation.SuppressLint
import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Looper
import android.webkit.WebView
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.work.Configuration
import androidx.work.WorkManager
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.allowRgb565
import coil3.request.crossfade
import coil3.util.DebugLogger
import com.elvishew.xlog.LogConfiguration
import com.elvishew.xlog.LogLevel
import com.elvishew.xlog.XLog
import com.elvishew.xlog.printer.AndroidPrinter
import com.elvishew.xlog.printer.Printer
import com.elvishew.xlog.printer.file.backup.NeverBackupStrategy
import com.elvishew.xlog.printer.file.naming.DateFileNameGenerator
import com.zycomic.app.net.DevConfig
import dev.mihon.injekt.patchInjekt
import eu.kanade.domain.DomainModule
import eu.kanade.domain.KMKDomainModule
import eu.kanade.domain.SYDomainModule
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.domain.ui.model.setAppCompatDelegateThemeMode
import eu.kanade.tachiyomi.core.security.PrivacyPreferences
import eu.kanade.tachiyomi.crash.CrashActivity
import eu.kanade.tachiyomi.crash.GlobalExceptionHandler
import eu.kanade.tachiyomi.data.coil.BufferedSourceFetcher
import eu.kanade.tachiyomi.data.coil.MangaCoverFetcher
import eu.kanade.tachiyomi.data.coil.MangaCoverKeyer
import eu.kanade.tachiyomi.data.coil.MangaCoverMetadata
import eu.kanade.tachiyomi.data.coil.MangaKeyer
import eu.kanade.tachiyomi.data.coil.PagePreviewFetcher
import eu.kanade.tachiyomi.data.coil.PagePreviewKeyer
import eu.kanade.tachiyomi.data.coil.TachiyomiImageDecoder
import eu.kanade.tachiyomi.data.connections.discord.DiscordRPCService
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.data.sync.SyncDataJob
import eu.kanade.tachiyomi.di.AppModule
import eu.kanade.tachiyomi.di.PreferenceModule
import eu.kanade.tachiyomi.di.SYPreferenceModule
import eu.kanade.tachiyomi.ui.base.delegate.SecureActivityDelegate
import eu.kanade.tachiyomi.util.CrashLogUtil
import eu.kanade.tachiyomi.util.system.DeviceUtil
import eu.kanade.tachiyomi.util.system.GLUtil
import eu.kanade.tachiyomi.util.system.WebViewUtil
import eu.kanade.tachiyomi.util.system.animatorDurationScale
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.isDebugBuildType
import eu.kanade.tachiyomi.util.system.isPreviewBuildType
import eu.kanade.tachiyomi.util.system.notify
import exh.log.EHLogLevel
import exh.log.EnhancedFilePrinter
import exh.log.XLogLogcatLogger
import exh.log.xLogD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import logcat.AndroidLogcatLogger
import logcat.LogPriority
import logcat.LogcatLogger
import mihon.core.migration.Migrator
import mihon.core.migration.migrations.migrations
import org.conscrypt.Conscrypt
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.i18n.MR
import timber.log.Timber
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.security.Security
import java.text.SimpleDateFormat
import java.util.Locale

class App : Application(), DefaultLifecycleObserver, SingletonImageLoader.Factory {

    private val basePreferences: BasePreferences by injectLazy()
    private val privacyPreferences: PrivacyPreferences by injectLazy()

    private val disableIncognitoReceiver = DisableIncognitoReceiver()

    @SuppressLint("LaunchActivityFromNotification")
    override fun onCreate() {
        super<Application>.onCreate()

        com.zycomic.app.platform.StartupPlatformPolicy.load(this)
        // 初始化开发者配置
        DevConfig.init(this)
        DevConfig.migrateProxyDefaultIfNeeded()
        com.zycomic.app.net.ImageCacheManager.init(this)
        // 初始化网络模块上下文（VPN 检测需要），并注册网络/VPN 变化监听
        com.zycomic.app.net.NetworkModule.init(this)
        com.zycomic.app.net.JmAuthStore.initialize(this)
        com.zycomic.app.picacg.PicacgRepository.init(this)
        registerNetworkCallback()
        runCatching {
            if (getSharedPreferences("zycomic_data", android.content.Context.MODE_PRIVATE)
                    .getBoolean("auto_clear_chapter_cache", true)
            ) {
                DevConfig.clearImageCaches()
            }
        }
        // 先恢复自动模式，再决定是否读取持久化的手动图源选择
        com.zycomic.app.net.RouteManager.loadUseFastestImgForAll()
        // 恢复自动选线偏好（开启时用上次最快线路作为当前线路）
        com.zycomic.app.net.RouteManager.loadAutoSelectPrefs()
        // 自动模式下忽略上次运行的临时图源覆盖；手动模式才恢复保存选择
        com.zycomic.app.net.RouteManager.loadImgIndex()
        // 恢复用户手动钉住的图源（pin 优先级高于派生最快域）
        com.zycomic.app.net.RouteManager.loadPinnedImgDomain()
        // 清除旧版缓存的线路/图源候选；有效列表已从当前 DevConfig 重建
        com.zycomic.app.net.RouteManager.loadImgDomainsFromPrefs()

        // 初始化用户仓库（本地用户信息存储）
        com.zycomic.app.data.repository.UserRepository.init(this)
        // 初始化搜索页显示模式持久化
        com.zycomic.app.ui.search.SearchViewModel.init(this)
        // 初始化分类页显示模式持久化
        com.zycomic.app.ui.browse.BrowseViewModel.init(this)
        // 初始化收藏页显示模式持久化
        com.zycomic.app.ui.library.LibraryViewModel.init(this)
        // 先从本地恢复登录态，UI 立即显示登录（没网也显示）
        com.zycomic.app.data.repository.UserRepository.restoreLoginFromLocal()
        // 无有效远程快照的首次安装先等待配置；配置就绪后再进行用户 API 校验。
        ProcessLifecycleOwner.get().lifecycleScope.launch {
            DevConfig.awaitUsableRouteConfig()
            if (com.zycomic.app.platform.StartupPlatformPolicy.isEnabled(com.zycomic.app.platform.ContentPlatform.MANWA)) {
                com.zycomic.app.data.repository.UserRepository.verifyLogin()
            }
        }
        // A toggle changed during a probe invalidates that generation and schedules enabled pools only.
        ProcessLifecycleOwner.get().lifecycleScope.launch {
            var previous = com.zycomic.app.platform.StartupPlatformPolicy.state.value
            com.zycomic.app.platform.StartupPlatformPolicy.state.collect { policy ->
                if (policy != previous) {
                    previous = policy
                    launch(Dispatchers.IO) {
                        runCatching { com.zycomic.app.net.PlatformSpeedTestCoordinator.run() }
                    }
                }
            }
        }
        // 已保存配置可先只测不应用，与权威配置检查重叠；版本变化会丢弃整代预热。
        ProcessLifecycleOwner.get().lifecycleScope.launch(Dispatchers.IO) {
            com.zycomic.app.net.SpeedTestTimingLog.start(com.zycomic.app.net.SpeedTestTimingLog.Scope.STARTUP).use { timings ->
                val warmup = launch {
                    try {
                        com.zycomic.app.net.PlatformSpeedTestCoordinator.awaitStartup()
                    } catch (error: kotlinx.coroutines.CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        // A speculative probe failure must not cancel the config check.
                    }
                }
                var configurationUpdated = false
                val result = try {
                    timings.start(com.zycomic.app.net.SpeedTestTimings.Phase.CONFIG_CHECK).use {
                        runCatching { com.zycomic.app.net.RemoteConfigFetcher.checkAndApply(manual = false) }.getOrNull()
                    }
                        .also { configurationUpdated = it?.configurationUpdated == true }
                } finally {
                    // Unblock selection only after the authoritative generation is known.
                    // Cached probes from a different generation cannot cross this barrier.
                    com.zycomic.app.net.PlatformSpeedTestCoordinator.finishStartupConfigCheck(configurationUpdated)
                }
                if (result?.configurationUpdated == true) {
                    com.zycomic.app.net.RemoteConfigFetcher.beginConfigRetest(result.version, duringStartup = true)
                    try {
                        runCatching {
                            com.zycomic.app.net.PlatformSpeedTestCoordinator.run(remoteConfigUpdated = result.configurationUpdated)
                        }
                    } finally {
                        com.zycomic.app.net.RemoteConfigFetcher.finishConfigRetest(result.version)
                    }
                }
                warmup.join()
            }
        }

        patchInjekt()

        // KMK -->
        if (isDebugBuildType) Timber.plant(Timber.DebugTree())
        // KMK <--

        GlobalExceptionHandler.initialize(applicationContext, CrashActivity::class.java)

        // TLS 1.3 support for Android < 10
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Security.insertProviderAt(Conscrypt.newProvider(), 1)
        }

        // Avoid potential crashes
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val process = getProcessName()
            if (packageName != process) WebView.setDataDirectorySuffix(process)
        }

        Injekt.importModule(PreferenceModule(this))
        Injekt.importModule(AppModule(this))
        Injekt.importModule(DomainModule())
        // KMK -->
        Injekt.importModule(KMKDomainModule())
        // KMK <--
        // SY -->
        Injekt.importModule(SYPreferenceModule(this))
        Injekt.importModule(SYDomainModule())
        // SY <--

        setupExhLogging() // EXH logging
        if (!LogcatLogger.isInstalled) {
            val minLogPriority = when {
                // KMK -->
                EHLogLevel.isExtraLogging() -> LogPriority.VERBOSE
                // KMK <--
                BuildConfig.DEBUG -> LogPriority.DEBUG
                else -> LogPriority.INFO
            }
            LogcatLogger.install()
            LogcatLogger.loggers += XLogLogcatLogger() // SY Redirect Logcat to XLog
            LogcatLogger.loggers += AndroidLogcatLogger(minLogPriority)
        }

        setupNotificationChannels()

        ProcessLifecycleOwner.get().lifecycle.addObserver(this)

        val scope = ProcessLifecycleOwner.get().lifecycleScope

        // Show notification to disable Incognito Mode when it's enabled
        basePreferences.incognitoMode().changes()
            .onEach { enabled ->
                if (enabled) {
                    disableIncognitoReceiver.register()
                    notify(
                        Notifications.ID_INCOGNITO_MODE,
                        Notifications.CHANNEL_INCOGNITO_MODE,
                    ) {
                        setContentTitle(stringResource(MR.strings.pref_incognito_mode))
                        setContentText(stringResource(MR.strings.notification_incognito_text))
                        setSmallIcon(R.drawable.ic_glasses_with_hat_24dp)
                        setColor(ContextCompat.getColor(applicationContext, R.color.ic_launcher))
                        setLargeIcon(BitmapFactory.decodeResource(applicationContext.resources, R.drawable.komikku))
                        setOngoing(true)

                        val pendingIntent = PendingIntent.getBroadcast(
                            this@App,
                            0,
                            Intent(ACTION_DISABLE_INCOGNITO_MODE).setPackage(BuildConfig.APPLICATION_ID),
                            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE,
                        )
                        setContentIntent(pendingIntent)
                    }
                } else {
                    disableIncognitoReceiver.unregister()
                    cancelNotification(Notifications.ID_INCOGNITO_MODE)
                }
            }
            .launchIn(scope)

        basePreferences.hardwareBitmapThreshold().let { preference ->
            if (!preference.isSet()) preference.set(GLUtil.DEVICE_TEXTURE_LIMIT)
        }

        basePreferences.hardwareBitmapThreshold().changes()
            .onEach { ImageUtil.hardwareBitmapThreshold = it }
            .launchIn(scope)

        setAppCompatDelegateThemeMode(Injekt.get<UiPreferences>().themeMode().get())

        // KMK -->
        MangaCoverMetadata.load()
        // KMK <--

        // Updates widget update

        if (!WorkManager.isInitialized()) {
            WorkManager.initialize(this, Configuration.Builder().build())
        }
        val syncPreferences: SyncPreferences = Injekt.get()
        val syncTriggerOpt = syncPreferences.getSyncTriggerOptions()
        if (syncPreferences.isSyncEnabled() && syncTriggerOpt.syncOnAppStart) {
            SyncDataJob.startNow(this@App)
        }

        initializeMigrator()
    }

    private val networkScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + Dispatchers.IO,
    )
    private val networkHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private data class DefaultNetworkState(
        val network: android.net.Network?,
        val isVpn: Boolean?,
        /** 非 VPN 网络自身，或唯一可观察到的非 VPN Internet 网络；null 表示无法可靠识别。 */
        val underlyingNetworks: Set<android.net.Network>?,
    )

    private val networkRetestLock = Any()
    private val observedNonVpnNetworks = mutableSetOf<android.net.Network>()

    /** 最近一次默认网络状态，用于去重回调并区分可确认的 VPN-only 变化。 */
    @Volatile
    private var lastDefaultNetworkState: DefaultNetworkState? = null

    /** 防抖窗口内只要出现真实/未能可靠分类的网络变化，就保留自动测速请求。 */
    private var pendingNetworkRetest = false

    private val networkRetestRunnable = Runnable {
        val shouldRetest = synchronized(networkRetestLock) {
            val pending = pendingNetworkRetest
            pendingNetworkRetest = false
            pending
        }
        networkScope.launch {
            try {
                // VPN-only 关闭时仍按当前策略处理网络栈并清理旧连接，只跳过完整测速。
                com.zycomic.app.net.NetworkModule.onVpnOrNetworkChanged(
                    if (shouldRetest) "default network changed" else "VPN-only change",
                )
                if (!shouldRetest) {
                    android.util.Log.i("App", "VPN-only change: network stack refreshed, automatic retest disabled")
                    return@launch
                }
                // Share startup's completed/in-flight generation only for the same actual network.
                // A real network/config change has a different key and must never be hidden by a timer.
                val r = com.zycomic.app.net.PlatformSpeedTestCoordinator.run()
                android.util.Log.i("App", "network changed retest done: ${r.joinToString { it.summary() }}")
            } catch (e: Exception) {
                android.util.Log.w("App", "network changed retest failed: ${e.message}")
            }
        }
    }

    /**
     * 注册默认网络回调：变化后 2s 防抖，先按生效策略清理/重建网络栈。
     * 真实网络变化始终自动重测速；只有能确认底层网络未变的 VPN-only 变化才受单独开关控制。
     */
    private fun registerNetworkCallback() {
        try {
            val cm = getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
                as? android.net.ConnectivityManager ?: return
            // Registration immediately replays the current networks. Seed the baseline so these
            // initial notifications do not enqueue a second round behind the startup probes.
            synchronized(networkRetestLock) {
                observedNonVpnNetworks.clear()
                cm.allNetworks.filterTo(observedNonVpnNetworks) { network ->
                    cm.getNetworkCapabilities(network)?.let { caps ->
                        caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    } == true
                }
                val active = cm.activeNetwork
                val vpn = active?.let(cm::getNetworkCapabilities)
                    ?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)
                val underlying = when {
                    active == null || vpn == null -> null
                    !vpn -> setOf(active)
                    else -> observedNonVpnNetworks.singleOrNull()?.let { setOf(it) }
                }
                lastDefaultNetworkState = DefaultNetworkState(active, vpn, underlying)
            }
            val callback = object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) = scheduleNetworkRetest(
                    network,
                    cm.getNetworkCapabilities(network),
                )
                override fun onLost(network: android.net.Network) {
                    val activeNetwork = cm.activeNetwork
                    scheduleNetworkRetest(
                        activeNetwork,
                        activeNetwork?.let(cm::getNetworkCapabilities),
                    )
                }
                override fun onCapabilitiesChanged(
                    network: android.net.Network,
                    caps: android.net.NetworkCapabilities,
                ) = scheduleNetworkRetest(network, caps)
            }
            cm.registerDefaultNetworkCallback(callback)
            val nonVpnRequest = android.net.NetworkRequest.Builder()
                .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            cm.registerNetworkCallback(
                nonVpnRequest,
                object : android.net.ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: android.net.Network) {
                        updateObservedNonVpnNetwork(network, available = true)
                    }

                    override fun onLost(network: android.net.Network) {
                        updateObservedNonVpnNetwork(network, available = false)
                    }

                    override fun onCapabilitiesChanged(
                        network: android.net.Network,
                        caps: android.net.NetworkCapabilities,
                    ) {
                        updateObservedNonVpnNetwork(
                            network,
                            available = caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                                caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN),
                        )
                    }
                },
            )
        } catch (e: Exception) {
            android.util.Log.w("App", "registerNetworkCallback failed: ${e.message}")
        }
    }

    /** 网络身份去重 + 2s 防抖后触发网络栈处理；VPN-only 的自动测速默认关闭。 */
    private fun scheduleNetworkRetest(
        network: android.net.Network?,
        caps: android.net.NetworkCapabilities?,
    ) {
        val isVpn = caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)
        val underlyingNetworks = when {
            network == null || isVpn == null -> null
            !isVpn -> setOf(network)
            else -> synchronized(networkRetestLock) {
                observedNonVpnNetworks.singleOrNull()?.let { setOf(it) }
            }
        }
        val current = DefaultNetworkState(network, isVpn, underlyingNetworks)
        val previous = synchronized(networkRetestLock) {
            val old = lastDefaultNetworkState
            if (current == old) return
            lastDefaultNetworkState = current
            old
        }
        val vpnOnlyChange = previous?.let { isVpnOnlyChange(it, current) } == true
        val shouldRetest = !vpnOnlyChange ||
            com.zycomic.app.net.DevConfig.isVpnOnlyAutoRetestEnabled()
        synchronized(networkRetestLock) {
            if (shouldRetest) pendingNetworkRetest = true
        }
        networkHandler.removeCallbacks(networkRetestRunnable)
        networkHandler.postDelayed(networkRetestRunnable, 2000)
    }

    /** 非 VPN Internet 网络列表发生变化时，按真实/无法分类网络变化防抖测速。 */
    private fun updateObservedNonVpnNetwork(
        network: android.net.Network,
        available: Boolean,
    ) {
        val changed = synchronized(networkRetestLock) {
            if (available) observedNonVpnNetworks.add(network) else observedNonVpnNetworks.remove(network)
        }
        if (changed) {
            synchronized(networkRetestLock) {
                pendingNetworkRetest = true
            }
            networkHandler.removeCallbacks(networkRetestRunnable)
            networkHandler.postDelayed(networkRetestRunnable, 2000)
        }
    }

    /** 只有 VPN 开关变化且唯一可观察到的非 VPN 网络未变时，才将变化视为 VPN-only。 */
    private fun isVpnOnlyChange(
        previous: DefaultNetworkState,
        current: DefaultNetworkState,
    ): Boolean {
        if (previous.isVpn == null || current.isVpn == null) return false
        if (previous.isVpn == false && current.isVpn == false) return false
        val previousUnderlying = previous.underlyingNetworks ?: return false
        val currentUnderlying = current.underlyingNetworks ?: return false
        return previousUnderlying.isNotEmpty() &&
            currentUnderlying.isNotEmpty() &&
            previousUnderlying == currentUnderlying
    }

    private fun initializeMigrator() {
        val preferenceStore = Injekt.get<PreferenceStore>()
        // SY -->
        val preference = preferenceStore.getInt(Preference.appStateKey("eh_last_version_code"), 0)
        // SY <--
        logcat { "Migration from ${preference.get()} to ${BuildConfig.VERSION_CODE}" }
        Migrator.initialize(
            old = preference.get(),
            new = BuildConfig.VERSION_CODE,
            migrations = migrations,
            onMigrationComplete = {
                logcat { "Updating last version to ${BuildConfig.VERSION_CODE}" }
                preference.set(BuildConfig.VERSION_CODE)
            },
        )
    }

    override fun newImageLoader(context: Context): ImageLoader {
        return ImageLoader.Builder(this).apply {
            // 封面统一走 ZYComic 图片栈（NetworkModule.imageClient）：相同 DNS/SNI 策略、图片头、
            // 最快图域改写与解密，并在网络/VPN 变化重建后经 CoverCallFactory.rebind() 自动跟随。
            val callFactoryLazy = lazy { com.zycomic.app.net.CoverCallFactory }
            components {
                // NetworkFetcher.Factory
                add(OkHttpNetworkFetcherFactory(callFactoryLazy::value))
                // Decoder.Factory
                add(TachiyomiImageDecoder.Factory())
                // Fetcher.Factory
                add(BufferedSourceFetcher.Factory())
                add(MangaCoverFetcher.MangaCoverFactory(callFactoryLazy))
                add(MangaCoverFetcher.MangaFactory(callFactoryLazy))
                // Keyer
                add(MangaCoverKeyer())
                add(MangaKeyer())
                // SY -->
                add(PagePreviewKeyer())
                add(PagePreviewFetcher.Factory(callFactoryLazy))
                // SY <--
            }

            diskCache(
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image_cache"))
                    .maxSizePercent(0.02)
                    .build(),
            )

            memoryCache(
                MemoryCache.Builder()
                    .maxSizePercent(context)
                    .build(),
            )

            crossfade((300 * this@App.animatorDurationScale).toInt())
            allowRgb565(DeviceUtil.isLowRamDevice(this@App))
            // KMK -->
            if (EHLogLevel.isExtraLogging()) logger(DebugLogger())
            // KMK <--

            // Coil spawns a new thread for every image load by default
            fetcherCoroutineContext(Dispatchers.IO.limitedParallelism(8))
            decoderCoroutineContext(Dispatchers.IO.limitedParallelism(3))
        }
            .build()
    }

    override fun onStart(owner: LifecycleOwner) {
        SecureActivityDelegate.onApplicationStart()

        val syncPreferences: SyncPreferences = Injekt.get()
        val syncTriggerOpt = syncPreferences.getSyncTriggerOptions()
        if (syncPreferences.isSyncEnabled() && syncTriggerOpt.syncOnAppResume) {
            SyncDataJob.startNow(this@App)
        }

        // AM (DISCORD) -->
        DiscordRPCService.start(applicationContext)
        // <-- AM (DISCORD)
    }

    override fun onStop(owner: LifecycleOwner) {
        SecureActivityDelegate.onApplicationStopped()

        // AM (DISCORD) -->
        DiscordRPCService.stop(applicationContext)
        // <-- AM (DISCORD)
    }

    override fun getPackageName(): String {
        try {
            // Override the value passed as X-Requested-With in WebView requests
            val stackTrace = Looper.getMainLooper().thread.stackTrace
            // KMK -->
            val chromiumClasses = setOf("org.chromium.base.buildinfo", "org.chromium.base.apkinfo")
            val chromiumMethods = setOf("getall", "getpackagename", "<init>")
            // KMK <--
            val isChromiumCall = stackTrace.any { trace ->
                trace.className.lowercase() in chromiumClasses &&
                    trace.methodName.lowercase() in chromiumMethods
            }

            if (isChromiumCall) return WebViewUtil.spoofedPackageName(applicationContext)
        } catch (_: Exception) {
        }

        return super.getPackageName()
    }

    private fun setupNotificationChannels() {
        try {
            Notifications.createChannels(this)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to modify notification channels" }
        }
    }

    // EXH
    private fun setupExhLogging() {
        EHLogLevel.init(
            this,
            // KMK -->
            isDebugBuildType = isDebugBuildType,
            // KMK <--
        )

        val logLevel = when {
            // KMK -->
            EHLogLevel.isExtremeLogging() -> LogLevel.ALL
            EHLogLevel.isExtraLogging() -> LogLevel.DEBUG
            // KMK <--
            else -> LogLevel.WARN
        }

        val logConfig = LogConfiguration.Builder()
            .logLevel(logLevel)
            .disableStackTrace()
            .disableBorder()
            .build()

        val printers = mutableListOf<Printer>(AndroidPrinter())

        val logFolder = Injekt.get<StorageManager>().getLogsDirectory()

        if (logFolder != null) {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

            printers += EnhancedFilePrinter
                .Builder(logFolder) {
                    fileNameGenerator = object : DateFileNameGenerator() {
                        override fun generateFileName(logLevel: Int, timestamp: Long): String {
                            return super.generateFileName(
                                logLevel,
                                timestamp,
                            ) + "-${BuildConfig.BUILD_TYPE}.txt"
                        }
                    }
                    flattener { timeMillis, level, tag, message ->
                        "${dateFormat.format(timeMillis)} ${LogLevel.getShortLevelName(level)}/$tag: $message"
                    }
                    backupStrategy = NeverBackupStrategy()
                }
        }

        XLog.init(
            logConfig,
            *printers.toTypedArray(),
        )

        xLogD("Application booting...")
        xLogD(CrashLogUtil(applicationContext).getDebugInfo())
    }

    private inner class DisableIncognitoReceiver : BroadcastReceiver() {
        private var registered = false

        override fun onReceive(context: Context, intent: Intent) {
            basePreferences.incognitoMode().set(false)
        }

        fun register() {
            if (!registered) {
                ContextCompat.registerReceiver(
                    this@App,
                    this,
                    IntentFilter(ACTION_DISABLE_INCOGNITO_MODE),
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
                registered = true
            }
        }

        fun unregister() {
            if (registered) {
                unregisterReceiver(this)
                registered = false
            }
        }
    }
}

private const val ACTION_DISABLE_INCOGNITO_MODE = "tachi.action.DISABLE_INCOGNITO_MODE"
