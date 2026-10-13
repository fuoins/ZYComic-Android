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

        // 初始化开发者配置
        DevConfig.init(this)
        DevConfig.migrateProxyDefaultIfNeeded()
        com.zycomic.app.net.ImageCacheManager.init(this)
        // 初始化网络模块上下文（VPN 检测需要），并注册网络/VPN 变化监听
        com.zycomic.app.net.NetworkModule.init(this)
        registerNetworkCallback()
        runCatching {
            if (getSharedPreferences("zycomic_data", android.content.Context.MODE_PRIVATE)
                    .getBoolean("auto_clear_chapter_cache", true)
            ) {
                DevConfig.clearImageCaches()
            }
        }
        // 加载持久化的图源索引
        com.zycomic.app.net.RouteManager.loadImgIndex()
        // 恢复自动选线偏好（开启时用上次最快线路作为当前线路）
        com.zycomic.app.net.RouteManager.loadAutoSelectPrefs()
        // 恢复"封面/章节用测速最快图源"偏好
        com.zycomic.app.net.RouteManager.loadUseFastestImgForAll()
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
            com.zycomic.app.data.repository.UserRepository.verifyLogin()
        }
        // 启动后异步拉取权威远程配置。只有应用成功替换后，才针对新候选列表测速。
        ProcessLifecycleOwner.get().lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching { com.zycomic.app.net.RemoteConfigFetcher.checkAndApply(manual = false) }.getOrNull()
            if (result?.outcome == com.zycomic.app.net.RemoteConfigFetcher.Outcome.APPLIED) {
                runCatching { com.zycomic.app.net.SpeedTester.runForCurrentConfig() }
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

    /** 最近一次已处理的网络身份签名，用于去重连发回调。 */
    @Volatile
    private var lastNetworkSig: String? = null

    private val networkRetestRunnable = Runnable {
        networkScope.launch {
            try {
                // 1) 按当前生效策略清理连接池、必要时重建 client（强制直连模式下策略不翻转，仅清池）
                com.zycomic.app.net.NetworkModule.onVpnOrNetworkChanged("default network changed")
                // 2) 30s 内已在相同策略下测速成功则不重复测（避免冷启动回调与启动测速重复）
                val fresh = System.currentTimeMillis() - com.zycomic.app.net.SpeedTester.lastRunAt < 30_000L
                val sameStrategy = com.zycomic.app.net.SpeedTester.lastRunDirectStrategy ==
                    com.zycomic.app.net.NetworkModule.shouldUseDirectStrategy()
                if (fresh && sameStrategy) {
                    android.util.Log.i("App", "network changed: reuse recent speed test, skip retest")
                    return@launch
                }
                val r = com.zycomic.app.net.SpeedTester.runAndApply()
                android.util.Log.i("App", "network changed retest done: line=${r.first}, img=${r.second}")
            } catch (e: Exception) {
                android.util.Log.w("App", "network changed retest failed: ${e.message}")
            }
        }
    }

    /**
     * 注册默认网络回调：网络/VPN 状态变化后 2s 防抖，先清连接池、按生效策略重建图片/网络栈，
     * 再后台自动重测速（成功才替换线路/图域）。
     * 「自动跟随系统 VPN」关闭（强制直连）期间同样防抖清池与重测，但策略恒为直连，不随 VPN 翻转。
     */
    private fun registerNetworkCallback() {
        try {
            val cm = getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
                as? android.net.ConnectivityManager ?: return
            val callback = object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) = scheduleNetworkRetest(cm, network)
                override fun onLost(network: android.net.Network) = scheduleNetworkRetest(cm, network)
                override fun onCapabilitiesChanged(
                    network: android.net.Network,
                    caps: android.net.NetworkCapabilities,
                ) = scheduleNetworkRetest(cm, network)
            }
            cm.registerDefaultNetworkCallback(callback)
        } catch (e: Exception) {
            android.util.Log.w("App", "registerNetworkCallback failed: ${e.message}")
        }
    }

    /** 网络身份去重 + 2s 防抖后触发 [networkRetestRunnable]。 */
    private fun scheduleNetworkRetest(
        cm: android.net.ConnectivityManager,
        network: android.net.Network,
    ) {
        val caps = cm.getNetworkCapabilities(network)
        val sig = "$network@vpn=${caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)}"
        if (sig == lastNetworkSig) return
        lastNetworkSig = sig
        networkHandler.removeCallbacks(networkRetestRunnable)
        networkHandler.postDelayed(networkRetestRunnable, 2000)
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
