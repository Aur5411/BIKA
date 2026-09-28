package com.shizq.bika.core.common

import android.content.Context
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃日志与日志文件管理（clearLogs / getLogFile 等）。
 *
 * 常规业务日志走 KotlinLogging（log4j），由
 * [com.shizq.bika.core.logging.LoggingConfigurator] 的 RollingFile appender 落到
 * `files/logs/app.log`（并按天滚动出 `app-yyyy-MM-dd.log`）。这里不重复实现一套
 * 日志系统，只负责两件 log4j 不方便做的事：
 *
 * 1. **把日志文件路径暴露给设置页**（「查看系统日志」/「导出系统日志」要读它）；
 * 2. **崩溃时同步落盘**：进程随时可能被系统杀掉，异步 appender 不保证写完，
 *    所以未捕获异常额外同步 append 到 `crash.log`。
 *
 * 日志文件的收集与「调试日志开关」无关。用户最需要日志的正是闪退场景，
 * 让他先想起来去打开开关是不可能的。
 */
object BikaLog {

    private const val LOGS_DIR_NAME = "logs"

    /** log4j 的当前日志文件，与 LoggingConfigurator 里的常量保持一致。 */
    private const val MAIN_LOG_FILE_NAME = "app.log"

    /** 按天滚动后的历史日志文件名前缀。 */
    private const val ROLLED_LOG_FILE_PREFIX = "app-"

    /** 崩溃专用：未捕获异常的同步落盘文件。 */
    private const val CRASH_LOG_FILE_NAME = "crash.log"

    /** 崩溃日志的体积上限；只保留最近一次崩溃足够定位问题，无上限增长白占存储。 */
    private const val MAX_CRASH_LOG_BYTES = 512L * 1024

    /** 合并导出时截取主日志尾部的字节数。 */
    private const val MAX_EXPORT_MAIN_BYTES = 256L * 1024

    /** 合并导出文件名，不参与滚动文件扫描（不以 app- 开头）。 */
    private const val EXPORT_LOG_FILE_NAME = "export.log"

    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var logsDir: File? = null

    fun init(context: Context, enabled: Boolean) {
        // enabled 只是业务日志开关，不影响文件收集
        logsDir = context.applicationContext.filesDir.resolve(LOGS_DIR_NAME)
    }

    fun d(tag: String, message: String) {
        Log.d(tag, message)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        Log.w(tag, message, throwable)
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
    }

    private fun writeLog(level: String, tag: String, message: String) {
        appendSync(level, tag, message)
    }

    /**
     * 同步写入日志文件（崩溃路径专用，保证进程死亡前落盘）。
     * 崩溃日志无条件写入：用户最需要日志的正是闪退场景，不依赖日志开关。
     */
    private fun writeLogSync(level: String, tag: String, message: String) {
        appendSync(level, tag, message)
    }

    /**
     * 记录未捕获异常（含完整堆栈）。由全局异常处理器在进程被杀之前调用，
     * 走同步写入，不依赖 log4j 的异步 appender。
     */
    fun logFatalException(threadName: String, throwable: Throwable) {
        writeLogSync(
            level = "FATAL",
            tag = "UncaughtException",
            message = "thread=$threadName\n${Log.getStackTraceString(throwable)}",
        )
    }

    private fun appendSync(level: String, tag: String, message: String) {
        val dir = logsDir ?: return
        try {
            if (!dir.exists() && !dir.mkdirs()) return
            val file = dir.resolve(CRASH_LOG_FILE_NAME)
            if (file.length() > MAX_CRASH_LOG_BYTES) {
                file.writeText("")
            }
            file.appendText("${timestampFormat.format(Date())} $level/$tag: $message\n")
        } catch (e: Exception) {
            Log.e("BikaLog", "写入崩溃日志失败", e)
        }
    }

    /**
     * 供设置页「查看 / 导出系统日志」使用的文件。
     *
     * 有崩溃记录时**合并导出**：只给其中一份都不够用——崩溃栈说明了死在哪，
     * 而崩溃前的业务日志说明了当时在做什么。没有崩溃记录时退回 log4j 的主日志，
     * 再退回最近一天的滚动文件。null 表示确实没有任何日志。
     *
     * **顺序是"运行日志在前、崩溃记录在后"**：设置页的查看器会截断成最后 2000 行
     * （见 SettingsViewModel.getLogsContent），把崩溃段放在前面正好会被截掉——
     * 而它恰恰是用户导出日志的唯一目的。
     */
    fun getLogFile(): File? {
        val dir = logsDir ?: return null
        val crash = dir.resolve(CRASH_LOG_FILE_NAME).takeIf { it.isFile && it.length() > 0 }
        val main = dir.resolve(MAIN_LOG_FILE_NAME).takeIf { it.isFile && it.length() > 0 }

        if (crash == null && main == null) {
            return dir.listFiles { file -> file.isFile && file.name.startsWith(ROLLED_LOG_FILE_PREFIX) }
                ?.sortedByDescending { it.name }
                ?.firstOrNull { it.length() > 0 }
        }
        if (crash == null) return main
        if (main == null) return crash

        return try {
            val export = dir.resolve(EXPORT_LOG_FILE_NAME)
            export.bufferedWriter().use { writer ->
                writer.append("===== 运行日志尾部 (app.log) =====\n")
                writer.append(readTail(main, MAX_EXPORT_MAIN_BYTES))
                writer.append("\n===== 崩溃记录 (crash.log) =====\n")
                writer.append(crash.readText())
            }
            export
        } catch (e: Exception) {
            Log.e("BikaLog", "合并导出失败，退回崩溃日志", e)
            crash
        }
    }

    /** 读文件末尾 [maxBytes] 字节，且丢掉被截断的首行，避免导出半行乱码。 */
    private fun readTail(file: File, maxBytes: Long): String {
        val length = file.length()
        if (length <= maxBytes) return file.readText()
        return RandomAccessFile(file, "r").use { raf ->
            raf.seek(length - maxBytes)
            val buffer = ByteArray(maxBytes.toInt())
            raf.readFully(buffer)
            val text = String(buffer, Charsets.UTF_8)
            text.substringAfter('\n', text)
        }
    }

    fun clearLogs() {
        val dir = logsDir ?: return
        dir.listFiles()?.forEach { file ->
            try {
                if (file.isFile) file.writeText("")
            } catch (e: Exception) {
                Log.e("BikaLog", "清空日志失败: ${file.name}", e)
            }
        }
    }
}
