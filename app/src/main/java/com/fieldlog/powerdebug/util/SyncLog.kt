package com.fieldlog.powerdebug.util

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 同步过程日志：每次双向同步的关键步骤都落一份到应用私有目录 sync_log.txt，
 * 供现场排查"两台手机互相看不到数据"之类问题（工具页可查看/复制/清空）。
 * 文件超上限时砍掉前一半，防止无限膨胀。
 */
object SyncLog {
    private const val FILE = "sync_log.txt"
    private const val MAX_BYTES = 160_000L

    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, FILE)

    fun append(ctx: Context, line: String) {
        try {
            val f = file(ctx)
            val ts = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date())
            f.appendText("[$ts] $line\n")
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
}
