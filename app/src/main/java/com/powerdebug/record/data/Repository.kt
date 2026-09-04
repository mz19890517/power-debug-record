package com.powerdebug.record.data

import androidx.room.withTransaction
import com.powerdebug.record.data.db.AppDatabase
import com.powerdebug.record.data.db.CabinetInstance
import com.powerdebug.record.data.db.CabinetType
import com.powerdebug.record.data.db.CandidateItem
import com.powerdebug.record.data.db.DebugLog
import com.powerdebug.record.data.db.Debugger
import com.powerdebug.record.data.db.DeletedItem
import com.powerdebug.record.data.db.DeletedProject
import com.powerdebug.record.data.db.FaultExportRow
import com.powerdebug.record.data.db.FaultRecord
import com.powerdebug.record.data.db.InstanceRow
import com.powerdebug.record.data.db.InstanceStatusRow
import com.powerdebug.record.data.db.LogListItem
import com.powerdebug.record.data.db.PlannedItem
import com.powerdebug.record.data.db.Project
import com.powerdebug.record.data.db.ProjectListItem
import com.powerdebug.record.data.db.TesterAccount
import com.powerdebug.record.data.db.TypeListItem
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import java.util.TimeZone
import java.util.UUID

/** 合并冲突裁决偏好（7.7 时间戳冲突弹窗）：CLOUD=按 wall-clock 新者胜（旧语义，默认）；LOCAL=阈值内模糊一律保本地 */
enum class ConflictFavor { CLOUD, LOCAL }

/** 数据统计（工具页展示） */
data class Stats(
    val projects: Int,
    val types: Int,
    val instances: Int,
    val logs: Int,
    val pendingFaults: Int
)

/** 智能合并结果（各表 新增/更新 条数；appliedTombs=本机因墓碑实际删除的行数） */
data class MergeResult(
    var newProjects: Int = 0, var updProjects: Int = 0,
    var newTypes: Int = 0, var updTypes: Int = 0,
    var newCands: Int = 0,
    var newInstances: Int = 0, var updInstances: Int = 0,
    var newLogs: Int = 0, var updLogs: Int = 0,
    var newFaults: Int = 0, var updFaults: Int = 0,
    var newPlanned: Int = 0, var updPlanned: Int = 0,
    var newDebuggers: Int = 0, var updDebuggers: Int = 0,
    var appliedTombs: Int = 0
)

/** 从备份找回被删记录的结果：各表找回条数（total=合计） */
data class RollbackResult(
    var projects: Int = 0, var types: Int = 0,
    var cands: Int = 0,
    var instances: Int = 0, var logs: Int = 0,
    var faults: Int = 0, var planned: Int = 0,
    var debuggers: Int = 0
) {
    val total get() = projects + types + cands + instances + logs + faults + planned + debuggers
}

/** 找回计算出的缺失行列表（按父→子顺序），result() 输出各表计数 */
private class RollbackRows(
    val insP: List<Project>, val insT: List<CabinetType>, val insC: List<CandidateItem>,
    val insI: List<CabinetInstance>, val insL: List<DebugLog>, val insF: List<FaultRecord>,
    val insPl: List<PlannedItem>, val insD: List<Debugger>
) {
    fun result() = RollbackResult(
        projects = insP.size, types = insT.size, cands = insC.size,
        instances = insI.size, logs = insL.size, faults = insF.size,
        planned = insPl.size, debuggers = insD.size
    )
}

/** 找回面板预览：本机缺失行 + 备份/本机日志构成（用于直观判断所选备份是否为丢失前的产物） */
data class RollbackPreview(
    val missing: RollbackResult,
    val backupLogs: Int,
    val backupFaultLogs: Int,
    val backupResolutionLogs: Int,
    val backupFaultRecords: Int,
    val localLogs: Int,
    val localFaultLogs: Int,
    val localResolutionLogs: Int,
    val localFaultRecords: Int
) {
    val missingTotal get() = missing.total
}

/** 日志类型修复结果：把被故障记录指向却仍标成「通过」的日志重分类为「故障」；把备注=已解决故障现象的日志重分类为「消除」 */
data class ReclassifyResult(
    var faultLogs: Int = 0,
    var resolutionLogs: Int = 0,
    var attachedFaults: Int = 0
) {
    val total get() = faultLogs + resolutionLogs

    /** 本次实际改写（日志id → 原logType），仅应用时填充，供「撤销类型修复」留存 */
    val applied: MutableMap<String, Int> = mutableMapOf()
}

/** 删除日志时，对其完成的预选待测项的处置方式（用户弹窗二选一） */
enum class LogDeleteMode {
    /** 删除通过日志：恢复预选待测项 */
    RESTORE_PLANNED,
    /** 删除通过日志：连项删除预选待测项 */
    PURGE_PLANNED,
    /** 删除故障日志：删FaultRecord+关联消除日志+恢复PlannedItem */
    DELETE_FAULT,
    /** 删除消除日志：删消除日志+驳回FaultRecord */
    DELETE_RESOLUTION,
    /** 删除消除日志+故障日志：全删 */
    DELETE_RESOLUTION_PURGE
}

/** 备份文件解析结果：已统一为本机String主键的实体列表 */
private class ParsedBackup {
    val projects = mutableListOf<Project>()
    val types = mutableListOf<CabinetType>()
    val cands = mutableListOf<CandidateItem>()
    val instances = mutableListOf<CabinetInstance>()
    val logs = mutableListOf<DebugLog>()
    val faults = mutableListOf<FaultRecord>()
    val planned = mutableListOf<PlannedItem>()
    val debuggers = mutableListOf<Debugger>()
    val tombs = mutableListOf<DeletedItem>()
    val deletedProjects = mutableListOf<DeletedProject>()
}

/**
 * 业务逻辑统一入口。
 * 后期电脑端/网页端移植时，按同样的语义实现本层即可复用全部业务规则
 * （候选池自动沉淀、级联删除、JSON 备份格式、智能合并等）。
 */
class Repository(private val db: AppDatabase) {

    private val projectDao = db.projectDao()
    private val typeDao = db.cabinetTypeDao()
    private val candDao = db.candidateItemDao()
    private val instanceDao = db.instanceDao()
    private val logDao = db.debugLogDao()
    private val faultDao = db.faultRecordDao()
    private val plannedDao = db.plannedItemDao()
    private val debuggerDao = db.debuggerDao()
    private val tombDao = db.deletedItemDao()
    private val deletedProjectDao = db.deletedProjectDao()

    private fun newId() = UUID.randomUUID().toString()
    private fun now() = System.currentTimeMillis()

    /** 记删除墓碑（须在业务事务内调用）：该表该行已删除，随同步传播到全队 */
    private suspend fun markDeleted(tbl: String, itemId: String, t: Long = now()) {
        if (itemId.isNotBlank()) tombDao.insert(DeletedItem(id = newId(), tbl = tbl, itemId = itemId, deletedAt = t))
    }

    // ---------- 观察 ----------

    fun watchProjects(): Flow<List<Project>> = projectDao.watchAllAsFlow()
    fun watchProjectItems(): Flow<List<ProjectListItem>> = projectDao.watchListItemsAsFlow()
    fun watchTypeItems(): Flow<List<TypeListItem>> = typeDao.watchListItemsAsFlow()
    fun watchTypes(): Flow<List<CabinetType>> = typeDao.watchAllAsFlow()
    fun watchInstancesOf(projectId: String): Flow<List<CabinetInstance>> =
        instanceDao.watchByProjectAsFlow(projectId)
    fun watchInstancesWithStats(projectId: String): Flow<List<InstanceStatusRow>> =
        instanceDao.watchByProjectWithStatsAsFlow(projectId)
    fun watchPlannedOf(instanceId: String): Flow<List<PlannedItem>> =
        plannedDao.watchByInstanceAsFlow(instanceId)
    fun watchPool(typeId: String): Flow<List<CandidateItem>> = candDao.watchByTypeAsFlow(typeId)

    // ---------- 项目 ----------

    suspend fun getProject(id: String): Project? = projectDao.getByIdOnce(id)

    /** 新增或更新；返回最终id */
    suspend fun saveProject(p: Project): String {
        val row =
            if (p.id.isBlank()) p.copy(id = newId(), createdAt = now(), updatedAt = now())
            else p.copy(updatedAt = now())
        if (projectDao.getByIdOnce(row.id) == null) projectDao.insert(row) else projectDao.update(row)
        return row.id
    }

    /** 返回受影响柜子数（用于确认弹窗），-1 表示项目不存在。项目级删除墓碑（deleted_projects）随全局区同步传播 */
    suspend fun deleteProject(id: String): Int {
        val p = projectDao.getByIdOnce(id) ?: return -1
        val cabinets = instanceDao.byProjectOnce(id).size
        db.withTransaction {
            deletedProjectDao.insert(DeletedProject(id = newId(), projectId = p.id, deletedAt = now()))
            projectDao.delete(p)
        }
        return cabinets
    }

    /**
     * 刷新项目「调试完成日期」（debugEndDate）：当且仅当该项目所有启用待测项均已通过
     * （无未测/未通过）且无未消除故障（status=0）时，写入当前时间=调试完成；否则清零
     * （撤回重测/新增故障 → 项目重新进行中）。
     * 起始日期（debugStartDate）创建后不再由系统改动，仅用户手动编辑时更新。
     * 仅在状态真正变化时落库，避免无意义地跳动 updatedAt 干扰同步时钟。
     */
    suspend fun refreshDebugEndDate(projectId: String) {
        val p = projectDao.getByIdOnce(projectId) ?: return
        val allDone = projectDao.enabledPlannedCount(projectId) > 0 &&
            projectDao.notPassedPlannedCount(projectId) == 0 &&
            projectDao.pendingFaultCount(projectId) == 0
        val target = if (allDone) now() else 0L
        if (p.debugEndDate != target) {
            projectDao.update(p.copy(debugEndDate = target, updatedAt = now()))
        }
    }

    /** 由柜子实例反查项目，并刷新该项目完成日期（状态跳变后的便捷入口） */
    private suspend fun refreshDebugEndDateForInstance(instanceId: String) {
        val inst = instanceDao.getByIdOnce(instanceId) ?: return
        refreshDebugEndDate(inst.projectId)
    }

    // ---------- 柜子类型与候选池 ----------

    suspend fun getType(id: String): CabinetType? = typeDao.getByIdOnce(id)
    suspend fun allTypes(): List<CabinetType> = typeDao.allOnce()

    suspend fun saveType(t: CabinetType): String {
        val row =
            if (t.id.isBlank()) t.copy(id = newId(), createdAt = now(), updatedAt = now())
            else t.copy(updatedAt = now())
        if (typeDao.getByIdOnce(row.id) == null) typeDao.insert(row) else typeDao.update(row)
        return row.id
    }

    /** 返回使用该类型的实例数（用于确认弹窗），-1 表示类型不存在。删除记墓碑随同步传播 */
    suspend fun deleteType(id: String): Int {
        val t = typeDao.getByIdOnce(id) ?: return -1
        val usage = instanceDao.byTypeWithProject(id).size
        db.withTransaction {
            markDeleted(DeletedItem.TBL_TYPES, t.id)
            typeDao.delete(t)
        }
        return usage
    }

    /**
     * 向候选池追加条目：自动按整行去重。
     * @return 新增条数
     */
    suspend fun addCandidatesFromText(typeId: String, text: String): Int {
        val existing = candDao.contentsOnce(typeId).map { it.trim() }.toHashSet()
        var added = 0
        text.split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() && existing.add(it) }
            .forEach {
                candDao.insert(CandidateItem(id = newId(), typeId = typeId, content = it))
                added++
            }
        return added
    }

    /** 删除候选池条目，记墓碑随同步传播 */
    suspend fun deleteCandidate(item: CandidateItem) {
        db.withTransaction {
            markDeleted(DeletedItem.TBL_CANDS, item.id)
            candDao.delete(item)
        }
    }

    suspend fun instanceUsageOfType(typeId: String) = instanceDao.byTypeWithProject(typeId)

    // ---------- 柜子实例 ----------

    suspend fun getInstance(id: String): CabinetInstance? = instanceDao.getByIdOnce(id)
    suspend fun instancesOfProjectOnce(projectId: String) = instanceDao.byProjectOnce(projectId)

    suspend fun saveInstance(i: CabinetInstance): String {
        val t = now()
        val isNew = i.id.isBlank()
        val row =
            if (isNew) i.copy(id = newId(), createdAt = t, updatedAt = t)
            else i.copy(updatedAt = t)
        db.withTransaction {
            if (instanceDao.getByIdOnce(row.id) == null) instanceDao.insert(row) else instanceDao.update(row)
            // 新建柜子时把所属类型的候选池整份复制为该柜子的预选待测清单（快照式）
            if (isNew) seedPlannedFromPool(row.id, row.typeId, t)
        }
        // 新增柜子带来新待测项 → 项目不再处于"全部完成"，完成日期清零
        refreshDebugEndDate(row.projectId)
        return row.id
    }

    /** 把类型候选池中本柜还没有的条目补进预选清单，返回新增条数 */
    suspend fun seedPlannedFromPool(instanceId: String, typeId: String, ts: Long = now()): Int {
        val existing = plannedDao.contentsOnce(instanceId).map { it.trim() }.toHashSet()
        val fresh = mutableListOf<PlannedItem>()
        candDao.byTypeOnce(typeId).forEach { c ->
            val text = c.content.trim()
            if (text.isNotEmpty() && existing.add(text)) {
                fresh += PlannedItem(id = newId(), instanceId = instanceId, content = text, createdAt = ts, updatedAt = ts)
            }
        }
        plannedDao.insertAll(fresh)
        return fresh.size
    }

    /** 返回该柜子的日志条数（用于确认弹窗），-1 表示不存在。删除记墓碑随同步传播 */
    suspend fun deleteInstance(id: String): Int {
        val inst = instanceDao.getByIdOnce(id) ?: return -1
        val logs = logDao.countLogsOf(inst.id)
        db.withTransaction {
            markDeleted(DeletedItem.TBL_INSTANCES, inst.id)
            instanceDao.delete(inst)
        }
        return logs
    }

    // ---------- 调试日志 ----------

    suspend fun searchLogs(
        projectId: String, typeId: String, instanceId: String,
        status: Int, circuit: String, q: String
    ): List<LogListItem> = logDao.search(projectId, typeId, instanceId, status, circuit.trim(), q.trim())

    suspend fun getLogDetail(id: String): LogListItem? = logDao.getDetailOnce(id)

    suspend fun distinctCircuits(projectId: String, typeId: String) =
        logDao.distinctCircuits(projectId, typeId)

    /**
     * 保存日志（新建或编辑）并同步故障记录；
     * 同时把测试内容中出现的新行自动沉淀进对应柜子类型的候选池。
     * 预选待测联动：测试内容逐行（宽容匹配：忽略行尾标点）命中本柜未完成项 → 标记为"通过"并挂到本日志。
     * @return 本次自动标记为通过的预选项数量
     */
    suspend fun saveLog(log: DebugLog, faults: List<FaultRecord>, actor: String = ""): Int {
        val inst = instanceDao.getByIdOnce(log.instanceId)
            ?: throw IllegalArgumentException("柜子实例不存在")
        val t = now()
        var saved: DebugLog
        var markedCount = 0
        db.withTransaction {
            saved =
                if (log.id.isBlank())
                    log.copy(
                        id = newId(), createdAt = t, updatedAt = t,
                        createdBy = actor.ifBlank { log.createdBy },
                        updatedBy = actor.ifBlank { log.createdBy }
                    )
                else
                    log.copy(
                        updatedAt = t,
                        updatedBy = actor.ifBlank { log.updatedBy },
                        createdBy = log.createdBy.ifBlank { actor }
                    )
            if (log.id.isBlank()) logDao.insert(saved) else logDao.update(saved)
            faultDao.deleteForLog(saved.id)
            faults.forEach { f ->
                faultDao.insert(f.copy(id = f.id.ifBlank { newId() }, logId = saved.id, updatedAt = t))
            }
            // 预选待测联动：内容命中即标"通过"
            if (saved.testContent.isNotBlank()) {
                val lines = saved.testContent.split('\n').map(::normLine).filter { it.isNotEmpty() }.toHashSet()
                val hits = plannedDao.pendingForTestOnce(saved.instanceId)
                    .filter { normLine(it.content) in lines }
                if (hits.isNotEmpty()) {
                    // 命中项若上次是"未通过"，其关联故障随本次通过自动解决
                    val prevFaults = hits.map { it.faultId }.filter { it.isNotEmpty() }
                    if (prevFaults.isNotEmpty()) faultDao.resolveByIds(prevFaults, t)
                    plannedDao.setResult(hits.map { it.id }, PlannedItem.RESULT_PASS, t, saved.id, "")
                    markedCount = hits.size
                }
            }
            // 候选池自动沉淀：测试内容逐行 trim、去重后追加
            val existing = candDao.contentsOnce(inst.typeId).map { it.trim() }.toHashSet()
            saved.testContent.split('\n')
                .map { it.trim() }
                .filter { it.isNotEmpty() && existing.add(it) }
                .forEach { candDao.insert(CandidateItem(id = newId(), typeId = inst.typeId, content = it)) }
        }
        // 日志增删/故障变化后刷新项目调试完成状态
        refreshDebugEndDate(inst.projectId)
        return markedCount
    }

    /** 行规范化：去首尾空白与行尾常用标点，减少手打措辞差异导致的漏配 */
    private fun normLine(s: String): String =
        s.trim().trimEnd('。', '，', '；', '、', '！', '？', '!', '?', ',', ';', ' ')

    /** 该日志完成了哪些预选项（删除日志前的弹窗判断用） */
    suspend fun linkedPlannedOfLog(logId: String): List<PlannedItem> = plannedDao.forLogOnce(logId)

    /**
     * 删除日志。若该日志由「开始测试」生成或曾匹配完成预选项，
     * 由调用方先弹窗让用户选择：恢复为待测(重测) 或 连预选项一起删除(误添加)。
     * 记墓碑随同步传播；连项删除时每个被删的预选项也各自记墓碑。
     */
    suspend fun deleteLog(id: String, mode: LogDeleteMode) {
        val l = logDao.getByIdOnce(id) ?: return
        db.withTransaction {
            when (mode) {
                LogDeleteMode.RESTORE_PLANNED -> {
                    plannedDao.resetForLog(id, now())
                    markDeleted(DeletedItem.TBL_LOGS, l.id)
                    logDao.delete(l)
                }
                LogDeleteMode.PURGE_PLANNED -> {
                    plannedDao.forLogOnce(id).forEach { markDeleted(DeletedItem.TBL_PLANNED, it.id) }
                    plannedDao.deleteForLog(id)
                }
                LogDeleteMode.DELETE_FAULT -> {
                    // 删除故障日志：删FaultRecord + 关联消除日志 + 重新计算PlannedItem状态
                    val faults = faultDao.forLogOnce(id)
                    for (f in faults) {
                        // 查找并删除该故障的消除日志
                        val resLog = logDao.resolutionLogOf(l.instanceId, l.testContent, f.symptom)
                        if (resLog != null) {
                            markDeleted(DeletedItem.TBL_LOGS, resLog.id)
                            logDao.delete(resLog)
                        }
                        markDeleted(DeletedItem.TBL_FAULTS, f.id)
                    }
                    faultDao.deleteForLog(id)
                    recomputePlannedState(l.instanceId, l.testContent)
                    markDeleted(DeletedItem.TBL_LOGS, l.id)
                    logDao.delete(l)
                }
                LogDeleteMode.DELETE_RESOLUTION -> {
                    // 删除消除日志并驳回：删消除日志 + 恢复FaultRecord为pending
                    val matchedFaults = faultDao.byInstanceAndContentOnce(l.instanceId, l.testContent)
                        .filter { it.symptom == l.remark && it.status == FaultRecord.STATUS_RESOLVED }
                    for (f in matchedFaults) {
                        faultDao.unpassSingle(f.id)
                    }
                    // 检测未消除故障 → 驳回重测
                    recomputePlannedState(l.instanceId, l.testContent)
                    markDeleted(DeletedItem.TBL_LOGS, l.id)
                    logDao.delete(l)
                }
                LogDeleteMode.DELETE_RESOLUTION_PURGE -> {
                    // 删除消除日志+故障日志：全删
                    val matchedFaults = faultDao.byInstanceAndContentOnce(l.instanceId, l.testContent)
                        .filter { it.symptom == l.remark }
                    for (f in matchedFaults) {
                        val faultLog = logDao.getByIdOnce(f.logId)
                        if (faultLog != null) {
                            markDeleted(DeletedItem.TBL_LOGS, faultLog.id)
                            logDao.delete(faultLog)
                        }
                        markDeleted(DeletedItem.TBL_FAULTS, f.id)
                    }
                    faultDao.deleteAll(matchedFaults)
                    // 检测未消除故障 → 驳回重测
                    recomputePlannedState(l.instanceId, l.testContent)
                    markDeleted(DeletedItem.TBL_LOGS, l.id)
                    logDao.delete(l)
                }
            }
        }
        // 兜底自愈：删除日志后，凡故障记录已全部消失但仍标记「未通过」的幽灵测试项一律转「通过」
        healGhostFailures()
        // 删除日志/故障后状态变化 → 刷新项目调试完成状态
        val inst1 = instanceDao.getByIdOnce(l.instanceId)
        if (inst1 != null) refreshDebugEndDate(inst1.projectId)
    }

    /**
     * 删除故障类日志后重新计算PlannedItem状态（数据一致性核心）：
     * 有未消除故障 → 删除通过日志 + 驳回为未测并写回faultId；
     * 无未消除故障 → 若有有效通过日志（或本已是通过态）则保持「测试通过」并清除失效faultId，
     *                  否则回退为待测（无故障且从未通过不该凭空标记为通过）。
     */
    private suspend fun recomputePlannedState(instanceId: String, content: String) {
        val pendingFaults = faultDao.pendingByInstanceAndContent(instanceId, content)
        val items = plannedDao.byInstanceAndContentOnce(instanceId, content)
        if (items.isEmpty()) return
        val t = now()
        if (pendingFaults.isNotEmpty()) {
            val faultIdStr = pendingFaults.joinToString(",") { it.id }
            for (item in items) {
                // 有故障的测试项不应有通过日志：存在则一并删除
                if (item.result == PlannedItem.RESULT_PASS && item.logId.isNotEmpty()) {
                    val passLog = logDao.getByIdOnce(item.logId)
                    if (passLog != null) {
                        markDeleted(DeletedItem.TBL_LOGS, passLog.id)
                        logDao.delete(passLog)
                    }
                }
                plannedDao.setResult(listOf(item.id), PlannedItem.RESULT_UNTESTED, 0L, "", faultIdStr)
            }
        } else {
            val decision = items.map { item ->
                val hasPassLog = item.logId.isNotEmpty() &&
                    logDao.getByIdOnce(item.logId)?.logType == DebugLog.LOG_TYPE_PASS
                if (item.result == PlannedItem.RESULT_PASS || hasPassLog) {
                    Triple(item.id, PlannedItem.RESULT_PASS, item.logId) // 保持/恢复「测试通过」
                } else {
                    Triple(item.id, PlannedItem.RESULT_UNTESTED, "")       // 从未通过 → 待测
                }
            }
            for ((itemId, result, logId) in decision) {
                val at = if (result == PlannedItem.RESULT_PASS) items.first { it.id == itemId }.doneAt else 0L
                plannedDao.setResult(listOf(itemId), result, at, logId, "")
            }
        }
    }

    /**
     * 兜底自愈（用户确认·简单粗暴原则）：凡测试项 faultId 指向的故障记录已全部不存在
     * （幽灵状态——界面显示「原因见日志」但故障列表为空，来源可以是删除日志、旧快照合并、
     * 历史版本残留等任何路径），一律纠正为「测试通过」。
     * 仅处理 faultId 完全悬空的项；故障记录仍存在（含已解决待复测）的项不动，
     * 遵守「故障标已解决不会自动过关，必须人工复测」的产品规则。
     */
    suspend fun healGhostFailures() {
        val items = plannedDao.allOnce().filter { it.faultId.isNotBlank() }
        if (items.isEmpty()) return
        val allIds = items.flatMap { it.faultId.split(",") }.filter { it.isNotEmpty() }.distinct()
        if (allIds.isEmpty()) return
        val existing = faultDao.byIdsOnce(allIds).map { it.id }.toHashSet()
        val t = now()
        val affectedProjects = linkedSetOf<String>()
        for (item in items) {
            val ids = item.faultId.split(",").filter { it.isNotEmpty() }
            if (ids.isEmpty()) continue
            // faultId 全部找不到对应记录 → 该测试项实际已无故障 → 转为「测试通过」
            if (ids.all { it !in existing }) {
                plannedDao.setResult(
                    listOf(item.id), PlannedItem.RESULT_PASS,
                    if (item.doneAt > 0) item.doneAt else t,
                    item.logId, ""
                )
                instanceDao.getByIdOnce(item.instanceId)?.let { affectedProjects += it.projectId }
            }
        }
        // 幽灵故障被自愈转"通过"后刷新对应项目完成状态
        for (pid in affectedProjects) refreshDebugEndDate(pid)
    }

    // ---------- 预选待测 ----------

    /** 向某柜预选清单追加自定义条目（多行输入自动按行拆分去重），返回新增条数 */
    suspend fun addPlannedFromText(instanceId: String, text: String): Int {
        val existing = plannedDao.contentsOnce(instanceId).map { it.trim() }.toHashSet()
        val fresh = mutableListOf<PlannedItem>()
        text.split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() && existing.add(it) }
            .forEach { fresh += PlannedItem(id = newId(), instanceId = instanceId, content = it) }
        plannedDao.insertAll(fresh)
        return fresh.size
    }

    /** 从所属类型的候选池补充缺失条目，返回新增条数 */
    suspend fun syncPlannedFromPool(instanceId: String, typeId: String): Int =
        seedPlannedFromPool(instanceId, typeId)

    /**
     * 「加入常用模板」：把项目下所有柜子当前启用的预选待测项，
     * 沉淀为各自柜子类型的候选池条目（已有同名条目的自动跳过）。
     * 之后同类柜子即可在候选选择器里快速勾选；使用频次越高的项排序越靠前。
     * @return 新增候选条数
     */
    suspend fun saveProjectAsTemplate(projectId: String): Int {
        val insts = instanceDao.byProjectOnce(projectId)
        var added = 0
        db.withTransaction {
            insts.forEach { inst ->
                val contents = plannedDao.allOfInstanceOnce(inst.id)
                    .filter { it.enabled }
                    .map { it.content.trim() }
                    .filter { it.isNotEmpty() }
                    .distinct()
                if (contents.isEmpty()) return@forEach
                val existing = candDao.contentsOnce(inst.typeId).map { it.trim() }.toHashSet()
                contents.forEach { c ->
                    if (existing.add(c)) {
                        candDao.insert(CandidateItem(id = newId(), typeId = inst.typeId, content = c))
                        added++
                    }
                }
            }
        }
        return added
    }

    /**
     * 候选池按「使用频次」降序排列（频次=该类型全部柜子预选清单中出现次数；
     * 未用过的按创建时间排后）。返回 (候选, 频次) 列表，供候选选择器展示。
     */
    suspend fun candidatesByUsage(typeId: String): List<Pair<CandidateItem, Int>> {
        val usage = candDao.usageOfType(typeId).associate { it.content to it.cnt }
        return candDao.byTypeOnce(typeId)
            .sortedWith(
                compareByDescending<CandidateItem> { usage[it.content.trim()] ?: 0 }
                    .thenBy { it.createdAt }
            )
            .map { it to (usage[it.content.trim()] ?: 0) }
    }

    /** 全部柜子带项目名（跨柜拉取来源列表） */
    suspend fun allInstancesWithProject(): List<InstanceRow> = instanceDao.allWithProject()

    /**
     * 跨柜拉取预选待测：用来源柜的启用清单【整体覆盖】本柜清单。
     * 本柜原有条目（含停用的）全部删除并记墓碑随同步传播；
     * 来源项复制为新记录、重置为未测状态。
     * @return 覆盖后的清单条数
     */
    suspend fun pullPlannedFromCabinet(targetInstanceId: String, sourceInstanceId: String): Int {
        val src = plannedDao.allOfInstanceOnce(sourceInstanceId).filter { it.enabled }
        val t = now()
        var n = 0
        db.withTransaction {
            val olds = plannedDao.allOfInstanceOnce(targetInstanceId)
            olds.forEach { markDeleted(DeletedItem.TBL_PLANNED, it.id, t) }
            plannedDao.deleteForInstance(targetInstanceId)
            val fresh = src.map {
                PlannedItem(id = newId(), instanceId = targetInstanceId, content = it.content.trim(),
                    createdAt = t, updatedAt = t)
            }
            plannedDao.insertAll(fresh)
            n = fresh.size
        }
        return n
    }

    suspend fun updatePlanned(item: PlannedItem) {
        plannedDao.update(item.copy(updatedAt = now()))
    }

    /** 删除单条预选待测项，记墓碑随同步传播 */
    suspend fun deletePlanned(item: PlannedItem) {
        db.withTransaction {
            markDeleted(DeletedItem.TBL_PLANNED, item.id)
            plannedDao.delete(item)
        }
    }

    /**
     * 「开始测试」保存：每个测试项生成独立日志（不再合并），便于逐项管理。
     * 通过项：testContent=该项名称，result=PASS。
     * 未通过项：testContent=该项名称，result=FAIL，每条故障独立记录。
     * 复测通过的项若上次有未解决故障 → 自动标记已解决；
     * 测试人员必须来自调试员名单，绝不回落到登录账号；同时候选池沉淀。
     * @param failedItems 未通过项：预选项id to 故障现象列表（每项可有多条故障）
     * @return 最后一条日志id（兼容）
     */
    /**
     * 生成独立日志（永不合并）。
     * @param passIds 通过的测试项ID
     * @param failItems 新增故障: itemId -> [故障原因, ...]
     * @param resolvedFaults 已消除的故障: itemId -> [faultId, ...]（新创建故障可用symptom文本）
     * @param solutions 解决方法: faultId或symptom -> 文本（故障列表「通过」弹窗填写的）
     */
    suspend fun generateIndependentLogs(
        instanceId: String,
        passIds: List<String>,
        failItems: Map<String, List<String>>,
        resolvedFaults: Map<String, List<String>>,
        tester: String,
        actor: String,
        solutions: Map<String, String> = emptyMap()
    ) {
        val inst = instanceDao.getByIdOnce(instanceId)
            ?: throw IllegalArgumentException("柜子实例不存在")
        require(tester.isNotBlank()) { "请先绑定调试员" }
        val t = now()
        val allIds = (passIds + failItems.keys + resolvedFaults.keys).distinct()
        if (allIds.isEmpty()) return
        val items = plannedDao.byIdsOnce(allIds).filter { it.instanceId == instanceId }
        if (items.isEmpty()) return

        db.withTransaction {
            val existing = candDao.contentsOnce(inst.typeId).map { it.trim() }.toHashSet()

            for (item in items) {
                val itemId = item.id

                // 1. 新增故障 → 每条单独生成故障日志(logType=1)【必须先创建】
                val newlyCreatedFaults = mutableListOf<Pair<String, FaultRecord>>() // (symptom, record)
                val newFaults = failItems[itemId]
                if (!newFaults.isNullOrEmpty()) {
                    val faultIds = mutableListOf<String>()
                    for (symptom in newFaults) {
                        if (symptom.isBlank()) continue
                        val faultLog = DebugLog(
                            id = newId(), instanceId = instanceId, circuit = "",
                            logType = DebugLog.LOG_TYPE_FAULT,
                            testContent = item.content, tester = tester,
                            remark = symptom.trim(),
                            createdBy = actor, updatedBy = actor, createdAt = t, updatedAt = t
                        )
                        logDao.insert(faultLog)
                        val f = FaultRecord(
                            id = newId(), logId = faultLog.id, circuit = "",
                            symptom = symptom.trim(), solution = "",
                            occurredAt = t, resolvedAt = 0,
                            status = FaultRecord.STATUS_PENDING, updatedAt = t
                        )
                        faultDao.insert(f)
                        faultIds.add(f.id)
                        newlyCreatedFaults.add(symptom.trim() to f)
                    }
                    plannedDao.setResult(
                        listOf(itemId), PlannedItem.RESULT_FAIL, t, "",
                        faultIds.joinToString(",")
                    )
                    if (existing.add(item.content.trim())) {
                        candDao.insert(CandidateItem(id = newId(), typeId = inst.typeId, content = item.content.trim()))
                    }
                }

                // 2. 已消除故障 → 每条单独生成消除日志(logType=2)
                //    先尝试按faultId查找（DB中已有的故障），找不到则按symptom匹配新创建的故障
                val resolved = resolvedFaults[itemId]
                if (!resolved.isNullOrEmpty()) {
                    for (faultId in resolved) {
                        var fr = faultDao.byIdsOnce(listOf(faultId)).firstOrNull()
                        // 新创建的故障可能没有DB ID，按symptom匹配
                        if (fr == null) {
                            fr = newlyCreatedFaults.find { it.first == faultId }?.second
                        }
                        if (fr == null) continue
                        val resolutionLog = DebugLog(
                            id = newId(), instanceId = instanceId, circuit = "",
                            logType = DebugLog.LOG_TYPE_RESOLUTION,
                            testContent = item.content, tester = tester,
                            remark = fr.symptom,
                            createdBy = actor, updatedBy = actor, createdAt = t, updatedAt = t
                        )
                        logDao.insert(resolutionLog)
                        faultDao.passSingle(fr.id, t)
                        solutions[faultId]?.let { sol -> faultDao.setSolution(fr.id, sol, t) }
                    }
                    // 如果该项所有故障都已解决 → 设为通过
                    val remainingFaultIds = mutableListOf<String>()
                    remainingFaultIds.addAll(item.faultId.split(",").filter { it.isNotEmpty() })
                    remainingFaultIds.addAll(newlyCreatedFaults.map { it.second.id })
                    val remaining = faultDao.byIdsOnce(remainingFaultIds).count { it.status == FaultRecord.STATUS_PENDING }
                    if (remaining == 0) {
                        plannedDao.setResult(listOf(itemId), PlannedItem.RESULT_PASS, t, "", "")
                    }
                }

                // 3. 通过项 → 生成通过日志(logType=0)
                if (itemId in passIds) {
                    // 通过前自动解决旧故障，并为每条生成消除日志
                    if (item.faultId.isNotEmpty()) {
                        val prevFaultIds = item.faultId.split(",").filter { it.isNotEmpty() }
                        if (prevFaultIds.isNotEmpty()) {
                            val prevFaults = faultDao.byIdsOnce(prevFaultIds)
                            for (pf in prevFaults) {
                                if (pf.status == FaultRecord.STATUS_PENDING) {
                                    // 生成消除日志
                                    val resolutionLog = DebugLog(
                                        id = newId(), instanceId = instanceId, circuit = "",
                                        logType = DebugLog.LOG_TYPE_RESOLUTION,
                                        testContent = item.content, tester = tester,
                                        remark = pf.symptom,
                                        createdBy = actor, updatedBy = actor, createdAt = t, updatedAt = t
                                    )
                                    logDao.insert(resolutionLog)
                                }
                            }
                            faultDao.resolveByIds(prevFaultIds, t)
                        }
                    }
                    val passLog = DebugLog(
                        id = newId(), instanceId = instanceId, circuit = "",
                        logType = DebugLog.LOG_TYPE_PASS,
                        testContent = item.content, tester = tester,
                        remark = "",
                        createdBy = actor, updatedBy = actor, createdAt = t, updatedAt = t
                    )
                    logDao.insert(passLog)
                    plannedDao.setResult(listOf(itemId), PlannedItem.RESULT_PASS, t, passLog.id, "")
                    if (existing.add(item.content.trim())) {
                        candDao.insert(CandidateItem(id = newId(), typeId = inst.typeId, content = item.content.trim()))
                    }
                }
            }
        }
        // 测试完成后状态变化 → 刷新项目调试完成状态（可能首次达成全部完成）
        refreshDebugEndDateForInstance(inst.id)
    }

    suspend fun faultsOf(logId: String) = faultDao.forLogOnce(logId)

    /** 获取某柜某测试项的所有故障记录（通过log.testContent匹配 + faultId直接查询） */
    suspend fun faultsForTestItem(instanceId: String, content: String, faultIdStr: String = ""): List<FaultRecord> {
        val byLog = faultDao.byInstanceAndContentOnce(instanceId, content).toMutableList()
        // 补充：直接通过faultId查找（消除日志删除后恢复场景）
        if (faultIdStr.isNotBlank()) {
            val ids = faultIdStr.split(",").filter { it.isNotEmpty() }
            if (ids.isNotEmpty()) {
                val byId = faultDao.byFaultIdsOnce(ids).map { it.id }.toSet()
                faultDao.byFaultIdsOnce(ids).forEach { f ->
                    if (f.id !in byLog.map { it.id }) byLog.add(f)
                }
            }
        }
        return byLog
    }

    /** 按id列表查询故障记录（TestChecklistActivity故障列表用） */
    suspend fun faultsByIds(ids: List<String>): List<FaultRecord> = faultDao.byIdsOnce(ids)

    /** 单条故障标记通过（复测通过时调用） */
    suspend fun passSingleFault(faultId: String) {
        faultDao.passSingle(faultId)
    }

    /** 单条故障驳回（恢复为待处理） */
    suspend fun unpassSingleFault(faultId: String) {
        faultDao.unpassSingle(faultId)
    }

    /** 更新某条故障的解决方法（时间线消除条目点击编辑） */
    suspend fun updateFaultSolution(faultId: String, solution: String) {
        faultDao.setSolution(faultId, solution.trim(), now())
    }

    /** 驳回已通过的测试项：删除通过日志 + 删除关联故障 + 重置PlannedItem为未测 */
    suspend fun rejectPassedItem(item: PlannedItem) {
        val t = now()
        val p = plannedDao.getByIdOnce(item.id) ?: return
        if (p.result != PlannedItem.RESULT_PASS) return
        val logId = p.logId
        db.withTransaction {
            if (logId.isNotEmpty()) {
                val log = logDao.getByIdOnce(logId)
                if (log != null) {
                    markDeleted(DeletedItem.TBL_LOGS, logId, t)
                    logDao.delete(log)
                }
                val faults = faultDao.forLogOnce(logId)
                for (f in faults) {
                    markDeleted(DeletedItem.TBL_FAULTS, f.id, t)
                }
                faultDao.deleteForLog(logId)
            }
            plannedDao.setResult(listOf(p.id), PlannedItem.RESULT_UNTESTED, 0L, "", "")
        }
        // 撤回已通过项 → 项目不再全部完成，完成日期清零
        refreshDebugEndDateForInstance(p.instanceId)
    }

    /**
     * 获取某测试项的历史流水（故障日志→消除日志→通过日志），按时间排序。
     * 用于已通过项点击时的时间线对话框，以及日志列表点击时的时间线。
     */
    suspend fun historyTimeline(instanceId: String, itemContent: String): List<Triple<DebugLog, String, List<FaultRecord>>> {
        val logs = logDao.byInstanceAndContentOnce(instanceId, itemContent)
        return logs.map { log ->
            val faults = when (log.logType) {
                DebugLog.LOG_TYPE_FAULT -> faultDao.forLogOnce(log.id)
                // 消除日志：带回关联的已解决故障（含解决方法，供时间线显示与编辑）
                DebugLog.LOG_TYPE_RESOLUTION -> {
                    val hit = faultDao.byInstanceAndContentOnce(instanceId, itemContent)
                        .filter { it.status == FaultRecord.STATUS_RESOLVED && it.symptom == log.remark }
                    val latest = hit.maxByOrNull { it.resolvedAt }
                    if (latest == null) emptyList() else listOf(latest)
                }
                else -> emptyList()
            }
            Triple(log, log.remark, faults)
        }
    }

    // ---------- 测试员账号 ----------

    suspend fun testerAccounts(): List<TesterAccount> = db.testerAccountDao().allOnce()

    /** 注册/刷新测试员，返回是否新注册 */
    suspend fun registerTester(username: String, source: String): Boolean {
        val dao = db.testerAccountDao()
        val existed = dao.byUsername(username) != null
        if (!existed) dao.insert(TesterAccount(id = newId(), username = username, source = source))
        else dao.updateSource(username, source)
        return !existed
    }

    // ---------- 调试员名单 ----------

    suspend fun debuggers(): List<Debugger> = debuggerDao.allOnce()

    /** 新增调试员；名字为空或已存在返回false */
    suspend fun addDebugger(name: String): Boolean {
        val n = name.trim()
        if (n.isEmpty()) return false
        if (debuggerDao.byNameOnce(n) != null) return false
        debuggerDao.insert(Debugger(id = newId(), name = n))
        return true
    }

    /**
     * 改名。历史日志一律不动（用户约定：名单操作不影响已有数据）。
     * 目标名已存在返回false。
     */
    suspend fun renameDebugger(id: String, newName: String): Boolean {
        val n = newName.trim()
        if (n.isEmpty()) return false
        val hit = debuggerDao.byIdOnce(id) ?: return false
        if (n == hit.name) return true
        if (debuggerDao.byNameOnce(n) != null) return false
        debuggerDao.updateAll(listOf(hit.copy(name = n, updatedAt = now())))
        return true
    }

    /** 删除调试员（历史日志保留原姓名），记墓碑随同步传播 */
    suspend fun deleteDebugger(id: String) {
        debuggerDao.byIdOnce(id)?.let {
            db.withTransaction {
                markDeleted(DeletedItem.TBL_DEBUGGERS, it.id)
                debuggerDao.delete(it)
            }
        }
    }

    // ---------- 统计 / 导出 / 备份 ----------

    suspend fun stats(): Stats = Stats(
        projects = projectDao.count(),
        types = typeDao.count(),
        instances = instanceDao.count(),
        logs = logDao.count(),
        pendingFaults = faultDao.countPending()
    )

    suspend fun collectExport(filter: ExportFilter = ExportFilter()): Pair<List<LogListItem>, List<FaultExportRow>> {
        var (logs, faults) = logDao.exportAll() to faultDao.exportAll()
        // 状态筛选
        if (filter.status != null) {
            when (filter.status) {
                0 -> { // 含故障
                    val logIds = faults.map { it.fault.logId }.toSet()
                    logs = logs.filter { it.log.id in logIds }
                }
                1 -> { // 仅通过（logType == PASS）
                    logs = logs.filter { it.log.logType == DebugLog.LOG_TYPE_PASS }
                }
            }
        }
        // 测试人员筛选
        if (filter.testers.isNotEmpty()) {
            logs = logs.filter { it.log.tester in filter.testers }
        }
        // 柜子类型筛选
        if (filter.typeIds.isNotEmpty()) {
            logs = logs.filter { it.typeId in filter.typeIds }
        }
        // 日期范围筛选
        if (filter.dateFrom > 0) logs = logs.filter { it.log.createdAt >= filter.dateFrom }
        if (filter.dateTo > 0) logs = logs.filter { it.log.createdAt <= filter.dateTo }
        // 故障表跟随日志筛选结果
        val logIds = logs.map { it.log.id }.toSet()
        faults = faults.filter { it.fault.logId in logIds }
        return logs to faults
    }

    /**
     * 范围导出：按项目（全部柜子）或单个柜子过滤日志与故障。
     * 两个参数都为空 = 全量导出。用于项目卡/柜子长按菜单的定向导出。
     * @param filter 可选筛选条件（状态/人员/日期/列选择）
     */
    suspend fun collectExportOf(
        projectId: String = "",
        instanceId: String = "",
        filter: ExportFilter = ExportFilter()
    ):
        Pair<List<LogListItem>, List<FaultExportRow>> {
        // 先应用筛选条件
        val (filteredLogs, filteredFaults) = collectExport(filter)
        if (projectId.isBlank() && instanceId.isBlank()) return filteredLogs to filteredFaults
        // LogListItem 无 projectId 字段，经实例归属换算
        val instIds = if (projectId.isNotBlank())
            instanceDao.byProjectOnce(projectId).map { it.id }.toSet() else null
        val ls = filteredLogs.filter {
            (instIds == null || it.log.instanceId in instIds) &&
                (instanceId.isBlank() || it.log.instanceId == instanceId)
        }
        val logIds = ls.map { it.log.id }.toSet()
        val fs = filteredFaults.filter { it.fault.logId in logIds }
        return ls to fs
    }

    companion object {
        const val BACKUP_APP_TAG = "power-debug-log"
        const val BACKUP_SCHEMA = 12
        /** 时间戳冲突窗口（7.7）：仅当"同一条目双方都有"且时间差超过该值时才视为冲突、交由用户裁决 */
        const val CONFLICT_WINDOW_MS = 5L * 60L * 1000L
    }

    // ---------- 合并"新者胜"裁决 ----------

    /** 远端时间戳是否"有效更新"：LOCAL偏好下阈值内歧义一律保本地（不把远端判得更新） */
    private fun remoteNewer(favor: ConflictFavor, remoteTs: Long, localTs: Long): Boolean {
        val within = remoteTs != localTs && Math.abs(remoteTs - localTs) <= CONFLICT_WINDOW_MS
        if (favor == ConflictFavor.LOCAL && within) return false
        return remoteTs > localTs
    }

    /** 墓碑是否"有效删除"：LOCAL偏好下阈值内歧义一律视墓碑被击败（本地行存活）；相等沿用旧语义=墓碑胜 */
    private fun tombWins(favor: ConflictFavor, deletedAt: Long, rowLatest: Long): Boolean {
        val within = deletedAt != rowLatest && Math.abs(deletedAt - rowLatest) <= CONFLICT_WINDOW_MS
        if (favor == ConflictFavor.LOCAL && within) return false
        return deletedAt >= rowLatest
    }

    /**
     * 备份为 JSON 字符串。该格式同时是后期 PC/网页端的官方数据交换格式：
     * 字段名即数据库列名，schemaVersion 变更时需提供迁移说明。
     * schemaVersion 2：全表UUID主键+updatedAt合并时钟；日志含创建/修改账号。
     * schemaVersion 3：新增 plannedItems（柜子实例的预选待测清单）。
     * schemaVersion 4：plannedItems 增加三态结果 result 与关联故障 faultId。
     * schemaVersion 5：新增 debuggers（调试员名单）。
     * schemaVersion 6：新增 deletedItems（删除墓碑，删除操作随同步传播）。
     * schemaVersion 7：CabinetInstance 新增 shortName（精简名，网格视图显示用）。
     * schemaVersion 8：CabinetInstance 新增 sortOrder（拖动排序用，0=默认按名称）。
     * schemaVersion 9：CabinetInstance 新增 rowGroup（行分组编号，0=未分组）。
     * schemaVersion 10：新增 deletedProjects（项目级删除墓碑）；顶层增加 deviceTimeZoneOffset
     *                 （时区偏移毫秒，同步冲突裁决用）；readyKind/kind 字段支持 global/项目双文件增量结构。
     * schemaVersion 11：DebugLog 新增 logType 字段（0通过/1故障/2消除标注随快照同步，多端合并不再回退为"通过"）。
     *                  旧备份缺该字段时按 0（通过）解析，兼容读取。
     */
    suspend fun backupJson(): String {
        val jo = JSONObject()
        jo.put("app", BACKUP_APP_TAG)
        jo.put("schemaVersion", BACKUP_SCHEMA)
        jo.put("readyKind", "full")
        jo.put("exportedAt", now())
        jo.put("deviceTimeZoneOffset", deviceTzOffsetMillis())

        fun arr(list: List<JSONObject>): JSONArray = JSONArray().apply { list.forEach(::put) }

        jo.put("projects", arr(projectDao.allOnce().map(::projJson)))
        jo.put("cabinetTypes", arr(typeDao.allOnce().map(::typeJson)))
        jo.put("candidateItems", arr(candDao.allOnce().map(::candJson)))
        jo.put("instances", arr(instanceDao.allOnce().map(::instJson)))
        jo.put("logs", arr(logDao.allOnce().map(::logJson)))
        jo.put("faults", arr(faultDao.allOnce().map(::faultJson)))
        jo.put("plannedItems", arr(plannedDao.allOnce().map(::plannedJson)))
        jo.put("debuggers", arr(debuggerDao.allOnce().map(::debuggerJson)))
        // v6：删除墓碑（记录"什么删过了"，合并端据此删除本地对应行）
        jo.put("deletedItems", arr(tombDao.allOnce().map(::tombJson)))
        // v10：项目级删除墓碑（独立表，走全局区传播）
        jo.put("deletedProjects", arr(deletedProjectDao.allOnce().map(::projTombJson)))
        return jo.toString(2)
    }

    // ---------- 各表行序列化（备份 / 全局快照 / 单项目快照共用同一字段布局，账期同步） ----------

    private fun projJson(p: Project) = JSONObject()
        .put("id", p.id).put("name", p.name).put("code", p.code)
        .put("remark", p.remark)
        .put("debugStartDate", p.debugStartDate)
        .put("debugEndDate", p.debugEndDate)
        .put("createdAt", p.createdAt).put("updatedAt", p.updatedAt)

    private fun typeJson(t: CabinetType) = JSONObject()
        .put("id", t.id).put("name", t.name).put("remark", t.remark)
        .put("createdAt", t.createdAt).put("updatedAt", t.updatedAt)

    private fun candJson(c: CandidateItem) = JSONObject()
        .put("id", c.id).put("typeId", c.typeId).put("content", c.content)
        .put("createdAt", c.createdAt).put("updatedAt", c.updatedAt)

    private fun instJson(i: CabinetInstance) = JSONObject()
        .put("id", i.id).put("projectId", i.projectId).put("typeId", i.typeId)
        .put("name", i.name).put("deviceCode", i.deviceCode)
        .put("location", i.location).put("installer", i.installer)
        .put("shortName", i.shortName)
        .put("sortOrder", i.sortOrder)
        .put("rowGroup", i.rowGroup)
        .put("createdAt", i.createdAt).put("updatedAt", i.updatedAt)

    private fun logJson(l: DebugLog) = JSONObject()
        .put("id", l.id).put("instanceId", l.instanceId)
        .put("circuit", l.circuit).put("logType", l.logType)
        .put("testContent", l.testContent)
        .put("tester", l.tester).put("remark", l.remark)
        .put("createdBy", l.createdBy).put("updatedBy", l.updatedBy)
        .put("createdAt", l.createdAt).put("updatedAt", l.updatedAt)

    private fun faultJson(f: FaultRecord) = JSONObject()
        .put("id", f.id).put("logId", f.logId).put("circuit", f.circuit)
        .put("symptom", f.symptom).put("solution", f.solution)
        .put("occurredAt", f.occurredAt).put("resolvedAt", f.resolvedAt)
        .put("status", f.status).put("updatedAt", f.updatedAt)

    private fun plannedJson(p: PlannedItem) = JSONObject()
        .put("id", p.id).put("instanceId", p.instanceId).put("content", p.content)
        .put("enabled", if (p.enabled) 1 else 0)
        .put("doneAt", p.doneAt).put("logId", p.logId)
        .put("result", p.result).put("faultId", p.faultId)
        .put("createdAt", p.createdAt).put("updatedAt", p.updatedAt)

    private fun debuggerJson(d: Debugger) = JSONObject()
        .put("id", d.id).put("name", d.name)
        .put("createdAt", d.createdAt).put("updatedAt", d.updatedAt)

    private fun tombJson(d: DeletedItem) = JSONObject()
        .put("id", d.id).put("tbl", d.tbl).put("itemId", d.itemId)
        .put("deletedAt", d.deletedAt)

    private fun projTombJson(d: DeletedProject) = JSONObject()
        .put("id", d.id).put("projectId", d.projectId)
        .put("deletedAt", d.deletedAt)

    // ---------- 新版增量同步（7.5 全局区 + 每项目独立文件）的数据源 ----------

    /**
     * 全局区快照（7.5 global/backup_<账号>_global.json.gz）：
     * 柜型 + 候选池 + 调试员 + 通用删除墓碑 + 项目级删除墓碑。
     * 测试员账号(tester_accounts)是本地身份注册表（无 updatedAt 时钟、无合并语义），
     * 与现版口径一致不随同步传播。
     */
    suspend fun globalSnapshot(): String {
        val jo = JSONObject()
        jo.put("app", BACKUP_APP_TAG)
        jo.put("schemaVersion", BACKUP_SCHEMA)
        jo.put("readyKind", "global")
        jo.put("exportedAt", now())
        jo.put("deviceTimeZoneOffset", deviceTzOffsetMillis())
        fun arr(list: List<JSONObject>): JSONArray = JSONArray().apply { list.forEach(::put) }
        jo.put("cabinetTypes", arr(typeDao.allOnce().map(::typeJson)))
        jo.put("candidateItems", arr(candDao.allOnce().map(::candJson)))
        jo.put("debuggers", arr(debuggerDao.allOnce().map(::debuggerJson)))
        jo.put("deletedItems", arr(tombDao.allOnce().map(::tombJson)))
        jo.put("deletedProjects", arr(deletedProjectDao.allOnce().map(::projTombJson)))
        return jo.toString(2)
    }

    /**
     * 单项目快照（7.5 projects/project_<UUID>/backup_<账号>_project.json.gz）：
     * 项目行 + 该项目全部 柜子/日志/故障/预选项（删除墓碑统一走全局区传播，不入项目文件，
     * 避免为墓碑追溯所属项目而扩充 deleted_items 结构）。
     */
    suspend fun projectSnapshot(projectId: String): String {
        val jo = JSONObject()
        jo.put("app", BACKUP_APP_TAG)
        jo.put("schemaVersion", BACKUP_SCHEMA)
        jo.put("readyKind", "project")
        jo.put("exportedAt", now())
        jo.put("deviceTimeZoneOffset", deviceTzOffsetMillis())
        fun arr(list: List<JSONObject>): JSONArray = JSONArray().apply { list.forEach(::put) }
        val insts = instanceDao.allOnce().filter { it.projectId == projectId }
        val instIds = insts.map { it.id }.toHashSet()
        val logs = logDao.allOnce().filter { it.instanceId in instIds }
        val logIds = logs.map { it.id }.toHashSet()
        jo.put("projects", arr(projectDao.allOnce().filter { it.id == projectId }.map(::projJson)))
        jo.put("instances", arr(insts.map(::instJson)))
        jo.put("logs", arr(logs.map(::logJson)))
        jo.put("faults", arr(faultDao.allOnce().filter { it.logId in logIds }.map(::faultJson)))
        jo.put("plannedItems", arr(plannedDao.allOnce().filter { it.instanceId in instIds }.map(::plannedJson)))
        return jo.toString(2)
    }

    /**
     * 每项目"版本时钟" = 该项目所有行（项目/柜子/日志/故障/预选项）最新 updatedAt。
     * 本地任意增删改只推高该项目时钟；据此与 SyncStore.projectLastSync 比较决定是否上传（7.6 阶段C），
     * 不依赖安卓文件 mtime（厂商写入时间差异大，不可信）。
     */
    suspend fun projectClocks(): Map<String, Long> {
        val all = HashMap<String, Long>()
        fun touch(pid: String, ts: Long) {
            if (ts > (all[pid] ?: 0L)) all[pid] = ts
        }
        val insts = instanceDao.allOnce()
        val instProj = HashMap<String, String>()
        insts.forEach { instProj[it.id] = it.projectId }
        insts.forEach { touch(it.projectId, it.updatedAt) }
        val logs = logDao.allOnce()
        val logToInst = HashMap<String, String>()
        logs.forEach { logToInst[it.id] = it.instanceId }
        logs.forEach { l -> instProj[l.instanceId]?.let { touch(it, l.updatedAt) } }
        faultDao.allOnce().forEach { f ->
            logToInst[f.logId]?.let { instProj[it]?.let { p -> touch(p, f.updatedAt) } }
        }
        plannedDao.allOnce().forEach { p -> instProj[p.instanceId]?.let { touch(it, p.updatedAt) } }
        projectDao.allOnce().forEach { touch(it.id, it.updatedAt) }
        return all
    }

    /**
     * 快照与本机是否存在"时钟窗口内歧义"（7.7）：同一行双方都有但时间戳不同且差距
     * <= CONFLICT_WINDOW_MS 时无法用墙钟可靠裁决，合并前应弹窗让用户选「保留本地/覆盖云端」。
     */
    suspend fun hasMergeConflict(text: String): Boolean {
        val pb = parseBackup(text)
        fun ambiguous(remoteTs: Long, localTs: Long) =
            remoteTs != localTs && kotlin.math.abs(remoteTs - localTs) <= CONFLICT_WINDOW_MS
        fun clashes(remote: List<Pair<String, Long>>, local: Map<String, Long>): Boolean =
            remote.any { (id, rts) -> local[id]?.let { ambiguous(rts, it) } == true }
        val lp = projectDao.allOnce().associateBy { it.id }
        val lt = typeDao.allOnce().associateBy { it.id }
        val lc = candDao.allOnce().associateBy { it.id }
        val li = instanceDao.allOnce().associateBy { it.id }
        val ll = logDao.allOnce().associateBy { it.id }
        val lf = faultDao.allOnce().associateBy { it.id }
        val lpl = plannedDao.allOnce().associateBy { it.id }
        val ld = debuggerDao.allOnce().associateBy { it.id }
        return clashes(pb.projects.map { it.id to it.updatedAt }, lp.mapValues { it.value.updatedAt }) ||
            clashes(pb.types.map { it.id to it.updatedAt }, lt.mapValues { it.value.updatedAt }) ||
            clashes(pb.cands.map { it.id to it.updatedAt }, lc.mapValues { it.value.updatedAt }) ||
            clashes(pb.instances.map { it.id to it.updatedAt }, li.mapValues { it.value.updatedAt }) ||
            clashes(pb.logs.map { it.id to it.updatedAt }, ll.mapValues { it.value.updatedAt }) ||
            clashes(pb.faults.map { it.id to it.updatedAt }, lf.mapValues { it.value.updatedAt }) ||
            clashes(pb.planned.map { it.id to it.updatedAt }, lpl.mapValues { it.value.updatedAt }) ||
            clashes(pb.debuggers.map { it.id to it.updatedAt }, ld.mapValues { it.value.updatedAt }) ||
            // 项目删除墓碑与本机存活项目时间戳的歧义
            pb.deletedProjects.any { dp ->
                lp[dp.projectId]?.let { ambiguous(dp.deletedAt, it.updatedAt) } == true
            }
    }

    /** 本机当前时区相对UTC的偏移（毫秒），写入快照头供对端做时钟偏差感知 */
    fun deviceTzOffsetMillis(): Int = TimeZone.getDefault().getOffset(now())

    /**
     * 解析备份JSON，统一转换为本机实体。
     * 支持 schemaVersion 2（直接使用）；也支持 1（旧版int主键备份：生成新UUID并重映射全部引用，
     * 缺失的合并时钟字段以 createdAt 兜底）。
     */
    private fun parseBackup(text: String): ParsedBackup {
        val root = JSONObject(text)
        require(root.optString("app") == BACKUP_APP_TAG) { "不是本应用的备份文件" }
        val version = root.optInt("schemaVersion", 1)
        require(version in 1..BACKUP_SCHEMA) { "不支持的备份版本：$version" }
        val pb = ParsedBackup()

        if (version >= 2) {
            root.optJSONArray("projects")?.let { a ->
                for (i in 0 until a.length()) with(a.getJSONObject(i)) {
                    pb.projects += Project(
                        id = getString("id"), name = getString("name"),
                        code = optString("code"), remark = optString("remark"),
                        debugStartDate = optLong("debugStartDate"),
                        debugEndDate = optLong("debugEndDate"),
                        createdAt = optLong("createdAt"), updatedAt = optLong("updatedAt")
                    )
                }
            }
            root.optJSONArray("cabinetTypes")?.let { a ->
                for (i in 0 until a.length()) with(a.getJSONObject(i)) {
                    pb.types += CabinetType(
                        id = getString("id"), name = getString("name"),
                        remark = optString("remark"),
                        createdAt = optLong("createdAt"), updatedAt = optLong("updatedAt")
                    )
                }
            }
            root.optJSONArray("candidateItems")?.let { a ->
                for (i in 0 until a.length()) with(a.getJSONObject(i)) {
                    pb.cands += CandidateItem(
                        id = getString("id"), typeId = getString("typeId"),
                        content = getString("content"),
                        createdAt = optLong("createdAt"), updatedAt = optLong("updatedAt")
                    )
                }
            }
            root.optJSONArray("instances")?.let { a ->
                for (i in 0 until a.length()) with(a.getJSONObject(i)) {
                    pb.instances += CabinetInstance(
                        id = getString("id"), projectId = getString("projectId"),
                        typeId = getString("typeId"), name = getString("name"),
                        deviceCode = optString("deviceCode"), location = optString("location"),
                        installer = optString("installer"),
                        shortName = optString("shortName"),
                        sortOrder = optInt("sortOrder"),
                        rowGroup = optInt("rowGroup"),
                        createdAt = optLong("createdAt"), updatedAt = optLong("updatedAt")
                    )
                }
            }
            root.optJSONArray("logs")?.let { a ->
                for (i in 0 until a.length()) with(a.getJSONObject(i)) {
                    pb.logs += DebugLog(
                        id = getString("id"), instanceId = getString("instanceId"),
                        circuit = optString("circuit"), testContent = getString("testContent"),
                        logType = optInt("logType", DebugLog.LOG_TYPE_PASS),
                        tester = optString("tester"), remark = optString("remark"),
                        createdBy = optString("createdBy"), updatedBy = optString("updatedBy"),
                        createdAt = optLong("createdAt"), updatedAt = optLong("updatedAt")
                    )
                }
            }
            root.optJSONArray("faults")?.let { a ->
                for (i in 0 until a.length()) with(a.getJSONObject(i)) {
                    pb.faults += FaultRecord(
                        id = getString("id"), logId = getString("logId"),
                        circuit = optString("circuit"), symptom = optString("symptom"),
                        solution = optString("solution"), occurredAt = optLong("occurredAt"),
                        resolvedAt = optLong("resolvedAt"), status = optInt("status"),
                        updatedAt = optLong("updatedAt")
                    )
                }
            }
            root.optJSONArray("plannedItems")?.let { a ->
                for (i in 0 until a.length()) with(a.getJSONObject(i)) {
                    pb.planned += PlannedItem(
                        id = getString("id"), instanceId = getString("instanceId"),
                        content = getString("content"),
                        enabled = optInt("enabled", 1) != 0,
                        doneAt = optLong("doneAt"), logId = optString("logId"),
                        result = optInt("result", PlannedItem.RESULT_UNTESTED),
                        faultId = optString("faultId"),
                        createdAt = optLong("createdAt"), updatedAt = optLong("updatedAt")
                    )
                }
            }
            // v5起新增；旧版备份（v2~v4）无此数组，静默跳过
            root.optJSONArray("debuggers")?.let { a ->
                for (i in 0 until a.length()) with(a.getJSONObject(i)) {
                    pb.debuggers += Debugger(
                        id = getString("id"), name = getString("name"),
                        createdAt = optLong("createdAt"), updatedAt = optLong("updatedAt")
                    )
                }
            }
            // v6起新增：删除墓碑；旧版备份无此数组静默跳过。
            // 项目级墓碑（tbl='projects'）在 v11 起改走独立 deletedProjects 表，解析时等价并入
            root.optJSONArray("deletedItems")?.let { a ->
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    val tbl = o.optString("tbl")
                    val itemId = o.optString("itemId")
                    if (tbl.isEmpty() || itemId.isEmpty()) continue
                    if (tbl == DeletedItem.TBL_PROJECTS) {
                        pb.deletedProjects += DeletedProject(
                            id = o.optString("id").ifBlank { newId() },
                            projectId = itemId, deletedAt = o.optLong("deletedAt")
                        )
                    } else {
                        pb.tombs += DeletedItem(
                            id = o.optString("id").ifBlank { newId() },
                            tbl = tbl, itemId = itemId, deletedAt = o.optLong("deletedAt")
                        )
                    }
                }
            }
            // v10起新增：项目级删除墓碑；旧版备份无此数组静默跳过
            root.optJSONArray("deletedProjects")?.let { a ->
                for (i in 0 until a.length()) with(a.getJSONObject(i)) {
                    val pid = optString("projectId")
                    if (pid.isNotEmpty()) pb.deletedProjects += DeletedProject(
                        id = optString("id").ifBlank { newId() },
                        projectId = pid, deletedAt = optLong("deletedAt")
                    )
                }
            }
            return pb
        }

        // ---- v1 旧格式：int主键 → UUID 重映射 ----
        val mapP = HashMap<Long, String>()
        val mapT = HashMap<Long, String>()
        val mapC = HashMap<Long, String>()
        val mapI = HashMap<Long, String>()
        val mapL = HashMap<Long, String>()
        val mapF = HashMap<Long, String>()

        fun idOf(map: MutableMap<Long, String>, old: Long): String =
            map.getOrPut(old) { newId() }

        root.optJSONArray("projects")?.let { a ->
            for (i in 0 until a.length()) with(a.getJSONObject(i)) {
                val old = getLong("id"); val t = optLong("createdAt")
                pb.projects += Project(
                    id = idOf(mapP, old), name = getString("name"),
                    code = optString("code"), remark = optString("remark"),
                    createdAt = t, updatedAt = optLong("updatedAt", t)
                )
            }
        }
        root.optJSONArray("cabinetTypes")?.let { a ->
            for (i in 0 until a.length()) with(a.getJSONObject(i)) {
                val old = getLong("id"); val t = optLong("createdAt")
                pb.types += CabinetType(
                    id = idOf(mapT, old), name = getString("name"),
                    remark = optString("remark"), createdAt = t, updatedAt = optLong("updatedAt", t)
                )
            }
        }
        root.optJSONArray("candidateItems")?.let { a ->
            for (i in 0 until a.length()) with(a.getJSONObject(i)) {
                val old = getLong("id"); val t = optLong("createdAt")
                pb.cands += CandidateItem(
                    id = idOf(mapC, old), typeId = idOf(mapT, getLong("typeId")),
                    content = getString("content"), createdAt = t, updatedAt = optLong("updatedAt", t)
                )
            }
        }
        root.optJSONArray("instances")?.let { a ->
            for (i in 0 until a.length()) with(a.getJSONObject(i)) {
                val old = getLong("id"); val t = optLong("createdAt")
                pb.instances += CabinetInstance(
                    id = idOf(mapI, old),
                    projectId = idOf(mapP, getLong("projectId")),
                    typeId = idOf(mapT, getLong("typeId")),
                    name = getString("name"),
                    deviceCode = optString("deviceCode"), location = optString("location"),
                    installer = optString("installer"),
                    shortName = optString("shortName"),
                    createdAt = t, updatedAt = optLong("updatedAt", t)
                )
            }
        }
        root.optJSONArray("logs")?.let { a ->
            for (i in 0 until a.length()) with(a.getJSONObject(i)) {
                val old = getLong("id"); val t = optLong("createdAt")
                pb.logs += DebugLog(
                    id = idOf(mapL, old),
                    instanceId = idOf(mapI, getLong("instanceId")),
                    circuit = optString("circuit"), testContent = getString("testContent"),
                    logType = optInt("logType", DebugLog.LOG_TYPE_PASS),
                    tester = optString("tester"), remark = optString("remark"),
                    createdBy = "", updatedBy = "",
                    createdAt = t, updatedAt = optLong("updatedAt", t)
                )
            }
        }
        root.optJSONArray("faults")?.let { a ->
            for (i in 0 until a.length()) with(a.getJSONObject(i)) {
                val old = getLong("id"); val t = optLong("occurredAt")
                pb.faults += FaultRecord(
                    id = idOf(mapF, old),
                    logId = idOf(mapL, getLong("logId")),
                    circuit = optString("circuit"), symptom = optString("symptom"),
                    solution = optString("solution"), occurredAt = t,
                    resolvedAt = optLong("resolvedAt"), status = optInt("status"),
                    updatedAt = optLong("updatedAt", t)
                )
            }
        }
        return pb
    }

    /**
     * 从 JSON 恢复（整体覆盖）。解析失败会抛异常且不改动现有数据。
     * 支持 v1/v2 备份文件。
     */
    suspend fun restoreJson(text: String): Stats {
        val root = JSONObject(text)
        require(root.optString("app") == BACKUP_APP_TAG) { "不是本应用的备份文件" }
        val version = root.optInt("schemaVersion", 1)
        require(version in 1..BACKUP_SCHEMA) { "不支持的备份版本：$version" }

        val projects = mutableListOf<Project>()
        val types = mutableListOf<CabinetType>()
        val cands = mutableListOf<CandidateItem>()
        val instances = mutableListOf<CabinetInstance>()
        val logs = mutableListOf<DebugLog>()
        val faults = mutableListOf<FaultRecord>()
        val planned = mutableListOf<PlannedItem>()
        val debuggers = mutableListOf<Debugger>()
        val tombs = mutableListOf<DeletedItem>()
        val deletedProjects = mutableListOf<DeletedProject>()

        if (version >= 2) {
            root.optJSONArray("projects")?.let { a ->
                for (i in 0 until a.length()) with(a.getJSONObject(i)) {
                    projects += Project(
                        id = getString("id"), name = getString("name"),
                        code = optString("code"), remark = optString("remark"),
                        debugStartDate = optLong("debugStartDate"),
                        debugEndDate = optLong("debugEndDate"),
                        createdAt = optLong("createdAt"), updatedAt = optLong("updatedAt")
                    )
                }
            }
            root.optJSONArray("cabinetTypes")?.let { a ->
                for (i in 0 until a.length()) with(a.getJSONObject(i)) {
                    types += CabinetType(
                        id = getString("id"), name = getString("name"),
                        remark = optString("remark"),
                        createdAt = optLong("createdAt"), updatedAt = optLong("updatedAt")
                    )
                }
            }
            root.optJSONArray("candidateItems")?.let { a ->
                for (i in 0 until a.length()) with(a.getJSONObject(i)) {
                    cands += CandidateItem(
                        id = getString("id"), typeId = getString("typeId"),
                        content = getString("content"),
                        createdAt = optLong("createdAt"), updatedAt = optLong("updatedAt")
                    )
                }
            }
            root.optJSONArray("instances")?.let { a ->
                for (i in 0 until a.length()) with(a.getJSONObject(i)) {
                    instances += CabinetInstance(
                        id = getString("id"), projectId = getString("projectId"),
                        typeId = getString("typeId"), name = getString("name"),
                        deviceCode = optString("deviceCode"), location = optString("location"),
                        installer = optString("installer"),
                        shortName = optString("shortName"),
                        createdAt = optLong("createdAt"), updatedAt = optLong("updatedAt")
                    )
                }
            }
            root.optJSONArray("logs")?.let { a ->
                for (i in 0 until a.length()) with(a.getJSONObject(i)) {
                    logs += DebugLog(
                        id = getString("id"), instanceId = getString("instanceId"),
                        circuit = optString("circuit"), testContent = getString("testContent"),
                        logType = optInt("logType", DebugLog.LOG_TYPE_PASS),
                        tester = optString("tester"), remark = optString("remark"),
                        createdBy = optString("createdBy"), updatedBy = optString("updatedBy"),
                        createdAt = optLong("createdAt"), updatedAt = optLong("updatedAt")
                    )
                }
            }
            root.optJSONArray("faults")?.let { a ->
                for (i in 0 until a.length()) with(a.getJSONObject(i)) {
                    faults += FaultRecord(
                        id = getString("id"), logId = getString("logId"),
                        circuit = optString("circuit"), symptom = optString("symptom"),
                        solution = optString("solution"), occurredAt = optLong("occurredAt"),
                        resolvedAt = optLong("resolvedAt"), status = optInt("status"),
                        updatedAt = optLong("updatedAt")
                    )
                }
            }
            root.optJSONArray("plannedItems")?.let { a ->
                for (i in 0 until a.length()) with(a.getJSONObject(i)) {
                    planned += PlannedItem(
                        id = getString("id"), instanceId = getString("instanceId"),
                        content = getString("content"),
                        enabled = optInt("enabled", 1) != 0,
                        doneAt = optLong("doneAt"), logId = optString("logId"),
                        result = optInt("result", PlannedItem.RESULT_UNTESTED),
                        faultId = optString("faultId"),
                        createdAt = optLong("createdAt"), updatedAt = optLong("updatedAt")
                    )
                }
            }
            root.optJSONArray("debuggers")?.let { a ->
                for (i in 0 until a.length()) with(a.getJSONObject(i)) {
                    debuggers += Debugger(
                        id = getString("id"), name = getString("name"),
                        createdAt = optLong("createdAt"), updatedAt = optLong("updatedAt")
                    )
                }
            }
            root.optJSONArray("deletedItems")?.let { a ->
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    val tbl = o.optString("tbl")
                    val itemId = o.optString("itemId")
                    if (tbl.isEmpty() || itemId.isEmpty()) continue
                    if (tbl == DeletedItem.TBL_PROJECTS) {
                        deletedProjects += DeletedProject(
                            id = o.optString("id").ifBlank { newId() },
                            projectId = itemId, deletedAt = o.optLong("deletedAt")
                        )
                    } else {
                        tombs += DeletedItem(
                            id = o.optString("id").ifBlank { newId() },
                            tbl = tbl, itemId = itemId, deletedAt = o.optLong("deletedAt")
                        )
                    }
                }
            }
            root.optJSONArray("deletedProjects")?.let { a ->
                for (i in 0 until a.length()) with(a.getJSONObject(i)) {
                    val pid = optString("projectId")
                    if (pid.isNotEmpty()) deletedProjects += DeletedProject(
                        id = optString("id").ifBlank { newId() },
                        projectId = pid, deletedAt = optLong("deletedAt")
                    )
                }
            }
        } else {
            // v1：解析后走同一套uuid重映射（复用parse逻辑）
            val pb = parseBackup(text)
            projects += pb.projects; types += pb.types; cands += pb.cands
            instances += pb.instances; logs += pb.logs; faults += pb.faults
            planned += pb.planned
            deletedProjects += pb.deletedProjects
        }

        db.withTransaction {
            faultDao.wipe(); logDao.wipe(); instanceDao.wipe()
            candDao.wipe(); typeDao.wipe(); projectDao.wipe()
            plannedDao.wipe()
            debuggerDao.wipe()
            tombDao.wipe()
            deletedProjectDao.wipe()
            projectDao.insertAll(projects)
            typeDao.insertAll(types)
            candDao.insertAll(cands)
            instanceDao.insertAll(instances)
            logDao.insertAll(logs)
            faultDao.upsertAll(faults)
            if (planned.isNotEmpty()) plannedDao.insertAll(planned)
            if (debuggers.isNotEmpty()) debuggerDao.insertAll(debuggers)
            if (tombs.isNotEmpty()) tombDao.insertAll(tombs)
            if (deletedProjects.isNotEmpty()) deletedProjectDao.insertAll(deletedProjects)
        }
        return Stats(projects.size, types.size, instances.size, logs.size, faults.count { it.status == FaultRecord.STATUS_PENDING })
    }

    /**
     * 智能合并远端数据到本机：
     * 1) 墓碑先行——远端删除记录落库并应用删除（经DAO删除，级联与本机一致）；
     * 2) 数据按id去重、同id冲突updatedAt新者胜；已删id与父链已断的孤儿行一律跳过，
     *    防止被删记录借旧快照复活。
     */
    suspend fun mergeJson(text: String, favor: ConflictFavor = ConflictFavor.CLOUD): MergeResult =
        applyMerge(parseBackup(text), favor)

    /**
     * 合并预览（只读不写库）：返回与真实合并一致的统计结果。
     */
    suspend fun mergePreview(text: String): MergeResult {
        val pb = parseBackup(text)
        // 墓碑全集 = 本机已有 ∪ 快照带来的（项目级墓碑走 deleted_projects 统一口径）
        val tombs = HashMap<String, HashSet<String>>()
        tombDao.allOnce().forEach { tombs.getOrPut(it.tbl) { HashSet() }.add(it.itemId) }
        pb.tombs.forEach { tombs.getOrPut(it.tbl) { HashSet() }.add(it.itemId) }
        fun dead(tbl: String, id: String) = tombs[tbl]?.contains(id) == true

        val projDead = HashSet<String>()
        tombDao.allOnce().filter { it.tbl == DeletedItem.TBL_PROJECTS }.forEach { projDead.add(it.itemId) }
        deletedProjectDao.allOnce().forEach { projDead.add(it.projectId) }
        pb.deletedProjects.forEach { projDead.add(it.projectId) }
        fun deadP(pid: String) = pid in projDead

        // 预计本机会被墓碑删掉的行数（各表直接命中数；级联另计）
        var approxDel = 0
        approxDel += projectDao.allOnce().count { deadP(it.id) }
        approxDel += typeDao.allOnce().count { dead(DeletedItem.TBL_TYPES, it.id) }
        approxDel += candDao.allOnce().count { dead(DeletedItem.TBL_CANDS, it.id) }
        approxDel += instanceDao.allOnce().count { dead(DeletedItem.TBL_INSTANCES, it.id) }
        approxDel += logDao.allOnce().count { dead(DeletedItem.TBL_LOGS, it.id) }
        approxDel += faultDao.allOnce().count { dead(DeletedItem.TBL_FAULTS, it.id) }
        approxDel += plannedDao.allOnce().count { dead(DeletedItem.TBL_PLANNED, it.id) }
        approxDel += debuggerDao.allOnce().count { dead(DeletedItem.TBL_DEBUGGERS, it.id) }

        val lp = projectDao.allOnce().associateBy { it.id }
        val lt = typeDao.allOnce().associateBy { it.id }
        val lc = candDao.allOnce()
        val li = instanceDao.allOnce().associateBy { it.id }
        val ll = logDao.allOnce().associateBy { it.id }
        val lf = faultDao.allOnce().associateBy { it.id }

        fun newer(map: Map<String, Long>, id: String, ts: Long) =
            map[id]?.let { it < ts } == true

        val lcById = lc.associateBy { it.id }
        val lcPair = lc.map { it.typeId to it.content }.toHashSet()
        val lpl = plannedDao.allOnce()
        val lplById = lpl.associateBy { it.id }
        val lplPair = lpl.map { it.instanceId to it.content }.toHashSet()
        val ld = debuggerDao.allOnce()
        val ldById = ld.associateBy { it.id }
        val ldNames = ld.map { it.name }.toHashSet()

        // 存活父集合 = 本机现存 ∪ 快照中未被删的
        val aliveP = buildSet {
            addAll(lp.keys); pb.projects.forEach { if (!deadP(it.id)) add(it.id) }
        }
        val aliveT = buildSet {
            addAll(lt.keys); pb.types.forEach { if (!dead(DeletedItem.TBL_TYPES, it.id)) add(it.id) }
        }
        val aliveI = buildSet {
            addAll(li.keys)
            pb.instances.filter { it.projectId in aliveP && it.typeId in aliveT && !dead(DeletedItem.TBL_INSTANCES, it.id) }
                .forEach { add(it.id) }
        }
        val aliveL = buildSet {
            addAll(ll.keys)
            pb.logs.filter { it.instanceId in aliveI && !dead(DeletedItem.TBL_LOGS, it.id) }.forEach { add(it.id) }
        }

        return MergeResult(
            newProjects = pb.projects.count { it.id !in lp && !deadP(it.id) },
            updProjects = pb.projects.count { !deadP(it.id) && newer(lp.mapValues { it.value.updatedAt }, it.id, it.updatedAt) },
            newTypes = pb.types.count { it.id !in lt && !dead(DeletedItem.TBL_TYPES, it.id) },
            updTypes = pb.types.count { !dead(DeletedItem.TBL_TYPES, it.id) && newer(lt.mapValues { it.value.updatedAt }, it.id, it.updatedAt) },
            newCands = pb.cands.count {
                it.typeId in aliveT && it.id !in lcById &&
                    (it.typeId to it.content) !in lcPair && !dead(DeletedItem.TBL_CANDS, it.id)
            },
            newInstances = pb.instances.count {
                it.id !in li && it.projectId in aliveP && it.typeId in aliveT && !dead(DeletedItem.TBL_INSTANCES, it.id)
            },
            updInstances = pb.instances.count { !dead(DeletedItem.TBL_INSTANCES, it.id) && newer(li.mapValues { it.value.updatedAt }, it.id, it.updatedAt) },
            newLogs = pb.logs.count { it.id !in ll && it.instanceId in aliveI && !dead(DeletedItem.TBL_LOGS, it.id) },
            updLogs = pb.logs.count { !dead(DeletedItem.TBL_LOGS, it.id) && newer(ll.mapValues { it.value.updatedAt }, it.id, it.updatedAt) },
            newFaults = pb.faults.count {
                it.id !in lf && (it.logId.isBlank() || it.logId in aliveL) && !dead(DeletedItem.TBL_FAULTS, it.id)
            },
            updFaults = pb.faults.count { !dead(DeletedItem.TBL_FAULTS, it.id) && newer(lf.mapValues { it.value.updatedAt }, it.id, it.updatedAt) },
            newPlanned = pb.planned.count {
                it.instanceId in aliveI && it.id !in lplById &&
                    (it.instanceId to it.content) !in lplPair && !dead(DeletedItem.TBL_PLANNED, it.id)
            },
            updPlanned = pb.planned.count { !dead(DeletedItem.TBL_PLANNED, it.id) && newer(lplById.mapValues { it.value.updatedAt }, it.id, it.updatedAt) },
            newDebuggers = pb.debuggers.count {
                it.id !in ldById && it.name !in ldNames && !dead(DeletedItem.TBL_DEBUGGERS, it.id)
            },
            updDebuggers = pb.debuggers.count { d ->
                if (dead(DeletedItem.TBL_DEBUGGERS, d.id)) return@count false
                val local = ldById[d.id] ?: return@count false
                if (d.updatedAt <= local.updatedAt) return@count false
                // 改名目标与其他本地行重名时无法安全更新（唯一索引），跳过
                ld.none { it.id != d.id && it.name == d.name }
            },
            appliedTombs = approxDel
        )
    }

    private suspend fun applyMerge(pb: ParsedBackup, favor: ConflictFavor = ConflictFavor.CLOUD): MergeResult {
        val r = MergeResult()
        db.withTransaction {
            // ---------- 0) 墓碑先行：远端墓碑落库（IGNORE去重，(表,记录id)保留首条=最早删除时刻） ----------
            if (pb.tombs.isNotEmpty()) tombDao.insertAll(pb.tombs)
            // 项目级墓碑：远端 deletedProjects 同样先落库（保留最早删除时刻）
            if (pb.deletedProjects.isNotEmpty()) deletedProjectDao.insertAll(pb.deletedProjects)

            // 0.1 墓碑「新者胜」调和：删除时刻早于该行已知最新更新（本机存活行 ∪ 快照携带行）
            //     → 墓碑被击败不生效并自清理，防止旧机删除清掉他机较新数据，也避免在应用删除前
            //       就先对胜出的父行做级联误删。留存墓碑=删除确实晚于该行已知全部更新，删除成立。
            // 汇总各表(本机+快照)每行已知最新更新时间
            val knownRows = HashMap<String, HashMap<String, Long>>()
            fun track(tbl: String, id: String, updatedAt: Long) {
                val m = knownRows.getOrPut(tbl) { HashMap() }
                if (m[id] == null || updatedAt > m[id]!!) m[id] = updatedAt
            }
            projectDao.allOnce().forEach { track(DeletedItem.TBL_PROJECTS, it.id, it.updatedAt) }
            pb.projects.forEach { track(DeletedItem.TBL_PROJECTS, it.id, it.updatedAt) }
            typeDao.allOnce().forEach { track(DeletedItem.TBL_TYPES, it.id, it.updatedAt) }
            pb.types.forEach { track(DeletedItem.TBL_TYPES, it.id, it.updatedAt) }
            candDao.allOnce().forEach { track(DeletedItem.TBL_CANDS, it.id, it.updatedAt) }
            pb.cands.forEach { track(DeletedItem.TBL_CANDS, it.id, it.updatedAt) }
            instanceDao.allOnce().forEach { track(DeletedItem.TBL_INSTANCES, it.id, it.updatedAt) }
            pb.instances.forEach { track(DeletedItem.TBL_INSTANCES, it.id, it.updatedAt) }
            logDao.allOnce().forEach { track(DeletedItem.TBL_LOGS, it.id, it.updatedAt) }
            pb.logs.forEach { track(DeletedItem.TBL_LOGS, it.id, it.updatedAt) }
            faultDao.allOnce().forEach { track(DeletedItem.TBL_FAULTS, it.id, it.updatedAt) }
            pb.faults.forEach { track(DeletedItem.TBL_FAULTS, it.id, it.updatedAt) }
            plannedDao.allOnce().forEach { track(DeletedItem.TBL_PLANNED, it.id, it.updatedAt) }
            pb.planned.forEach { track(DeletedItem.TBL_PLANNED, it.id, it.updatedAt) }
            debuggerDao.allOnce().forEach { track(DeletedItem.TBL_DEBUGGERS, it.id, it.updatedAt) }
            pb.debuggers.forEach { track(DeletedItem.TBL_DEBUGGERS, it.id, it.updatedAt) }

            // 项目级墓碑统一口径 = deleted_projects 表 ∪ 旧版 deleted_items(tbl='projects')（本地历史残留）
            val projDead = HashMap<String, Long>()
            deletedProjectDao.allOnce().forEach {
                val prev = projDead[it.projectId]
                if (prev == null || it.deletedAt < prev) projDead[it.projectId] = it.deletedAt
            }
            tombDao.allOnce().filter { it.tbl == DeletedItem.TBL_PROJECTS }.forEach {
                val prev = projDead[it.itemId]
                if (prev == null || it.deletedAt < prev) projDead[it.itemId] = it.deletedAt
            }

            // 通用删除墓碑 = deleted_items 中除项目外的各行
            val tombs = HashMap<String, HashMap<String, Long>>()
            tombDao.allOnce().filter { it.tbl != DeletedItem.TBL_PROJECTS }
                .forEach { tombs.getOrPut(it.tbl) { HashMap() }.put(it.itemId, it.deletedAt) }

            /** 移除被击败墓碑（本机DB + 内存映射同步清除） */
            suspend fun defeatTomb(tbl: String, id: String) {
                tombDao.deleteByRow(tbl, id)
                tombs[tbl]?.remove(id)
            }

            /** 移除被击败的项目墓碑（deleted_projects + 旧版 deleted_items 两条路径都清） */
            suspend fun defeatProj(pid: String) {
                deletedProjectDao.deleteByProjectId(pid)
                tombDao.deleteByRow(DeletedItem.TBL_PROJECTS, pid)
                projDead.remove(pid)
            }

            tombs.forEach { (tbl, m) ->
                val defeated = m.filter { (id, dA) ->
                    val latest = knownRows[tbl]?.get(id)
                    latest != null && !tombWins(favor, dA, latest)
                }.keys
                defeated.forEach { id -> defeatTomb(tbl, id) }
            }
            projDead.toMap().forEach { (pid, dA) ->
                val latest = knownRows[DeletedItem.TBL_PROJECTS]?.get(pid)
                if (latest != null && !tombWins(favor, dA, latest)) defeatProj(pid)
            }

            /** 该行是否仍被墓碑判死（调和后留存墓碑为真删除） */
            fun dead(tbl: String, id: String) = tombs[tbl]?.containsKey(id) == true
            fun deadProject(pid: String) = projDead.containsKey(pid)

            // 0.2 统一应用删除：经DAO删除（非裸SQL），外键级联与本机直接删除完全一致，各设备最终状态收敛。
            //     项目删除先于子表：经 projectDao 删除后 FK 级联清掉整棵树，本机原有子表墓碑留存无副作用
            var applied = 0
            projectDao.allOnce().filter { deadProject(it.id) }.let {
                if (it.isNotEmpty()) { projectDao.deleteAll(it); applied += it.size }
            }
            typeDao.allOnce().filter { dead(DeletedItem.TBL_TYPES, it.id) }.let {
                if (it.isNotEmpty()) { typeDao.deleteAll(it); applied += it.size }
            }
            candDao.allOnce().filter { dead(DeletedItem.TBL_CANDS, it.id) }.let {
                if (it.isNotEmpty()) { candDao.deleteAll(it); applied += it.size }
            }
            instanceDao.allOnce().filter { dead(DeletedItem.TBL_INSTANCES, it.id) }.let {
                if (it.isNotEmpty()) { instanceDao.deleteAll(it); applied += it.size }
            }
            logDao.allOnce().filter { dead(DeletedItem.TBL_LOGS, it.id) }.let {
                if (it.isNotEmpty()) { logDao.deleteAll(it); applied += it.size }
            }
            faultDao.allOnce().filter { dead(DeletedItem.TBL_FAULTS, it.id) }.let {
                if (it.isNotEmpty()) { faultDao.deleteAll(it); applied += it.size }
            }
            plannedDao.allOnce().filter { dead(DeletedItem.TBL_PLANNED, it.id) }.let {
                if (it.isNotEmpty()) { plannedDao.deleteAll(it); applied += it.size }
            }
            debuggerDao.allOnce().filter { dead(DeletedItem.TBL_DEBUGGERS, it.id) }.let {
                if (it.isNotEmpty()) { debuggerDao.deleteAll(it); applied += it.size }
            }
            r.appliedTombs = applied

            // ---------- 1) 数据合并：跳过已删id与父链已断的孤儿行，防借旧快照复活 ----------
            // 父表在前，保证外键引用顺序；UPDATE不触发级联，父行更新安全
            val lp = projectDao.allOnce().associateBy { it.id }
            val insP = pb.projects
                .filter { it.id !in lp && !deadProject(it.id) }
                // 旧版快照(≤11)无调试日期键 → 起始日期回填为创建日期，与数据库迁移口径一致
                .map { if (it.debugStartDate == 0L) it.copy(debugStartDate = it.createdAt) else it }
            val updP = pb.projects.filter { !deadProject(it.id) && lp[it.id]?.let { l -> remoteNewer(favor, it.updatedAt, l.updatedAt) } == true }
            projectDao.insertAll(insP); projectDao.updateAll(updP)
            r.newProjects = insP.size; r.updProjects = updP.size
            val aliveP = lp.keys + insP.map { it.id }

            val lt = typeDao.allOnce().associateBy { it.id }
            val insT = pb.types.filter { it.id !in lt && !dead(DeletedItem.TBL_TYPES, it.id) }
            val updT = pb.types.filter { !dead(DeletedItem.TBL_TYPES, it.id) && lt[it.id]?.let { l -> remoteNewer(favor, it.updatedAt, l.updatedAt) } == true }
            typeDao.insertAll(insT); typeDao.updateAll(updT)
            r.newTypes = insT.size; r.updTypes = updT.size
            val aliveT = lt.keys + insT.map { it.id }

            val lc = candDao.allOnce()
            val lcById = lc.associateBy { it.id }
            val lcPair = lc.map { it.typeId to it.content }.toHashSet()
            val insC = pb.cands.filter {
                it.typeId in aliveT && it.id !in lcById &&
                    (it.typeId to it.content) !in lcPair && !dead(DeletedItem.TBL_CANDS, it.id)
            }
            val updC = pb.cands.filter { !dead(DeletedItem.TBL_CANDS, it.id) && lcById[it.id]?.let { l -> remoteNewer(favor, it.updatedAt, l.updatedAt) } == true }
            candDao.insertAll(insC); candDao.insertAll(updC) // IGNORE策略：内容重复时静默跳过
            r.newCands = insC.size

            val li = instanceDao.allOnce().associateBy { it.id }
            val insI = pb.instances.filter {
                it.id !in li && it.projectId in aliveP && it.typeId in aliveT && !dead(DeletedItem.TBL_INSTANCES, it.id)
            }
            val updI = pb.instances.filter { !dead(DeletedItem.TBL_INSTANCES, it.id) && li[it.id]?.let { l -> remoteNewer(favor, it.updatedAt, l.updatedAt) } == true }
            instanceDao.insertAll(insI); instanceDao.updateAll(updI)
            r.newInstances = insI.size; r.updInstances = updI.size
            val aliveI = li.keys + insI.map { it.id }

            val ll = logDao.allOnce().associateBy { it.id }
            val insL = pb.logs.filter { it.id !in ll && it.instanceId in aliveI && !dead(DeletedItem.TBL_LOGS, it.id) }
            val updL = pb.logs.filter { !dead(DeletedItem.TBL_LOGS, it.id) && ll[it.id]?.let { l -> remoteNewer(favor, it.updatedAt, l.updatedAt) } == true }
            logDao.insertAll(insL); logDao.updateAll(updL)
            r.newLogs = insL.size; r.updLogs = updL.size
            val aliveL = ll.keys + insL.map { it.id }

            val lf = faultDao.allOnce().associateBy { it.id }
            val insF = pb.faults.filter {
                it.id !in lf && (it.logId.isBlank() || it.logId in aliveL) && !dead(DeletedItem.TBL_FAULTS, it.id)
            }
            val updF = pb.faults.filter { !dead(DeletedItem.TBL_FAULTS, it.id) && lf[it.id]?.let { l -> remoteNewer(favor, it.updatedAt, l.updatedAt) } == true }
            faultDao.upsertAll(insF); faultDao.updateAll(updF)
            r.newFaults = insF.size; r.updFaults = updF.size

            // 预选待测：同id新者胜；(柜子,内容) 相同但id不同视为同一项，IGNORE静默跳过
            val lpl = plannedDao.allOnce()
            val lplById = lpl.associateBy { it.id }
            val lplPair = lpl.map { it.instanceId to it.content }.toHashSet()
            val insPl = pb.planned.filter {
                it.instanceId in aliveI && it.id !in lplById &&
                    (it.instanceId to it.content) !in lplPair && !dead(DeletedItem.TBL_PLANNED, it.id)
            }
            val updPl = pb.planned.filter { !dead(DeletedItem.TBL_PLANNED, it.id) && lplById[it.id]?.let { l -> remoteNewer(favor, it.updatedAt, l.updatedAt) } == true }
            plannedDao.insertAll(insPl); plannedDao.updateAll(updPl)
            r.newPlanned = insPl.size; r.updPlanned = updPl.size

            // 调试员名单：同id新者胜；按name去重（不同id同名只保留先到者）；
            // 远端改名撞上本地已有姓名时跳过该行，避免唯一索引冲突
            val ld = debuggerDao.allOnce()
            val ldById = ld.associateBy { it.id }
            val ldNames = ld.map { it.name }.toHashSet()
            val insD = pb.debuggers.filter { it.id !in ldById && it.name !in ldNames && !dead(DeletedItem.TBL_DEBUGGERS, it.id) }
            val updD = pb.debuggers.filter { d ->
                if (dead(DeletedItem.TBL_DEBUGGERS, d.id)) return@filter false
                val local = ldById[d.id] ?: return@filter false
                if (!remoteNewer(favor, d.updatedAt, local.updatedAt)) return@filter false
                ld.none { it.id != d.id && it.name == d.name }
            }
            debuggerDao.insertAll(insD); debuggerDao.updateAll(updD)
            r.newDebuggers = insD.size; r.updDebuggers = updD.size
        }
        return r
    }

    /**
     * 从备份找回被删记录（v2.22）：把备份中"本机已缺失"的行作为权威插回（父表→子表顺序、孤儿跳过、
     * 内容去重冲突跳过），全部刷新 updatedAt=当前时刻——保证任何现存删除墓碑在合并时"新者胜"被击败、
     * 本机随后上传快照即可把找回的记录连同击败结果传播回全队。
     * apply=false 只扫描统计（找回面板预览用，不写库），结果口径与真实执行完全一致。
     * 输入为已按魔数解压过的备份文本（明文/gzip 均可经 WebDavSync.decodeSnapshot 转换）。
     */
    suspend fun rollbackFromBackup(text: String, apply: Boolean): RollbackResult {
        val rows = rollbackRowsOf(text)
        if (apply) {
            db.withTransaction {
                projectDao.insertAll(rows.insP)
                typeDao.insertAll(rows.insT)
                candDao.insertAll(rows.insC)
                instanceDao.insertAll(rows.insI)
                logDao.insertAll(rows.insL)
                faultDao.upsertAll(rows.insF)
                plannedDao.insertAll(rows.insPl)
                debuggerDao.insertAll(rows.insD)
                // 一并清掉本次找回行的墓碑：行时钟已刷新为"新者胜"，留存墓碑必然被击败，直接自清理
                rows.insP.forEach { tombDao.deleteByRow(DeletedItem.TBL_PROJECTS, it.id) }
                rows.insT.forEach { tombDao.deleteByRow(DeletedItem.TBL_TYPES, it.id) }
                rows.insC.forEach { tombDao.deleteByRow(DeletedItem.TBL_CANDS, it.id) }
                rows.insI.forEach { tombDao.deleteByRow(DeletedItem.TBL_INSTANCES, it.id) }
                rows.insL.forEach { tombDao.deleteByRow(DeletedItem.TBL_LOGS, it.id) }
                rows.insF.forEach { tombDao.deleteByRow(DeletedItem.TBL_FAULTS, it.id) }
                rows.insPl.forEach { tombDao.deleteByRow(DeletedItem.TBL_PLANNED, it.id) }
                rows.insD.forEach { tombDao.deleteByRow(DeletedItem.TBL_DEBUGGERS, it.id) }
            }
        }
        return rows.result()
    }

    /**
     * 找回预览（v2.23）：扫描备份中本机缺失的行 + 统计备份/本机日志构成。
     * 用于"提示无需找回"时让用户判断所选备份是否为丢失前的产物（备份里若无故障/消除日志，找回自然无果）。
     */
    suspend fun rollbackPreview(text: String): RollbackPreview {
        val rows = rollbackRowsOf(text)
        val pb = parseBackup(text)
        val localLogs = logDao.allOnce()
        val localFaults = faultDao.allOnce()
        fun byType(list: List<DebugLog>, type: Int) = list.count { it.logType == type }
        return RollbackPreview(
            missing = rows.result(),
            backupLogs = pb.logs.size,
            backupFaultLogs = byType(pb.logs, DebugLog.LOG_TYPE_FAULT),
            backupResolutionLogs = byType(pb.logs, DebugLog.LOG_TYPE_RESOLUTION),
            backupFaultRecords = pb.faults.size,
            localLogs = localLogs.size,
            localFaultLogs = byType(localLogs, DebugLog.LOG_TYPE_FAULT),
            localResolutionLogs = byType(localLogs, DebugLog.LOG_TYPE_RESOLUTION),
            localFaultRecords = localFaults.size
        )
    }

    /** 校验并解析备份，返回本机缺失的候选行（统一刷新 updatedAt=now()，保证"新者胜"压过历史墓碑） */
    private suspend fun rollbackRowsOf(text: String): RollbackRows {
        // 只支持 v2+（UUID主键）备份：v1 旧备份会被重映射为全新UUID，找回=整库重复，应走「恢复数据」
        val root = JSONObject(text)
        require(root.optString("app") == BACKUP_APP_TAG) { "不是本应用的备份文件" }
        require(root.optInt("schemaVersion", 1) >= 2) { "旧版(v1)备份请使用「恢复数据」，找回功能只支持 UUID 主键备份" }
        val pb = parseBackup(text)
        val t = now()

        // 本机现状（各表一次读取）
        val lp = projectDao.allOnce().associateBy { it.id }
        val lt = typeDao.allOnce().associateBy { it.id }
        val lc = candDao.allOnce()
        val lcById = lc.associateBy { it.id }
        val lcPair = lc.map { it.typeId to it.content }.toHashSet()
        val li = instanceDao.allOnce().associateBy { it.id }
        val ll = logDao.allOnce().associateBy { it.id }
        val lf = faultDao.allOnce().associateBy { it.id }
        val lpl = plannedDao.allOnce()
        val lplById = lpl.associateBy { it.id }
        val lplPair = lpl.map { it.instanceId to it.content }.toHashSet()
        val ld = debuggerDao.allOnce()
        val ldById = ld.associateBy { it.id }
        val ldNames = ld.map { it.name }.toHashSet()

        // 父表在前：凡备份有、本机无的行都算被删、权威插回（除非父链断 / 内容去重冲突）
        val insP = pb.projects.filter { it.id !in lp }.map { it.copy(updatedAt = t) }
        val insT = pb.types.filter { it.id !in lt }.map { it.copy(updatedAt = t) }
        val aliveP = lp.keys + insP.map { it.id }
        val aliveT = lt.keys + insT.map { it.id }
        val insC = pb.cands.filter {
            it.id !in lcById && it.typeId in aliveT && (it.typeId to it.content) !in lcPair
        }.map { it.copy(updatedAt = t) }
        val insI = pb.instances.filter { it.id !in li && it.projectId in aliveP && it.typeId in aliveT }
            .map { it.copy(updatedAt = t) }
        val aliveI = li.keys + insI.map { it.id }
        val insL = pb.logs.filter { it.id !in ll && it.instanceId in aliveI }.map { it.copy(updatedAt = t) }
        val aliveL = ll.keys + insL.map { it.id }
        val insF = pb.faults.filter { it.id !in lf && (it.logId.isBlank() || it.logId in aliveL) }
            .map { it.copy(updatedAt = t) }
        val insPl = pb.planned.filter {
            it.id !in lplById && it.instanceId in aliveI && (it.instanceId to it.content) !in lplPair
        }.map { it.copy(updatedAt = t) }
        val insD = pb.debuggers.filter { it.id !in ldById && it.name !in ldNames }.map { it.copy(updatedAt = t) }
        return RollbackRows(insP, insT, insC, insI, insL, insF, insPl, insD)
    }

    /**
     * 日志类型修复（v2.25）：按数据事实恢复被旧版本/旧格式快照合并冲掉的日志类型。
     * 1) 故障：被 ≥1 条故障记录（logId 指回）却仍为 logType=0 的日志 → 1(故障)；
     * 2) 消除：未被故障记录指向、备注=同一柜子同一测试内容下某条"已解决"故障的现象 → 2(消除)，
     *    恢复后时间线/故障列表即可显示解决方法。
     * 只改 logType 一个字段 + 刷新 updatedAt，下次同步以"新者胜"传播回全队；幂等。
     * preview=true 只统计不写库；应用时把 (id→原类型) 写入 applied 供 Frag 留存撤销。
     */
    suspend fun reclassifyLogTypes(preview: Boolean): ReclassifyResult {
        val logs = logDao.allOnce()
        val logById = logs.associateBy { it.id }
        val faults = faultDao.allOnce()
        val faultLogIds = faults.map { it.logId }.filter { it.isNotBlank() }.toHashSet()

        // 1. 故障日志：被 ≥1 条故障记录指向（老数据/被冲掉类型的故障日志都满足）
        val faultTargets = logs.filter { it.logType != DebugLog.LOG_TYPE_FAULT && it.id in faultLogIds }
        val faultTargetIds = faultTargets.map { it.id }.toHashSet()
        val attachedFaults = faults.count { it.logId in faultTargetIds }

        // 2. 消除日志：未挂故障记录、备注非空，且同柜同内容存在"已解决且现象==备注"的故障
        val resolvedFaults = faults.filter {
            it.status == FaultRecord.STATUS_RESOLVED &&
                it.logId.isNotBlank() && it.symptom.isNotBlank() && it.logId in logById
        }
        val resTargets = logs.filter { log ->
            log.logType != DebugLog.LOG_TYPE_RESOLUTION &&
                log.id !in faultTargetIds &&
                log.remark.isNotBlank() &&
                resolvedFaults.any { f ->
                    f.symptom == log.remark && logById[f.logId]?.let {
                        it.instanceId == log.instanceId && it.testContent == log.testContent
                    } == true
                }
        }

        val result = ReclassifyResult(
            faultLogs = faultTargets.size,
            resolutionLogs = resTargets.size,
            attachedFaults = attachedFaults
        )
        if (preview) return result
        val t = now()
        db.withTransaction {
            faultTargets.forEach {
                result.applied[it.id] = it.logType
                logDao.update(it.copy(logType = DebugLog.LOG_TYPE_FAULT, updatedAt = t))
            }
            resTargets.forEach {
                result.applied[it.id] = it.logType
                logDao.update(it.copy(logType = DebugLog.LOG_TYPE_RESOLUTION, updatedAt = t))
            }
        }
        return result
    }

    /**
     * 撤销类型修复：把修复工具留存过的 (日志id→原类型) 还原，并刷新时间戳同步回全队。
     * 幂等：当前类型已与原始一致的跳过。返回实际还原条数。
     */
    suspend fun undoLogTypeFix(applied: Map<String, Int>): Int {
        if (applied.isEmpty()) return 0
        val t = now()
        var n = 0
        db.withTransaction {
            applied.forEach { (id, orig) ->
                val cur = logDao.getByIdOnce(id) ?: return@forEach
                if (cur.logType != orig) {
                    logDao.update(cur.copy(logType = orig, updatedAt = t))
                    n++
                }
            }
        }
        return n
    }
}
