package com.fieldlog.powerdebug.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * v2 起全部表主键为客户端生成的 UUID 字符串：
 * 多台设备离线各自新增数据后可通过 WebDAV 智能合并，不会撞主键。
 * 每表带 updatedAt 作为合并时钟：同 id 冲突时新者胜。
 */

/** 工程/项目：调试任务的根单位，一个项目包含一台或多台柜子 */
@Entity(tableName = "projects")
data class Project(
    @PrimaryKey val id: String = "",
    val name: String,
    val code: String = "",
    val remark: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/** 柜子类型模板：绑定预选测试项候选池，与具体柜子名字无关 */
@Entity(tableName = "cabinet_types")
data class CabinetType(
    @PrimaryKey val id: String = "",
    val name: String,
    val remark: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/** 预选测试项候选池条目，属于某个柜子类型 */
@Entity(
    tableName = "candidate_items",
    indices = [
        Index("typeId"),
        Index(value = ["typeId", "content"], unique = true)
    ]
)
data class CandidateItem(
    @PrimaryKey val id: String = "",
    val typeId: String,
    val content: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/** 柜子实例：现场的实设备，隶属一个项目，引用一个柜子类型 */
@Entity(
    tableName = "instances",
    foreignKeys = [
        ForeignKey(
            entity = Project::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = CabinetType::class,
            parentColumns = ["id"],
            childColumns = ["typeId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("projectId"), Index("typeId")]
)
data class CabinetInstance(
    @PrimaryKey val id: String = "",
    val projectId: String,
    val typeId: String,
    val name: String,
    val deviceCode: String = "",
    val location: String = "",
    val installer: String = "",
    val shortName: String = "",
    val sortOrder: Int = 0,
    val rowGroup: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * 调试日志；circuit 为空表示整柜测试。
 * createdBy/updatedBy 记录操作账号（未登录为空串），随 WebDAV 同步到其他设备。
 */
@Entity(
    tableName = "debug_logs",
    foreignKeys = [
        ForeignKey(
            entity = CabinetInstance::class,
            parentColumns = ["id"],
            childColumns = ["instanceId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("instanceId"), Index("createdAt")]
)
data class DebugLog(
    @PrimaryKey val id: String = "",
    val instanceId: String,
    val circuit: String = "",
    val testContent: String,
    /** 0=通过日志 1=故障日志 2=消除日志 */
    @ColumnInfo(defaultValue = "0") val logType: Int = LOG_TYPE_PASS,
    val tester: String = "",
    /** 故障原因/消除原因（故障日志和消除日志时有意义） */
    val remark: String = "",
    val createdBy: String = "",
    val updatedBy: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val LOG_TYPE_PASS = 0
        const val LOG_TYPE_FAULT = 1
        const val LOG_TYPE_RESOLUTION = 2
    }
}

/** 故障记录：status 0=待处理 1=已解决；resolvedAt=0 表示未解决 */
@Entity(
    tableName = "fault_records",
    foreignKeys = [
        ForeignKey(
            entity = DebugLog::class,
            parentColumns = ["id"],
            childColumns = ["logId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("logId")]
)
data class FaultRecord(
    @PrimaryKey val id: String = "",
    var logId: String = "",
    var circuit: String = "",
    var symptom: String = "",
    var solution: String = "",
    var occurredAt: Long = System.currentTimeMillis(),
    var resolvedAt: Long = 0,
    var status: Int = STATUS_PENDING,
    var updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val STATUS_PENDING = 0
        const val STATUS_RESOLVED = 1
    }
}

/**
 * 柜子实例的预选待测清单：建实例时自动从所属类型的候选池复制初始清单。
 * enabled=false 为临时停用；result 三态：0未测 / 1通过 / 2未通过；
 * doneAt=最近一次测试时间，logId=完成它的日志，
 * result=2 时 faultId 指向该次未通过产生的故障记录（原因）。
 */
@Entity(
    tableName = "planned_items",
    foreignKeys = [
        ForeignKey(
            entity = CabinetInstance::class,
            parentColumns = ["id"],
            childColumns = ["instanceId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("instanceId"),
        Index(value = ["instanceId", "content"], unique = true)
    ]
)
data class PlannedItem(
    @PrimaryKey val id: String = "",
    val instanceId: String,
    val content: String,
    val enabled: Boolean = true,
    /** 最近一次测试时间；0=从未测过 */
    val doneAt: Long = 0,
    val logId: String = "",
    /** 0=未测 1=通过 2=未通过 */
    @ColumnInfo(defaultValue = "0") val result: Int = RESULT_UNTESTED,
    /** 未通过时关联的故障记录id */
    @ColumnInfo(defaultValue = "") val faultId: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val RESULT_UNTESTED = 0
        const val RESULT_PASS = 1
        const val RESULT_FAIL = 2
    }
}

/** 测试员账号：登录 WebDAV 验证通过或超级密码注册后沉淀在本机，用于切换身份 */
@Entity(
    tableName = "tester_accounts",
    indices = [Index(value = ["username"], unique = true)]
)
data class TesterAccount(
    @PrimaryKey val id: String = "",
    val username: String,
    /** webdav = 通过服务器验证；super = 超级密码直接注册 */
    val source: String = SOURCE_WEBDAV,
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val SOURCE_WEBDAV = "webdav"
        const val SOURCE_SUPER = "super"
    }
}

/**
 * 删除墓碑（v6新增）：记录「某表的某行已在何时被删除」，随备份/WebDAV同步到全队，
 * 使删除操作也能跨设备传播——否则这边删了、那边一合并又长回来。
 * 只记显式删除入口；级联删除靠外键在各设备本机一致触发，不逐条记墓碑。
 */
@Entity(
    tableName = "deleted_items",
    indices = [Index(value = ["tbl", "itemId"], unique = true)]
)
data class DeletedItem(
    @PrimaryKey val id: String = "",
    /** 被删除记录所在表名（TBL_* 常量） */
    val tbl: String,
    /** 被删除记录的原UUID */
    val itemId: String,
    val deletedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val TBL_PROJECTS = "projects"
        const val TBL_TYPES = "cabinet_types"
        const val TBL_CANDS = "candidate_items"
        const val TBL_INSTANCES = "instances"
        const val TBL_LOGS = "debug_logs"
        const val TBL_FAULTS = "fault_records"
        const val TBL_PLANNED = "planned_items"
        const val TBL_DEBUGGERS = "debuggers"
    }
}

/**
 * 调试员名单：与登录账号无关的现场调试人员名册，由超级口令统一维护（增/改/删均需验证）。
 * 日志「测试人员」从名单中选取，默认带出最近一次使用的姓名；随备份/WebDAV同步到全队。
 */
@Entity(
    tableName = "debuggers",
    indices = [Index(value = ["name"], unique = true)]
)
data class Debugger(
    @PrimaryKey val id: String = "",
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * 项目级删除墓碑（v11新增）：记录「某项目已在何时被删除」，走云端 global 区传播到全队。
 * 项目被删后没有自己的文件夹可承载删除消息，只能全局通告，故独立成表（不再复用 deleted_items 的 projects 行）。
 * 语义与 deleted_items 完全一致："新者胜"（deletedAt < 项目.updatedAt 即被击败、自清理、项目复活），
 * 旧版备份里 deleted_items.tbl='projects' 的施工墓碑在解析时等价并入本表。
 */
@Entity(
    tableName = "deleted_projects",
    indices = [Index(value = ["projectId"], unique = true)]
)
data class DeletedProject(
    @PrimaryKey val id: String = "",
    val projectId: String,
    val deletedAt: Long = System.currentTimeMillis()
)

// ---------- 查询结果 POJO ----------

data class LogListItem(
    @Embedded val log: DebugLog,
    val projectName: String,
    val typeName: String,
    val typeId: String,
    val instanceName: String,
    val deviceCode: String,
    val installer: String
)

data class ProjectListItem(
    @Embedded val project: Project,
    val cabinetCount: Int,
    val logCount: Int,
    val pendingTests: Int,
    val failedTests: Int,
    val pendingFaults: Int
)

/** 项目详情页柜子行：柜子 + 实时待测/未通过/待处理统计 */
data class InstanceStatusRow(
    @Embedded val instance: CabinetInstance,
    val pendingTests: Int,
    val failedTests: Int,
    val pendingFaults: Int
)

data class TypeListItem(
    @Embedded val type: CabinetType,
    val instanceCount: Int,
    val itemCount: Int
)

data class InstanceRow(
    @Embedded val instance: CabinetInstance,
    val projectName: String
)

data class FaultExportRow(
    @Embedded val fault: FaultRecord,
    val projectName: String,
    val instanceName: String,
    val deviceCode: String
)

/** 候选池使用频次：某条待测内容在该类型全部柜子的预选清单中出现的次数（常用排序依据） */
data class CandUsage(
    val content: String,
    val cnt: Int
)
