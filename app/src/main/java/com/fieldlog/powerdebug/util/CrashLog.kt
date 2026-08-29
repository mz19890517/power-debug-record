package com.fieldlog.powerdebug.util

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃日志（黑匣子）：App安装全局未捕获异常处理器，把堆栈写入应用私有目录 crash_log.txt。
 * 现场遇到"闪退/白屏重启"时，工具页可查看并复制发回排查。只保留最近若干条，防膨胀。
 */
object CrashLog {
    private const val FILE = "crash_log.txt"
    private const val MAX_BYTES = 120_000L

    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, FILE)

    fun append(ctx: Context, header: String, tr: Throwable?) {
        try {
            val f = file(ctx)
            val ts = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date())
            f.appendText("[$ts] $header\n${tr?.stackTraceToString() ?: "(无堆栈)"}\n\n")
            if (f.length() > MAX_BYTES) {
                val txt = f.readText()
                f.writeText(txt.substring(txt.length / 2))
            }
        } catch (_: Exception) {
        }
    }

    fun read(ctx: Context): String =
        try { file(ctx).readText() } catch (_: Exception) { "" }

    fun clear(ctx: Context) {
        try { file(ctx).delete() } catch (_: Exception) {}
    }

    /** 安装全局崩溃处理器；不影响系统原有的崩溃流程 */
    fun install(app: android.app.Application) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            append(app, "线程=${t.name} 版本=${app.packageManager.getPackageInfo(app.packageName, 0).versionName}", e)
            prev?.uncaughtException(t, e)
        }
    }
}
