package com.fieldlog.powerdebug.util

import android.content.Context
import org.json.JSONObject

/**
 * 日志类型修复（v2.25）的本地审计留存：记录修复工具改写过 (日志id→原logType)，
 * 供工具页「撤销类型修复」一键还原。仅存本机，非业务数据、不随备份/同步传播。
 */
object FixLogStore {
    private const val FILE = "fix_log_prefs"
    private const val K_APPLIED = "applied"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 追加记录本次改写的 (id→原类型)，已有同名 id 覆盖 */
    fun record(ctx: Context, applied: Map<String, Int>) {
        if (applied.isEmpty()) return
        val p = prefs(ctx)
        val json = JSONObject(p.getString(K_APPLIED, "{}").orEmpty())
        applied.forEach { (id, orig) -> json.put(id, orig) }
        p.edit().putString(K_APPLIED, json.toString()).apply()
    }

    /** 全部已记录的 (日志id→原类型) */
    fun all(ctx: Context): Map<String, Int> {
        val p = prefs(ctx)
        val json = JSONObject(p.getString(K_APPLIED, "{}").orEmpty())
        return buildMap {
            val it = json.keys()
            while (it.hasNext()) {
                val k = it.next()
                put(k, json.optInt(k))
            }
        }
    }

    /** 撤销完成后清空留存 */
    fun clear(ctx: Context) {
        prefs(ctx).edit().remove(K_APPLIED).apply()
    }
}