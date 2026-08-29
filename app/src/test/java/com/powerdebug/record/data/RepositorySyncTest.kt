package com.powerdebug.record.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.powerdebug.record.data.db.AppDatabase
import com.powerdebug.record.data.db.DeletedItem
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 核心合并语义回归测试（规格第10章：CI 必跑；重点 = applyMerge 墓碑"新者胜"与项目级墓碑 v11）。
 * RM 在 JVM 上用内存 Room 直接驱动 Repository（无 UI 依赖），覆盖：
 *  - 墓碑"新者胜"：晚于行更新→成立删除；早于行更新→被击败自清理、行存活
 *  - deleted_projects（v11）项目级墓碑同样"新者胜"，删除触发外键级联
 *  - hasMergeConflict 冲突窗口判断（7.7）
 *  - projectClocks（项目版本时钟，7.6）与 global/project 快照可独立解析合并
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RepositorySyncTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: Repository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        repo = Repository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ---------- JSON 快照构造（字段布局与 Repository.backupJson 一致） ----------

    private fun row(vararg kv: Pair<String, Any>): JSONObject =
        JSONObject().apply { kv.forEach { (k, v) -> put(k, v) } }

    private fun snapshot(
        projects: List<JSONObject> = emptyList(),
        types: List<JSONObject> = emptyList(),
        cands: List<JSONObject> = emptyList(),
        instances: List<JSONObject> = emptyList(),
        logs: List<JSONObject> = emptyList(),
        faults: List<JSONObject> = emptyList(),
        planned: List<JSONObject> = emptyList(),
        debuggers: List<JSONObject> = emptyList(),
        tombs: List<JSONObject> = emptyList(),
        projTombs: List<JSONObject> = emptyList()
    ): String {
        val jo = JSONObject()
            .put("app", Repository.BACKUP_APP_TAG)
            .put("schemaVersion", Repository.BACKUP_SCHEMA)
            .put("readyKind", "full")
            .put("exportedAt", 0L)
            .put("deviceTimeZoneOffset", 0)
        fun put(key: String, list: List<JSONObject>) =
            jo.put(key, JSONArray().apply { list.forEach { put(it) } })
        put("projects", projects); put("cabinetTypes", types); put("candidateItems", cands)
        put("instances", instances); put("logs", logs); put("faults", faults)
        put("plannedItems", planned); put("debuggers", debuggers)
        put("deletedItems", tombs); put("deletedProjects", projTombs)
        return jo.toString(2)
    }

    private fun project(id: String, updatedAt: Long) =
        row("id" to id, "name" to "项目$id", "code" to "", "remark" to "",
            "createdAt" to updatedAt, "updatedAt" to updatedAt)

    private fun type(id: String, updatedAt: Long) =
        row("id" to id, "name" to "类型$id", "remark" to "",
            "createdAt" to updatedAt, "updatedAt" to updatedAt)

    private fun instance(id: String, projectId: String, typeId: String, updatedAt: Long) =
        row("id" to id, "projectId" to projectId, "typeId" to typeId, "name" to "柜$id",
            "deviceCode" to "", "location" to "", "installer" to "", "shortName" to "",
            "sortOrder" to 0, "rowGroup" to 0, "createdAt" to updatedAt, "updatedAt" to updatedAt)

    private fun log(id: String, instanceId: String, updatedAt: Long) =
        row("id" to id, "instanceId" to instanceId, "circuit" to "", "testContent" to "L1送电",
            "tester" to "", "remark" to "", "createdBy" to "u", "updatedBy" to "u",
            "createdAt" to updatedAt, "updatedAt" to updatedAt)

    private fun tomb(tbl: String, itemId: String, deletedAt: Long) =
        row("id" to "tomb-$tbl-$itemId", "tbl" to tbl, "itemId" to itemId, "deletedAt" to deletedAt)

    private fun projTomb(pid: String, deletedAt: Long) =
        row("id" to "dp-$pid", "projectId" to pid, "deletedAt" to deletedAt)

    private val t0 = 1_700_000_000_000L

    /** 基础项目快照：1项目 + 1类型 + 1柜 + 1日志 */
    private fun baseSnapshot(logUpdatedAt: Long, tombDeletedAt: Long? = null): String =
        snapshot(
            projects = listOf(project("p1", t0)),
            types = listOf(type("t1", t0)),
            instances = listOf(instance("i1", "p1", "t1", t0)),
            logs = listOf(log("l1", "i1", logUpdatedAt)),
            tombs = tombDeletedAt?.let { listOf(tomb(DeletedItem.TBL_LOGS, "l1", it)) } ?: emptyList()
        )

    // ---------- 墓碑"新者胜"（7.4） ----------

    @Test
    fun tombstone_newer_than_row_applies_deletion() = runTest {
        repo.mergeJson(baseSnapshot(t0))               // 本机 l1 updatedAt=t0
        assertEquals(1, db.debugLogDao().allOnce().size)

        // 快照携带 l1 更新到 t0+10min，墓碑却更晚（t0+11min）→ 删除成立（级联）
        val r = repo.mergeJson(baseSnapshot(t0 + 600_000L, tombDeletedAt = t0 + 660_000L))
        assertEquals(0, db.debugLogDao().allOnce().size)
        assertTrue(r.appliedTombs > 0)
        // 墓碑留存（防"删了又长回来"）
        assertEquals(1, db.deletedItemDao().allOnce().size)
    }

    @Test
    fun tombstone_older_than_row_is_defeated_row_survives() = runTest {
        repo.mergeJson(baseSnapshot(t0))
        // 墓碑早于该行最新更新（t0+2min < t0+10min）→ 被击败自清理，l1 保留并更新
        val r = repo.mergeJson(baseSnapshot(t0 + 600_000L, tombDeletedAt = t0 + 120_000L))
        val logs = db.debugLogDao().allOnce()
        assertEquals(1, logs.size)
        assertEquals(t0 + 600_000L, logs[0].updatedAt)
        assertEquals(0, r.appliedTombs)
        // 被击败墓碑已自清理
        assertEquals(0, db.deletedItemDao().allOnce().size)
    }

    // ---------- 项目级删除墓碑 deleted_projects（v11 / schemaVersion=10） ----------

    @Test
    fun project_tombstone_newer_kills_project_and_cascade() = runTest {
        repo.mergeJson(baseSnapshot(t0))
        assertEquals(1, db.instanceDao().allOnce().size)

        val r = repo.mergeJson(
            snapshot(
                projects = listOf(project("p1", t0)),
                types = listOf(type("t1", t0)),
                instances = listOf(instance("i1", "p1", "t1", t0)),
                logs = listOf(log("l1", "i1", t0)),
                projTombs = listOf(projTomb("p1", t0 + 660_000L))
            )
        )
        assertEquals(0, db.projectDao().allOnce().size)
        // 外键级联：柜子与日志一并被删
        assertEquals(0, db.instanceDao().allOnce().size)
        assertEquals(0, db.debugLogDao().allOnce().size)
        assertTrue(r.appliedTombs >= 1)
        // （旧版 deleted_items(tbl=projects) 解析已等价并入 deletedProjects）
        assertEquals(1, db.deletedProjectDao().allOnce().size)
    }

    @Test
    fun project_tombstone_older_is_defeated_project_survives() = runTest {
        repo.mergeJson(baseSnapshot(t0))
        val r = repo.mergeJson(
            snapshot(
                projects = listOf(project("p1", t0 + 600_000L)),
                types = listOf(type("t1", t0)),
                instances = listOf(instance("i1", "p1", "t1", t0)),
                logs = listOf(log("l1", "i1", t0)),
                projTombs = listOf(projTomb("p1", t0 + 120_000L))
            )
        )
        assertEquals(1, db.projectDao().allOnce().size)
        assertEquals(t0 + 600_000L, db.projectDao().allOnce()[0].updatedAt)
        assertEquals(1, db.instanceDao().allOnce().size)
        assertEquals(0, r.appliedTombs)
        assertEquals(0, db.deletedProjectDao().allOnce().size)
    }

    @Test
    fun legacy_deleted_items_project_tombstone_merges_into_deleted_projects() = runTest {
        // 旧格式快照 deletedItems 里 tbl=projects 的墓碑解析后应等价进入 deletedProjects
        repo.mergeJson(baseSnapshot(t0))
        val s = snapshot(
            projects = listOf(project("p1", t0)),
            types = listOf(type("t1", t0)),
            instances = listOf(instance("i1", "p1", "t1", t0)),
            logs = listOf(log("l1", "i1", t0)),
            tombs = listOf(tomb(DeletedItem.TBL_PROJECTS, "p1", t0 + 660_000L))
        )
        val r = repo.mergeJson(s)
        assertEquals(0, db.projectDao().allOnce().size)
        assertTrue(r.appliedTombs >= 1)
    }

    // ---------- 冲突窗口（7.7）与项目版本时钟（7.6） ----------

    @Test
    fun hasMergeConflict_detects_ambiguous_window() = runTest {
        repo.mergeJson(baseSnapshot(t0))

        // 同一分钟内各有更新（差异=1min < 5min 窗口）→ 冲突
        val sConf = baseSnapshot(t0 + 60_000L)
        assertTrue("窗口内时间差应判定为歧义冲突", repo.hasMergeConflict(sConf))

        // 明确更新（差异=20min > 窗口）→ 非冲突
        val sClear = baseSnapshot(t0 + 20L * 60_000L)
        assertFalse("窗口外不应判定为冲突", repo.hasMergeConflict(sClear))
    }

    @Test
    fun projectClocks_is_max_updated_at_per_project() = runTest {
        repo.mergeJson(
            snapshot(
                projects = listOf(project("p1", t0)),
                types = listOf(type("t1", t0)),
                instances = listOf(instance("i1", "p1", "t1", t0 + 60_000L)),
                logs = listOf(log("l1", "i1", t0 + 300_000L))
            )
        )
        val clocks = repo.projectClocks()
        assertEquals(mapOf("p1" to t0 + 300_000L), clocks)
    }

    @Test
    fun global_and_project_snapshots_roundtrip_into_fresh_db() = runTest {
        repo.mergeJson(baseSnapshot(t0))
        val global = repo.globalSnapshot()
        val project = repo.projectSnapshot("p1")
        val jg = JSONObject(global)
        assertEquals("global", jg.getString("readyKind"))
        val jp = JSONObject(project)
        assertEquals("project", jp.getString("readyKind"))
        // 项目快照只含本项目：无类型/候选池/墓碑
        assertEquals(0, jp.optJSONArray("cabinetTypes")?.length() ?: 0)

        // 全新的库：先合全局（类型等），再合项目文件（柜/日志）→ 不丢孤儿
        val fresh = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val freshRepo = Repository(fresh)
            val rg = freshRepo.mergeJson(global)
            assertEquals(1, rg.newTypes)
            val rp = freshRepo.mergeJson(project)
            assertEquals(1, rp.newProjects)
            assertEquals(1, rp.newInstances)
            assertEquals(1, rp.newLogs)
            assertEquals(1, fresh.debugLogDao().allOnce().size)
        } finally {
            fresh.close()
        }
        // 本机库依然完好
        assertEquals(1, db.debugLogDao().allOnce().size)
    }
}