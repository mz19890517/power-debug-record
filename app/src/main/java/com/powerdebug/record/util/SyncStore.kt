package com.powerdebug.record.util

import android.content.Context

/**
 * 同步配置与登录会话存取。
 * 说明：按需求"低敏感信息不做高端验证"，WebDAV账密以明文存于应用私有目录，
 * 不参与系统备份以外的任何传输；超级密码用于离线直接注册测试员。
 */
object SyncStore {
    const val SUPER_PASSWORD = "mz9890517"

    private const val FILE = "sync_prefs"
    private const val K_URL = "webdav_url"
    private const val K_USER = "webdav_user"
    private const val K_PASS = "webdav_pass"
    private const val K_CURRENT = "current_user"
    private const val K_LAST_DEBUGGER = "last_debugger"
    private const val K_DEVICE_TAG = "device_tag"
    private const val K_AUTO = "auto_upload"
    private const val K_LAST_AUTO = "last_auto_sync"
    private const val K_LEGACY_URL = "legacy_url"
    private const val K_LEGACY_DONE = "legacy_migrated"
    private const val K_LEGACY_MIGRATED = "legacy_migrated_files"
    private const val K_PROJECT_LAST_SYNC = "project_last_sync"

    data class Config(val url: String, val user: String, val pass: String)

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 已配置的WebDAV连接；未配置返回null */
    fun config(ctx: Context): Config? {
        val p = prefs(ctx)
        val url = p.getString(K_URL, "").orEmpty().trim()
        val user = p.getString(K_USER, "").orEmpty().trim()
        if (url.isEmpty() || user.isEmpty()) return null
        return Config(url, user, p.getString(K_PASS, "").orEmpty())
    }

    fun saveConfig(ctx: Context, url: String, user: String, pass: String) {
        prefs(ctx).edit()
            .putString(K_URL, url.trim())
            .putString(K_USER, user.trim())
            .putString(K_PASS, pass)
            .apply()
    }

    fun clearConfig(ctx: Context) {
        prefs(ctx).edit().remove(K_URL).remove(K_USER).remove(K_PASS).apply()
    }

    /** 当前登录测试员账号（即WebDAV用户名）；未登录为null */
    fun currentUser(ctx: Context): String? =
        prefs(ctx).getString(K_CURRENT, "").orEmpty().ifBlank { null }

    fun setCurrentUser(ctx: Context, user: String?) {
        prefs(ctx).edit().putString(K_CURRENT, user.orEmpty()).apply()
    }

    /**
     * 当前调试员（写日志默认归属，必须来自超管维护的名单，与登录账号无关）。
     * 多人绑定时可随时切换；存盘后自动跟随最后一次使用的人。
     */
    fun currentDebugger(ctx: Context): String =
        prefs(ctx).getString(K_LAST_DEBUGGER, "").orEmpty()

    fun setCurrentDebugger(ctx: Context, name: String) {
        prefs(ctx).edit().putString(K_LAST_DEBUGGER, name.trim()).apply()
    }

    /**
     * 本机随机标识（首次使用时生成，之后固定）。
     * 快照文件名带上它，同一账号在多台手机上各用各的文件，互不覆盖、可互相合并。
     */
    fun deviceTag(ctx: Context): String {
        val p = prefs(ctx)
        val saved = p.getString(K_DEVICE_TAG, "").orEmpty()
        if (saved.isNotEmpty()) return saved
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        val fresh = buildString { repeat(6) { append(chars.random()) } }
        p.edit().putString(K_DEVICE_TAG, fresh).apply()
        return fresh
    }

    /** 保存日志后是否自动上传快照（默认开：连上即基本免操作） */
    fun autoUpload(ctx: Context): Boolean = prefs(ctx).getBoolean(K_AUTO, true)

    fun setAutoUpload(ctx: Context, value: Boolean) {
        prefs(ctx).edit().putBoolean(K_AUTO, value).apply()
    }

    /** 自动同步节流：距上次不足 minMs 毫秒则跳过，避免频繁旋转/切换触发风暴 */
    fun shouldAutoSyncNow(ctx: Context, minMs: Long = 60_000L): Boolean {
        val p = prefs(ctx)
        val now = System.currentTimeMillis()
        if (now - p.getLong(K_LAST_AUTO, 0) < minMs) return false
        p.edit().putLong(K_LAST_AUTO, now).apply()
        return true
    }

    // ---------- 新版增量同步（v2.26，7.5/7.6/7.8） ----------

    /** 旧版（v2.x）同步目录地址（仅迁移用，只读一次后可由用户清除）；未配置返回null */
    fun legacyUrl(ctx: Context): String? =
        prefs(ctx).getString(K_LEGACY_URL, "").orEmpty().ifBlank { null }

    fun setLegacyUrl(ctx: Context, url: String?) {
        prefs(ctx).edit().putString(K_LEGACY_URL, url?.trim().orEmpty()).apply()
    }

    /** 是否已完成旧版数据迁移（一次性；迁移后可手动复位以重试） */
    fun legacyMigrated(ctx: Context): Boolean = prefs(ctx).getBoolean(K_LEGACY_DONE, false)

    fun setLegacyMigrated(ctx: Context, done: Boolean) {
        prefs(ctx).edit().putBoolean(K_LEGACY_DONE, done).apply()
    }

    /** 已迁移过的旧文件名单（避免重复搬运；用文件名而非MOVE，兼容无MOVE权限的NAS） */
    fun legacyMigratedFiles(ctx: Context): Set<String> =
        prefs(ctx).getStringSet(K_LEGACY_MIGRATED, emptySet()) ?: emptySet()

    fun markLegacyMigrated(ctx: Context, names: Set<String>) {
        prefs(ctx).edit().putStringSet(K_LEGACY_MIGRATED, names).apply()
    }

    /** 每项目上次成功同步时刻（7.6 阶段C：本机修改检测用项目版本时钟，不依赖文件mtime） */
    fun projectLastSync(ctx: Context): Map<String, Long> {
        val set = prefs(ctx).getStringSet(K_PROJECT_LAST_SYNC, emptySet()) ?: return emptyMap()
        val out = HashMap<String, Long>()
        set.forEach { s ->
            val i = s.indexOf('|')
            if (i > 0) s.substring(i + 1).toLongOrNull()?.let { out[s.substring(0, i)] = it }
        }
        return out
    }

    fun projectLastSyncOf(ctx: Context, projectId: String): Long =
        projectLastSync(ctx)[projectId] ?: 0L

    fun setProjectLastSync(ctx: Context, projectId: String, ts: Long) {
        val map = projectLastSync(ctx).toMutableMap()
        map[projectId] = ts
        prefs(ctx).edit()
            .putStringSet(K_PROJECT_LAST_SYNC, map.map { "${it.key}|${it.value}" }.toSet())
            .apply()
    }
}
