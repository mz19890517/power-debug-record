package com.powerdebug.record.util

import android.content.Context
import com.powerdebug.record.App
import com.powerdebug.record.core.WebDavClient
import com.powerdebug.record.data.ConflictFavor
import com.powerdebug.record.data.MergeResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * WebDAV 增量同步编排（v2.26，规格第7章）。
 *
 * 新版云端布局（7.5，"新文件夹"，不复用旧版 backup_*.json 所在目录）：
 * ```
 * <云端根目录>
 * ├── global/
 * │   └── backup_<账号>_<本机标识>.json.gz     # 柜型+候选池+调试员+全部删除墓碑（含项目级）
 * └── projects/
 *     └── project_<UUID>/
 *         └── backup_<账号>_<本机标识>.json.gz # 该项目 柜子/日志/故障/预选项 + 项目行
 * ```
 * 同步为三阶段（7.6）：
 * ① 全局：先推本机全局快照（墓碑优先扩散），再拉取合并所有设备的全局快照；
 * ② 枚举 projects/ 子文件夹，与本机项目求差集；
 * ③ 按项目增量：本机"项目版本时钟"（=该项目所有行最大 updatedAt，不信任文件mtime）
 *    晚于 projectLastSync 才重新上传；云端所有项目文件统一拉取合并（同项目内"新者胜"）。
 *
 * 旧版(v2.x)数据迁移（7.8）：首次同步把旧目录的全量快照合入本机库一次（此后按项目时钟
 * 自然分发到新结构），旧文件名记入已迁移名单避免重复搬运。测试员账号是本地身份注册表，
 * 与现版一致不随同步传播。
 *
 * 冲突裁决（7.7）：合并前 detect 到"时钟窗口内歧义"（双方时间戳相差<=5分钟）时调用
 * favorResolver 让用户选「保留本地/覆盖云端」；自动同步默认 CLOUD（墙钟新者胜）不再弹窗。
 * 全过程写 SyncLog；失败不崩溃、可重试。
 */
object WebDavSync {

    const val DIR_GLOBAL = "global"
    const val DIR_PROJECTS = "projects"

    fun client(ctx: Context): WebDavClient {
        val cfg = SyncStore.config(ctx)
            ?: throw IllegalStateException("请先在「数据工具」配置并登录WebDAV")
        return WebDavClient(cfg.url, cfg.user, cfg.pass)
    }

    private fun fileNameOf(ctx: Context, user: String) =
        "backup_${user}_${SyncStore.deviceTag(ctx)}.json"

    /** gzip压缩（JSON中文文本通常压到1/8~1/15） */
    private fun gzip(bytes: ByteArray): ByteArray =
        ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write(bytes) }
        }.toByteArray()

    /** 按魔数识别gzip（1f 8b）并解压；旧版明文快照原样返回。备份/找回工具共用此识别入口 */
    fun decodeSnapshot(bytes: ByteArray): String {
        val isGzip = bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()
        return if (isGzip)
            GZIPInputStream(bytes.inputStream()).use { it.readBytes() }.toString(Charsets.UTF_8)
        else
            bytes.toString(Charsets.UTF_8)
    }

    private fun kb(n: ByteArray) =
        java.lang.String.format(java.util.Locale.CHINA, "%.1fKB", n.size / 1024.0)

    /**
     * 新版三段式增量同步。@param favorResolver 冲突裁决回调（挂起、需在主线程弹窗）。
     * @return 摘要文本（供toast展示），失败抛异常。
     */
    suspend fun syncAll(ctx: Context, favorResolver: suspend () -> ConflictFavor = { ConflictFavor.CLOUD }): String =
        withContext(Dispatchers.IO) {
            val me = SyncStore.currentUser(ctx)
                ?: throw IllegalStateException("未登录测试账号")
            val cl = client(ctx)
            val myName = fileNameOf(ctx, me)
            SyncLog.append(ctx, "═══ 开始增量同步 ═══ 账号=$me 文件标识=$myName 目录=${SyncStore.config(ctx)?.url}")

            val parts = mutableListOf<String>()
            val errors = mutableListOf<String>()
            var upGlobal = 0; var upProject = 0

            // 0) 旧版一次性迁移（失败不阻断新结构同步）
            try {
                migrateLegacy(ctx)
            } catch (e: Exception) {
                SyncLog.append(ctx, "⚠ 旧版迁移跳过：${e.javaClass.simpleName}: ${e.message}")
            }

            // 1) 目录布局（幂等）
            runCatching { cl.ensureDir(DIR_GLOBAL); cl.ensureDir(DIR_PROJECTS) }
                .onFailure { SyncLog.append(ctx, "⚠ 建目录失败（已存在可忽略）：${it.message}") }

            // ---------- ① 全局区：先推本机（墓碑即刻扩散），再拉所有设备的全局快照 ----------
            suspend fun pullGlobal(f: String): MergeResult {
                val text = decodeSnapshot(cl.download("$DIR_GLOBAL/$f"))
                val favor = if (App.repo.hasMergeConflict(text)) favorResolver() else ConflictFavor.CLOUD
                return App.repo.mergeJson(text, favor)
            }
            try {
                val gz = gzip(App.repo.globalSnapshot().toByteArray(Charsets.UTF_8))
                cl.upload("$DIR_GLOBAL/$myName", gz)
                upGlobal = 1
                SyncLog.append(ctx, "① 上传全局 $DIR_GLOBAL/$myName 压缩${kb(gz)}")
            } catch (e: Exception) {
                SyncLog.append(ctx, "① ⚠ 全局上传失败：${e.message}")
                errors.add("全局上传(${e.message})")
            }
            val globalFiles = runCatching {
                cl.listChildren(DIR_GLOBAL).filter { it.startsWith("backup_") && it.endsWith(".json") }
            }.getOrElse { e ->
                SyncLog.append(ctx, "① ⚠ 列全局区失败 ${e.message}")
                emptyList()
            }
            for (f in globalFiles) {
                if (f == myName) continue
                try {
                    val r = pullGlobal(f)
                    SyncLog.append(ctx, "① 合并全局 $f → 类型+${r.newTypes}/改${r.updTypes} 候选+${r.newCands} 调试员+${r.newDebuggers}/改${r.updDebuggers} 删除${r.appliedTombs}")
                    if (r.newTypes + r.updTypes + r.newCands + r.newDebuggers + r.updDebuggers + r.appliedTombs > 0) {
                        parts.add("全局：类型+${r.newTypes}/改${r.updTypes} 候选+${r.newCands} 调试员+${r.newDebuggers}/改${r.updDebuggers} 删除${r.appliedTombs}")
                    }
                } catch (e: Exception) {
                    SyncLog.append(ctx, "① ⚠ 合并全局失败 $f：${e.javaClass.simpleName}: ${e.message}")
                    errors.add("全局${f.substringAfter('_').substringBeforeLast('.')}(${e.message})")
                }
            }

            // ---------- ②/③ 项目级：云端子文件夹枚举 + 按项目增量上下传 ----------
            val clocks = App.repo.projectClocks()
            val lastSync = SyncStore.projectLastSync(ctx)
            val remotePids = runCatching {
                cl.listChildren(DIR_PROJECTS).mapNotNull { n ->
                    if (n.endsWith("/")) n.removeSuffix("/").removePrefix("project_").takeIf { it.isNotBlank() } else null
                }.toSet()
            }.getOrElse { e ->
                SyncLog.append(ctx, "② ⚠ 枚举项目目录失败 ${e.message}")
                emptySet()
            }
            val allPids = (clocks.keys + remotePids).toSet()
            SyncLog.append(ctx, "② 云端项目${remotePids.size}个；本机项目${clocks.size}个；需处理${allPids.size}个")

            for (pid in allPids.sorted()) {
                val dir = "$DIR_PROJECTS/project_$pid"
                val myProjName = fileNameOf(ctx, me)
                val localClock = clocks[pid] ?: 0L
                val last = lastSync[pid] ?: 0L

                if (pid in remotePids) {
                    // 下载合并云端该项目所有设备文件（跳过自己本次可能已建的）
                    val files = runCatching {
                        cl.listChildren(dir).filter { it.startsWith("backup_") && it.endsWith(".json") }
                    }.getOrElse { emptyList() }
                    for (f in files) {
                        if (f == myProjName) continue
                        try {
                            val text = decodeSnapshot(cl.download("$dir/$f"))
                            val favor = if (App.repo.hasMergeConflict(text)) favorResolver() else ConflictFavor.CLOUD
                            val r = App.repo.mergeJson(text, favor)
                            SyncLog.append(ctx, "③ 合并项目 $pid / $f → 日志+${r.newLogs}/${r.updLogs} 故障+${r.newFaults} 柜子+${r.newInstances} 待测+${r.newPlanned} 删除${r.appliedTombs}")
                            if (r.newLogs + r.updLogs + r.newFaults + r.updFaults +
                                r.newInstances + r.updInstances + r.newPlanned + r.updPlanned > 0
                            ) {
                                parts.add("项目${pid.take(8)}：日志+${r.newLogs}/改${r.updLogs} 故障+${r.newFaults} 柜子+${r.newInstances} 待测+${r.newPlanned}")
                            }
                        } catch (e: Exception) {
                            SyncLog.append(ctx, "③ ⚠ 合并项目失败 $pid / $f：${e.javaClass.simpleName}: ${e.message}")
                            errors.add("${pid.take(8)}(${e.message})")
                        }
                    }
                }

                // 上传条件：本机有该项目 &&（本机时钟晚于上次同步 || 云端还没有该项目）
                if (pid in clocks && (localClock > last || pid !in remotePids)) {
                    try {
                        cl.ensureDir(dir)
                        val gz = gzip(App.repo.projectSnapshot(pid).toByteArray(Charsets.UTF_8))
                        cl.upload("$dir/$myProjName", gz)
                        upProject++
                        SyncStore.setProjectLastSync(ctx, pid, System.currentTimeMillis())
                        SyncLog.append(ctx, "③ 上传项目 $pid 压缩${kb(gz)}（时钟$localClock > 已同步$last）")
                    } catch (e: Exception) {
                        SyncLog.append(ctx, "③ ⚠ 上传项目失败 $pid：${e.message}")
                        errors.add("${pid.take(8)}上传(${e.message})")
                    }
                }
            }

            buildString {
                append("增量同步完成 ✓\n")
                append("全局${if (upGlobal > 0) "已上传" else "上传失败"}；项目上传${upProject}个\n")
                if (parts.isEmpty()) append("云端暂无其他设备新数据")
                else append(parts.joinToString("\n"))
                if (errors.isNotEmpty()) append("\n⚠ 部分失败：${errors.joinToString(" ")}")
            }.also { SyncLog.append(ctx, "═══ 同步结束 ═══ ${it.replace("\n", " | ")}") }
        }

    /**
     * 旧版(v2.x)全量快照一次性迁移（7.8）：
     * 读取 legacyUrl 指向的旧目录，通过本机 mergeJson 吸收每份快照（含墓碑"新者胜"），
     * 增量阶段随后按项目时钟自然把数据分发到 global/+projects/ 新结构；已迁移文件名
     * 记入 SyncStore 防重复搬运（不在服务端改名/删除，旧版设备仍可读写旧目录）。
     */
    private suspend fun migrateLegacy(ctx: Context) {
        if (SyncStore.legacyMigrated(ctx)) return
        val legacyUrl = SyncStore.legacyUrl(ctx) ?: return
        val cfg = SyncStore.config(ctx) ?: return
        val legacy = WebDavClient(legacyUrl, cfg.user, cfg.pass)
        val files = try {
            legacy.listBackups()
        } catch (e: Exception) {
            SyncLog.append(ctx, "⚠ 旧目录不可读，暂不迁移：${e.message}")
            return
        }
        if (files.isEmpty()) {
            SyncLog.append(ctx, "旧目录无旧快照，直接标记迁移完成")
            SyncStore.setLegacyMigrated(ctx, true)
            return
        }
        var doneCount = 0
        val done = SyncStore.legacyMigratedFiles(ctx).toMutableSet()
        for (f in files) {
            if (f in done) continue
            try {
                val text = decodeSnapshot(legacy.download(f))
                val r: MergeResult = App.repo.mergeJson(text)
                done.add(f)
                SyncLog.append(ctx, "旧版迁移 $f → 项目+${r.newProjects} 日志+${r.newLogs} 故障+${r.newFaults} 删除${r.appliedTombs}")
                doneCount++
            } catch (e: Exception) {
                SyncLog.append(ctx, "⚠ 迁移失败 $f：${e.javaClass.simpleName}: ${e.message}")
            }
        }
        SyncStore.markLegacyMigrated(ctx, done)
        if (doneCount > 0) SyncStore.setLegacyMigrated(ctx, true)
        SyncLog.append(ctx, "旧版迁移完成：本次吸收${doneCount}份快照，共${done.size}份")
    }
}