package com.shizq.bika

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Process
import android.os.StrictMode
import android.os.StrictMode.ThreadPolicy.Builder
import android.util.Log
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import com.shizq.bika.core.common.BikaLog
import com.shizq.bika.core.coroutine.ApplicationScope
import com.shizq.bika.core.download.Download
import com.shizq.bika.core.logging.LoggingConfigurator
import com.shizq.bika.core.network.dns.DnsAutoSelector
import com.shizq.bika.sync.initializers.Sync
import com.shizq.bika.util.ProfileVerifierLogger
import dagger.hilt.android.HiltAndroidApp
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

@HiltAndroidApp
class BikaApplication : Application(), SingletonImageLoader.Factory {
    @Inject
    lateinit var imageLoader: ImageLoader

    @Inject
    lateinit var profileVerifierLogger: ProfileVerifierLogger

    @Inject
    lateinit var dnsAutoSelector: DnsAutoSelector

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    private val logger = KotlinLogging.logger("BikaApplication")

    override fun onCreate() {
        super.onCreate()
        initializeLogging()
        // 异常处理器在 onCreate 里就装上了，晚于 MainActivity 才初始化 BikaLog 的话，
        // 启动阶段的崩溃会无处可写。先在这里兜一次底（init 幂等，MainActivity 再调无副作用）
        BikaLog.init(this, enabled = false)
        // 提到后台初始化之前：下面起的后台协程也归它保护
        setupGlobalExceptionHandler()
//        setStrictModePolicy()

        // 两个 WorkManager 初始化都挪到后台。
        //
        // 它们做的是「往 WorkManager 的数据库里写一条调度记录」——`WorkManager.getInstance`
        // 本身因为有 androidx.startup 自动初始化而不贵，真正贵的是随之而来的
        // `enqueueUniquePeriodicWork` / `enqueueUniqueWork`：**主线程上的磁盘写入**。
        // 放在 Application.onCreate 里，这段 IO 会直接顶在首个画面前面。
        //
        // 延后到后台没有副作用：这两件事都不产出首屏需要的东西，唯一的差别是
        // 进程在落库前被杀时本次调度不生效——下次启动会重新入队，
        // 而两者用的都是 `KEEP` 策略，重复入队是幂等的。
        applicationScope.launch(Dispatchers.IO) {
            runCatching { Sync.initialize(this@BikaApplication) }
                .onFailure { Log.e("BikaApplication", "Sync initialize failed", it) }
            runCatching { Download.initialize(this@BikaApplication) }
                .onFailure { Log.e("BikaApplication", "Download initialize failed", it) }
        }

        profileVerifierLogger()
        // 冷启动顺手挑一条延迟最低的分流线路。放到最后调用，且内部是
        // 「起个后台协程就返回」，不会占用启动路径的时间。
        dnsAutoSelector.optimizeOnColdStart()
        logger.info { "Application initialized successfully" }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = imageLoader

    /**
     * Return true if the application is debuggable.
     */
    private fun isDebuggable(): Boolean {
        return (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    /**
     * Set a thread policy that detects all potential problems on the main thread, such as network
     * and disk access.
     *
     * If a problem is found, the offending call will be logged and the application will be killed.
     */
    private fun setStrictModePolicy() {
        if (!isDebuggable()) return
        StrictMode.setThreadPolicy(
            Builder()
                .detectAll()
                .penaltyLog()
                .build(),
        )
    }

    private fun initializeLogging() {
        try {
            val logsDir = getLogsDir()
            LoggingConfigurator.configureLogging(logsDir)
            logger.info { "Logging initialized at: ${logsDir.absolutePath}" }
        } catch (e: Exception) {
            Log.e("BikaApplication", "Failed to initialize logging", e)
        }
    }

    private fun getLogsDir(): File {
        val logsDir = applicationContext.filesDir.resolve("logs")
        if (!logsDir.exists() && !logsDir.mkdirs()) {
            Log.w("BikaApplication", "Failed to create logs directory: ${logsDir.absolutePath}")
        }
        return logsDir
    }

    private fun setupGlobalExceptionHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                // 两条路都记：log4j 那份给完整的上下文，BikaLog 这份是同步落盘，
                // 不依赖异步 appender 是否在进程被杀前写完。设置页的
                // 「查看 / 导出系统日志」读的就是后者所在目录
                BikaLog.logFatalException(thread.name, throwable)
                logger.error(throwable) {
                    "FATAL EXCEPTION on thread: ${thread.name}"
                }
            } catch (e: Exception) {
                Log.e("BikaApplication", "Failed to log uncaught exception", e)
            } finally {
                defaultHandler?.uncaughtException(thread, throwable) ?: run {
                    Log.e(
                        "BikaApplication",
                        "No default exception handler, killing process",
                        throwable
                    )
                    Process.killProcess(Process.myPid())
                }
            }
        }
    }
}
