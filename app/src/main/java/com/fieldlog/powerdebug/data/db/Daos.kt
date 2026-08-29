package com.fieldlog.powerdebug.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY createdAt DESC")
    fun watchAllAsFlow(): Flow<List<Project>>

    @Query("SELECT * FROM projects ORDER BY createdAt DESC")
    suspend fun allOnce(): List<Project>

    @Query(
        """SELECT pr.*, 
        (SELECT COUNT(*) FROM instances i WHERE i.projectId = pr.id) AS cabinetCount,
        (SELECT COUNT(*) FROM debug_logs l INNER JOIN instances i2 ON l.instanceId = i2.id WHERE i2.projectId = pr.id) AS logCount,
        (SELECT COUNT(*) FROM planned_items pi INNER JOIN instances i3 ON pi.instanceId = i3.id
            WHERE i3.projectId = pr.id AND pi.enabled = 1 AND pi.result = 0) AS pendingTests,
        (SELECT COUNT(*) FROM planned_items pi5 INNER JOIN instances i5 ON pi5.instanceId = i5.id
            WHERE i5.projectId = pr.id AND pi5.enabled = 1 AND pi5.result = 2) AS failedTests,
        (SELECT COUNT(*) FROM fault_records f INNER JOIN debug_logs l4 ON f.logId = l4.id
            INNER JOIN instances i4 ON l4.instanceId = i4.id
            WHERE i4.projectId = pr.id AND f.status = 0) AS pendingFaults
        FROM projects pr ORDER BY pr.createdAt DESC"""
    )
    fun watchListItemsAsFlow(): Flow<List<ProjectListItem>>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun getByIdOnce(id: String): Project?

    @Insert
    suspend fun insert(p: Project)

    @Insert
    suspend fun insertAll(list: List<Project>)

    @Update
    suspend fun update(p: Project)

    @Update
    suspend fun updateAll(list: List<Project>)

    @Delete
    suspend fun delete(p: Project)

    @Delete
    suspend fun deleteAll(list: List<Project>)

    @Query("DELETE FROM projects")
    suspend fun wipe()

    @Query("SELECT COUNT(*) FROM projects")
    suspend fun count(): Int
}

@Dao
interface CabinetTypeDao {
    @Query("SELECT * FROM cabinet_types ORDER BY name")
    fun watchAllAsFlow(): Flow<List<CabinetType>>

    @Query("SELECT * FROM cabinet_types ORDER BY name")
    suspend fun allOnce(): List<CabinetType>

    @Query(
        """SELECT t.*,
        (SELECT COUNT(*) FROM instances i WHERE i.typeId = t.id) AS instanceCount,
        (SELECT COUNT(*) FROM candidate_items c WHERE c.typeId = t.id) AS itemCount
        FROM cabinet_types t ORDER BY t.name"""
    )
    fun watchListItemsAsFlow(): Flow<List<TypeListItem>>

    @Query("SELECT * FROM cabinet_types WHERE id = :id")
    suspend fun getByIdOnce(id: String): CabinetType?

    @Insert
    suspend fun insert(t: CabinetType)

    @Insert
    suspend fun insertAll(list: List<CabinetType>)

    @Update
    suspend fun update(t: CabinetType)

    @Update
    suspend fun updateAll(list: List<CabinetType>)

    @Delete
    suspend fun delete(t: CabinetType)

    @Delete
    suspend fun deleteAll(list: List<CabinetType>)

    @Query("DELETE FROM cabinet_types")
    suspend fun wipe()

    @Query("SELECT COUNT(*) FROM cabinet_types")
    suspend fun count(): Int
}

@Dao
interface CandidateItemDao {
    @Query("SELECT * FROM candidate_items WHERE typeId = :typeId ORDER BY createdAt, id")
    fun watchByTypeAsFlow(typeId: String): Flow<List<CandidateItem>>

    @Query("SELECT * FROM candidate_items WHERE typeId = :typeId ORDER BY createdAt, id")
    suspend fun byTypeOnce(typeId: String): List<CandidateItem>

    @Query("SELECT content FROM candidate_items WHERE typeId = :typeId")
    suspend fun contentsOnce(typeId: String): List<String>

    @Query("SELECT * FROM candidate_items ORDER BY createdAt, id")
    suspend fun allOnce(): List<CandidateItem>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(item: CandidateItem)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<CandidateItem>)

    @Delete
    suspend fun delete(item: CandidateItem)

    @Delete
    suspend fun deleteAll(items: List<CandidateItem>)

    @Query("DELETE FROM candidate_items")
    suspend fun wipe()

    /** 候选池使用频次：该类型全部柜子的预选清单中各内容出现次数 */
    @Query(
        """SELECT pi.content AS content, COUNT(*) AS cnt
        FROM planned_items pi INNER JOIN instances i ON pi.instanceId = i.id
        WHERE i.typeId = :typeId GROUP BY pi.content"""
    )
    suspend fun usageOfType(typeId: String): List<CandUsage>
}

@Dao
interface InstanceDao {
    @Query("SELECT * FROM instances WHERE projectId = :projectId ORDER BY name")
    fun watchByProjectAsFlow(projectId: String): Flow<List<CabinetInstance>>

    /** 项目详情页柜子行：附带实时待测/未通过/待处理故障数（与调试日志页同源） */
    @Query(
        """SELECT i.*,
        (SELECT COUNT(*) FROM planned_items pi WHERE pi.instanceId = i.id AND pi.enabled = 1 AND pi.result = 0) AS pendingTests,
        (SELECT COUNT(*) FROM planned_items pi2 WHERE pi2.instanceId = i.id AND pi2.enabled = 1 AND pi2.result = 2) AS failedTests,
        (SELECT COUNT(*) FROM fault_records f INNER JOIN debug_logs l ON f.logId = l.id
            WHERE l.instanceId = i.id AND f.status = 0) AS pendingFaults
        FROM instances i WHERE i.projectId = :projectId ORDER BY i.sortOrder, i.name"""
    )
    fun watchByProjectWithStatsAsFlow(projectId: String): Flow<List<InstanceStatusRow>>

    @Query("SELECT * FROM instances WHERE projectId = :projectId ORDER BY sortOrder, name")
    suspend fun byProjectOnce(projectId: String): List<CabinetInstance>

    @Query(
        "SELECT * FROM instances WHERE (:projectId = '' OR projectId = :projectId) " +
            "AND (:typeId = '' OR typeId = :typeId) ORDER BY name"
    )
    suspend fun byProjectAndTypeOnce(projectId: String, typeId: String): List<CabinetInstance>

    @Query(
        """SELECT i.*, p.name AS projectName FROM instances i 
        INNER JOIN projects p ON i.projectId = p.id 
        WHERE i.typeId = :typeId ORDER BY p.name COLLATE NOCASE, i.name"""
    )
    suspend fun byTypeWithProject(typeId: String): List<InstanceRow>

    @Query("SELECT * FROM instances ORDER BY name")
    suspend fun allOnce(): List<CabinetInstance>

    /** 全部柜子带项目名（跨柜拉取来源选择用） */
    @Query(
        """SELECT i.*, p.name AS projectName FROM instances i
        INNER JOIN projects p ON i.projectId = p.id
        ORDER BY p.name COLLATE NOCASE, i.name"""
    )
    suspend fun allWithProject(): List<InstanceRow>

    @Query("SELECT * FROM instances WHERE id = :id")
    suspend fun getByIdOnce(id: String): CabinetInstance?

    @Insert
    suspend fun insert(i: CabinetInstance)

    @Insert
    suspend fun insertAll(list: List<CabinetInstance>)

    @Update
    suspend fun update(i: CabinetInstance)

    @Update
    suspend fun updateAll(list: List<CabinetInstance>)

    /** 批量更新排序值 */
    @Query("UPDATE instances SET sortOrder = :sortOrder, updatedAt = :now WHERE id = :id")
    suspend fun updateSortOrder(id: String, sortOrder: Int, now: Long = System.currentTimeMillis())

    /** 更新行分组（0=未分组） */
    @Query("UPDATE instances SET rowGroup = :rowGroup, updatedAt = :now WHERE id = :id")
    suspend fun updateRowGroup(id: String, rowGroup: Int, now: Long = System.currentTimeMillis())

    @Delete
    suspend fun delete(i: CabinetInstance)

    @Delete
    suspend fun deleteAll(list: List<CabinetInstance>)

    @Query("DELETE FROM instances")
    suspend fun wipe()

    @Query("SELECT COUNT(*) FROM instances")
    suspend fun count(): Int
}

@Dao
interface DebugLogDao {
    @Query(
        """SELECT l.*, p.name AS projectName, t.name AS typeName, i.typeId AS typeId, i.name AS instanceName,
        i.deviceCode AS deviceCode, i.installer AS installer
        FROM debug_logs l
        INNER JOIN instances i ON l.instanceId = i.id
        INNER JOIN cabinet_types t ON i.typeId = t.id
        INNER JOIN projects p ON i.projectId = p.id
        WHERE (:projectId = '' OR i.projectId = :projectId)
          AND (:typeId = '' OR i.typeId = :typeId)
          AND (:instanceId = '' OR l.instanceId = :instanceId)
          AND (:status = -1
               OR (:status = 0 AND l.logType = 1)
               OR (:status = 1 AND l.logType = 2))
          AND (:circuit = '' OR l.circuit LIKE '%' || :circuit || '%')
          AND (:q = '' OR l.testContent LIKE '%' || :q || '%'
               OR IFNULL(l.remark, '') LIKE '%' || :q || '%'
               OR IFNULL(l.tester, '') LIKE '%' || :q || '%'
               OR i.name LIKE '%' || :q || '%'
               OR p.name LIKE '%' || :q || '%')
        ORDER BY l.createdAt DESC"""
    )
    suspend fun search(
        projectId: String, typeId: String, instanceId: String,
        status: Int, circuit: String, q: String
    ): List<LogListItem>

    @Query(
        """SELECT l.*, p.name AS projectName, t.name AS typeName, i.typeId AS typeId, i.name AS instanceName,
        i.deviceCode AS deviceCode, i.installer AS installer
        FROM debug_logs l
        INNER JOIN instances i ON l.instanceId = i.id
        INNER JOIN cabinet_types t ON i.typeId = t.id
        INNER JOIN projects p ON i.projectId = p.id
        WHERE l.id = :id LIMIT 1"""
    )
    suspend fun getDetailOnce(id: String): LogListItem?

    @Query(
        """SELECT l.*, p.name AS projectName, t.name AS typeName, i.typeId AS typeId, i.name AS instanceName,
        i.deviceCode AS deviceCode, i.installer AS installer
        FROM debug_logs l
        INNER JOIN instances i ON l.instanceId = i.id
        INNER JOIN cabinet_types t ON i.typeId = t.id
        INNER JOIN projects p ON i.projectId = p.id
        ORDER BY p.name COLLATE NOCASE, i.name COLLATE NOCASE, l.createdAt"""
    )
    suspend fun exportAll(): List<LogListItem>

    @Query(
        """SELECT DISTINCT l.circuit FROM debug_logs l
        INNER JOIN instances i ON l.instanceId = i.id
        WHERE IFNULL(l.circuit, '') <> ''
          AND (:typeId = '' OR i.typeId = :typeId)
          AND (:projectId = '' OR i.projectId = :projectId)
        ORDER BY l.circuit LIMIT 40"""
    )
    suspend fun distinctCircuits(projectId: String, typeId: String): List<String>

    @Query("SELECT * FROM debug_logs ORDER BY createdAt, id")
    suspend fun allOnce(): List<DebugLog>

    @Query("SELECT * FROM debug_logs WHERE id = :id")
    suspend fun getByIdOnce(id: String): DebugLog?

    @Query("SELECT COUNT(*) FROM debug_logs WHERE instanceId = :instanceId")
    suspend fun countLogsOf(instanceId: String): Int

    @Query(
        """SELECT l.*, p.name AS projectName, t.name AS typeName, i.typeId AS typeId, i.name AS instanceName,
        i.deviceCode AS deviceCode, i.installer AS installer
        FROM debug_logs l
        INNER JOIN instances i ON l.instanceId = i.id
        INNER JOIN cabinet_types t ON i.typeId = t.id
        INNER JOIN projects p ON i.projectId = p.id
        WHERE l.instanceId = :instanceId
        ORDER BY l.createdAt"""
    )
    suspend fun byInstanceOnce(instanceId: String): List<LogListItem>

    @Insert
    suspend fun insert(l: DebugLog)

    @Insert
    suspend fun insertAll(list: List<DebugLog>)

    @Update
    suspend fun update(l: DebugLog)

    @Update
    suspend fun updateAll(list: List<DebugLog>)

    @Delete
    suspend fun delete(l: DebugLog)

    @Delete
    suspend fun deleteAll(list: List<DebugLog>)

    @Query("DELETE FROM debug_logs")
    suspend fun wipe()

    @Query("SELECT COUNT(*) FROM debug_logs")
    suspend fun count(): Int

    /** 查某故障的消除日志（logType=2，remark匹配symptom） */
    @Query(
        """SELECT l.* FROM debug_logs l
        WHERE l.instanceId = :instanceId AND l.testContent = :content AND l.logType = 2
          AND l.remark = :symptom
        ORDER BY l.createdAt DESC LIMIT 1"""
    )
    suspend fun resolutionLogOf(instanceId: String, content: String, symptom: String): DebugLog?

    /** 查某柜某测试项的所有日志（按时间排序，用于时间线） */
    @Query(
        """SELECT l.* FROM debug_logs l
        WHERE l.instanceId = :instanceId AND l.testContent = :content
        ORDER BY l.createdAt"""
    )
    suspend fun byInstanceAndContentOnce(instanceId: String, content: String): List<DebugLog>
}

@Dao
interface FaultRecordDao {
    @Query("SELECT * FROM fault_records WHERE logId = :logId ORDER BY occurredAt")
    suspend fun forLogOnce(logId: String): List<FaultRecord>

    @Query("SELECT * FROM fault_records WHERE id IN (:ids)")
    suspend fun byIdsOnce(ids: List<String>): List<FaultRecord>

    @Query(
        """SELECT f.*, p.name AS projectName, i.name AS instanceName, i.deviceCode AS deviceCode
        FROM fault_records f
        INNER JOIN debug_logs l ON f.logId = l.id
        INNER JOIN instances i ON l.instanceId = i.id
        INNER JOIN projects p ON i.projectId = p.id
        ORDER BY p.name COLLATE NOCASE, i.name COLLATE NOCASE, f.occurredAt"""
    )
    suspend fun exportAll(): List<FaultExportRow>

    @Query("SELECT * FROM fault_records ORDER BY occurredAt, id")
    suspend fun allOnce(): List<FaultRecord>

    @Insert
    suspend fun insert(f: FaultRecord)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(list: List<FaultRecord>)

    @Update
    suspend fun updateAll(list: List<FaultRecord>)

    @Delete
    suspend fun delete(f: FaultRecord)

    @Delete
    suspend fun deleteAll(list: List<FaultRecord>)

    @Query("DELETE FROM fault_records WHERE logId = :logId")
    suspend fun deleteForLog(logId: String)

    /** 批量标记解决（复测通过时自动销掉关联的未解决故障） */
    @Query(
        "UPDATE fault_records SET status = 1, resolvedAt = :t, updatedAt = :t " +
            "WHERE id IN (:ids) AND status = 0"
    )
    suspend fun resolveByIds(ids: List<String>, t: Long)

    /** 单条标记通过 */
    @Query("UPDATE fault_records SET status = 1, resolvedAt = :t, updatedAt = :t WHERE id = :id AND status = 0")
    suspend fun passSingle(id: String, t: Long = System.currentTimeMillis())

    /** 单条驳回（恢复为待处理） */
    @Query("UPDATE fault_records SET status = 0, resolvedAt = 0, updatedAt = :t WHERE id = :id AND status = 1")
    suspend fun unpassSingle(id: String, t: Long = System.currentTimeMillis())

    /** 写入/更新解决方法（故障通过弹窗/时间线编辑共用） */
    @Query("UPDATE fault_records SET solution = :solution, updatedAt = :t WHERE id = :id")
    suspend fun setSolution(id: String, solution: String, t: Long = System.currentTimeMillis())

    /** 查某柜某测试项名称关联的故障记录（通过log.testContent匹配 + faultId直接查询） */
    @Query(
        """SELECT f.* FROM fault_records f
        INNER JOIN debug_logs l ON f.logId = l.id
        WHERE l.instanceId = :instanceId AND l.testContent = :content AND l.logType = 1
        ORDER BY f.occurredAt"""
    )
    suspend fun byInstanceAndContentOnce(instanceId: String, content: String): List<FaultRecord>

    /** 查某柜某测试项的未解决故障（用于删除日志后检测是否需要驳回重测） */
    @Query(
        """SELECT f.* FROM fault_records f
        INNER JOIN debug_logs l ON f.logId = l.id
        WHERE l.instanceId = :instanceId AND l.testContent = :content AND l.logType = 1
          AND f.status = 0
        ORDER BY f.occurredAt"""
    )
    suspend fun pendingByInstanceAndContent(instanceId: String, content: String): List<FaultRecord>

    /** 查某柜关联的故障记录（通过PlannedItem.faultId直接查询，用于消除日志删除后恢复场景） */
    @Query("SELECT * FROM fault_records WHERE id IN (:faultIds) ORDER BY occurredAt")
    suspend fun byFaultIdsOnce(faultIds: List<String>): List<FaultRecord>

    @Query("SELECT COUNT(*) FROM fault_records WHERE status = 0")
    suspend fun countPending(): Int

    @Query("DELETE FROM fault_records")
    suspend fun wipe()
}

@Dao
interface PlannedItemDao {
    /** 管理页：未完成(未测+未通过)在前，未通过的排最前 */
    @Query(
        """SELECT * FROM planned_items WHERE instanceId = :instanceId 
        ORDER BY CASE WHEN result = 1 THEN 1 ELSE 0 END, result DESC, createdAt, id"""
    )
    fun watchByInstanceAsFlow(instanceId: String): Flow<List<PlannedItem>>

    @Query("SELECT content FROM planned_items WHERE instanceId = :instanceId")
    suspend fun contentsOnce(instanceId: String): List<String>

    @Query("SELECT * FROM planned_items WHERE id IN (:ids)")
    suspend fun byIdsOnce(ids: List<String>): List<PlannedItem>

    @Query("SELECT * FROM planned_items WHERE instanceId = :instanceId AND content = :content")
    suspend fun byInstanceAndContentOnce(instanceId: String, content: String): List<PlannedItem>

    @Query("SELECT * FROM planned_items WHERE id = :id LIMIT 1")
    suspend fun getByIdOnce(id: String): PlannedItem?

    /** 开始测试清单：启用且尚未通过(含上次未通过，供复测) */
    @Query(
        """SELECT * FROM planned_items WHERE instanceId = :instanceId AND enabled = 1 AND result <> 1 
        ORDER BY result DESC, createdAt, id"""
    )
    suspend fun pendingForTestOnce(instanceId: String): List<PlannedItem>

    @Query("SELECT * FROM planned_items WHERE instanceId = :instanceId ORDER BY result, createdAt, id")
    suspend fun allOfInstanceOnce(instanceId: String): List<PlannedItem>

    @Query("SELECT * FROM planned_items WHERE logId = :logId")
    suspend fun forLogOnce(logId: String): List<PlannedItem>

    @Query("SELECT * FROM planned_items ORDER BY updatedAt, id")
    suspend fun allOnce(): List<PlannedItem>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(p: PlannedItem)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(list: List<PlannedItem>)

    @Update
    suspend fun update(p: PlannedItem)

    @Update
    suspend fun updateAll(list: List<PlannedItem>)

    @Delete
    suspend fun delete(p: PlannedItem)

    /** 批量记录测试结果并挂到生成它的日志上（未通过时逐项携带faultId） */
    @Query(
        "UPDATE planned_items SET result = :result, doneAt = :at, logId = :logId, " +
            "faultId = :faultId, updatedAt = :at WHERE id IN (:ids)"
    )
    suspend fun setResult(ids: List<String>, result: Int, at: Long, logId: String, faultId: String)

    /** 删除日志时选择"重测"：恢复为未测 */
    @Query("UPDATE planned_items SET result = 0, doneAt = 0, logId = '', faultId = '', updatedAt = :at WHERE logId = :logId")
    suspend fun resetForLog(logId: String, at: Long)

    /** 按柜子实例+测试项名称恢复为未测（用于故障/消除日志删除） */
    @Query("UPDATE planned_items SET result = 0, doneAt = 0, logId = '', faultId = '', updatedAt = :at WHERE instanceId = :instanceId AND content = :content")
    suspend fun resetByInstanceAndContent(instanceId: String, content: String, at: Long)

    /** 删除日志时选择"连项删除"：这些预选项可能是误添加的 */
    @Query("DELETE FROM planned_items WHERE logId = :logId")
    suspend fun deleteForLog(logId: String)

    /** 跨柜拉取覆盖：清空本柜全部预选（调用方负责先记墓碑） */
    @Query("DELETE FROM planned_items WHERE instanceId = :instanceId")
    suspend fun deleteForInstance(instanceId: String)

    @Delete
    suspend fun deleteAll(list: List<PlannedItem>)

    @Query("DELETE FROM planned_items")
    suspend fun wipe()
}

@Dao
interface TesterAccountDao {
    @Query("SELECT * FROM tester_accounts ORDER BY createdAt")
    suspend fun allOnce(): List<TesterAccount>

    @Query("SELECT * FROM tester_accounts WHERE username = :username LIMIT 1")
    suspend fun byUsername(username: String): TesterAccount?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(a: TesterAccount)

    @Query("UPDATE tester_accounts SET source = :source WHERE username = :username")
    suspend fun updateSource(username: String, source: String)
}

@Dao
interface DebuggerDao {
    @Query("SELECT * FROM debuggers ORDER BY createdAt")
    suspend fun allOnce(): List<Debugger>

    @Query("SELECT * FROM debuggers WHERE id = :id LIMIT 1")
    suspend fun byIdOnce(id: String): Debugger?

    @Query("SELECT * FROM debuggers WHERE name = :name LIMIT 1")
    suspend fun byNameOnce(name: String): Debugger?

    /** IGNORE策略：重名静默跳过，唯一性由唯一索引兜底 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(d: Debugger)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(list: List<Debugger>)

    @Update
    suspend fun updateAll(list: List<Debugger>)

    @Delete
    suspend fun delete(d: Debugger)

    @Delete
    suspend fun deleteAll(list: List<Debugger>)

    @Query("DELETE FROM debuggers")
    suspend fun wipe()
}

/** 删除墓碑表：只增、被击败时按(表,记录id)删除；合并时先落库→新者胜调和→按表应用删除（经DAO删除以触发一致的级联） */
@Dao
interface DeletedItemDao {
    @Query("SELECT * FROM deleted_items ORDER BY deletedAt")
    suspend fun allOnce(): List<DeletedItem>

    /** IGNORE：同(表,记录id)重复墓碑静默跳过，保留首条（最早删除时刻） */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(item: DeletedItem)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<DeletedItem>)

    /** 墓碑被击败（删除时刻早于该行最后更新，新者胜）时移除，防旧删除误清他机较新数据 */
    @Query("DELETE FROM deleted_items WHERE tbl = :tbl AND itemId = :itemId")
    suspend fun deleteByRow(tbl: String, itemId: String)

    @Query("DELETE FROM deleted_items")
    suspend fun wipe()
}

/** 项目级删除墓碑（v11）：全局区传播项目删除，只增、被击败时按项目id删除；合并先落库→新者胜调和→经DAO删除项目触发级联 */
@Dao
interface DeletedProjectDao {
    @Query("SELECT * FROM deleted_projects ORDER BY deletedAt")
    suspend fun allOnce(): List<DeletedProject>

    /** IGNORE：同 projectId 重复墓碑静默跳过，保留首条（最早删除时刻） */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(item: DeletedProject)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<DeletedProject>)

    /** 墓碑被击败（删除早于项目最新更新）或找回复活时移除 */
    @Query("DELETE FROM deleted_projects WHERE projectId = :projectId")
    suspend fun deleteByProjectId(projectId: String)

    @Query("DELETE FROM deleted_projects")
    suspend fun wipe()
}
