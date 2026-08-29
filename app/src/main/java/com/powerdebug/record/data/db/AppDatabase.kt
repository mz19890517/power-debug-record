package com.powerdebug.record.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        Project::class,
        CabinetType::class,
        CandidateItem::class,
        CabinetInstance::class,
        DebugLog::class,
        FaultRecord::class,
        PlannedItem::class,
        TesterAccount::class,
        Debugger::class,
        DeletedItem::class,
        DeletedProject::class
    ],
    version = 11,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun cabinetTypeDao(): CabinetTypeDao
    abstract fun candidateItemDao(): CandidateItemDao
    abstract fun instanceDao(): InstanceDao
    abstract fun debugLogDao(): DebugLogDao
    abstract fun faultRecordDao(): FaultRecordDao
    abstract fun plannedItemDao(): PlannedItemDao
    abstract fun testerAccountDao(): TesterAccountDao
    abstract fun debuggerDao(): DebuggerDao
    abstract fun deletedItemDao(): DeletedItemDao
    abstract fun deletedProjectDao(): DeletedProjectDao

    companion object {
        const val DB_NAME = "power_debug.db"

        /** v1→v2：自增int主键改为UUID字符串，全表补updatedAt，debug_logs加创建/修改账号，新增tester_accounts */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 纯SQL生成UUIDv4格式字符串（randomblob方案，无需应用层参与）
                val uuidExpr =
                    "lower(hex(randomblob(4))||'-'||hex(randomblob(2))||'-4'||substr(hex(randomblob(2)),2)" +
                        "||'-'||substr('89ab',abs(random())%4+1,1)||substr(hex(randomblob(2)),2)||'-'||hex(randomblob(6)))"

                // ---------- projects ----------
                db.execSQL("CREATE TABLE IF NOT EXISTS _map_projects(oldId INTEGER NOT NULL PRIMARY KEY, newId TEXT NOT NULL)")
                db.execSQL("INSERT INTO _map_projects(oldId,newId) SELECT id, $uuidExpr FROM projects")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS projects_new(" +
                        "id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, code TEXT NOT NULL, remark TEXT NOT NULL, " +
                        "createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)"
                )
                db.execSQL(
                    "INSERT INTO projects_new(id,name,code,remark,createdAt,updatedAt) " +
                        "SELECT m.newId,p.name,p.code,p.remark,p.createdAt,p.createdAt " +
                        "FROM projects p INNER JOIN _map_projects m ON m.oldId=p.id"
                )
                db.execSQL("DROP TABLE projects")
                db.execSQL("ALTER TABLE projects_new RENAME TO projects")
                db.execSQL("DROP TABLE _map_projects")

                // ---------- cabinet_types ----------
                db.execSQL("CREATE TABLE IF NOT EXISTS _map_types(oldId INTEGER NOT NULL PRIMARY KEY, newId TEXT NOT NULL)")
                db.execSQL("INSERT INTO _map_types(oldId,newId) SELECT id, $uuidExpr FROM cabinet_types")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS cabinet_types_new(" +
                        "id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, remark TEXT NOT NULL, " +
                        "createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)"
                )
                db.execSQL(
                    "INSERT INTO cabinet_types_new(id,name,remark,createdAt,updatedAt) " +
                        "SELECT m.newId,t.name,t.remark,t.createdAt,t.createdAt " +
                        "FROM cabinet_types t INNER JOIN _map_types m ON m.oldId=t.id"
                )
                db.execSQL("DROP TABLE cabinet_types")
                db.execSQL("ALTER TABLE cabinet_types_new RENAME TO cabinet_types")
                db.execSQL("DROP TABLE _map_types")

                // ---------- candidate_items ----------
                db.execSQL("CREATE TABLE IF NOT EXISTS _map_cands(oldId INTEGER NOT NULL PRIMARY KEY, newId TEXT NOT NULL)")
                db.execSQL("INSERT INTO _map_cands(oldId,newId) SELECT id, $uuidExpr FROM candidate_items")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS candidate_items_new(" +
                        "id TEXT NOT NULL PRIMARY KEY, typeId TEXT NOT NULL, content TEXT NOT NULL, " +
                        "createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, " +
                        "FOREIGN KEY(typeId) REFERENCES cabinet_types(id) ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL(
                    "INSERT OR IGNORE INTO candidate_items_new(id,typeId,content,createdAt,updatedAt) " +
                        "SELECT m.newId,mt.newId,c.content,c.createdAt,c.createdAt " +
                        "FROM candidate_items c " +
                        "INNER JOIN _map_cands mc ON mc.oldId=c.id INNER JOIN _map_types mt ON mt.oldId=c.typeId"
                )
                db.execSQL("DROP TABLE candidate_items")
                db.execSQL("ALTER TABLE candidate_items_new RENAME TO candidate_items")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_candidate_items_typeId ON candidate_items(typeId)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_candidate_items_typeId_content ON candidate_items(typeId, content)"
                )
                db.execSQL("DROP TABLE _map_cands")

                // ---------- instances ----------
                db.execSQL("CREATE TABLE IF NOT EXISTS _map_insts(oldId INTEGER NOT NULL PRIMARY KEY, newId TEXT NOT NULL)")
                db.execSQL("INSERT INTO _map_insts(oldId,newId) SELECT id, $uuidExpr FROM instances")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS instances_new(" +
                        "id TEXT NOT NULL PRIMARY KEY, projectId TEXT NOT NULL, typeId TEXT NOT NULL, name TEXT NOT NULL, " +
                        "deviceCode TEXT NOT NULL, location TEXT NOT NULL, installer TEXT NOT NULL, " +
                        "createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, " +
                        "FOREIGN KEY(projectId) REFERENCES projects(id) ON UPDATE NO ACTION ON DELETE CASCADE, " +
                        "FOREIGN KEY(typeId) REFERENCES cabinet_types(id) ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL(
                    "INSERT INTO instances_new(id,projectId,typeId,name,deviceCode,location,installer,createdAt,updatedAt) " +
                        "SELECT mi.newId,mp.newId,mt.newId,i.name,i.deviceCode,i.location,i.installer,i.createdAt,i.createdAt " +
                        "FROM instances i " +
                        "INNER JOIN _map_insts mi ON mi.oldId=i.id " +
                        "INNER JOIN _map_projects mp ON mp.oldId=i.projectId " +
                        "INNER JOIN _map_types mt ON mt.oldId=i.typeId"
                )
                db.execSQL("DROP TABLE instances")
                db.execSQL("ALTER TABLE instances_new RENAME TO instances")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_instances_projectId ON instances(projectId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_instances_typeId ON instances(typeId)")
                db.execSQL("DROP TABLE _map_insts")

                // ---------- debug_logs ----------
                db.execSQL("CREATE TABLE IF NOT EXISTS _map_logs(oldId INTEGER NOT NULL PRIMARY KEY, newId TEXT NOT NULL)")
                db.execSQL("INSERT INTO _map_logs(oldId,newId) SELECT id, $uuidExpr FROM debug_logs")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS debug_logs_new(" +
                        "id TEXT NOT NULL PRIMARY KEY, instanceId TEXT NOT NULL, circuit TEXT NOT NULL, testContent TEXT NOT NULL, " +
                        "tester TEXT NOT NULL, remark TEXT NOT NULL, createdBy TEXT NOT NULL, updatedBy TEXT NOT NULL, " +
                        "createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, " +
                        "FOREIGN KEY(instanceId) REFERENCES instances(id) ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL(
                    "INSERT INTO debug_logs_new(id,instanceId,circuit,testContent,tester,remark,createdBy,updatedBy,createdAt,updatedAt) " +
                        "SELECT ml.newId,minst.newId,l.circuit,l.testContent,l.tester,l.remark,'','',l.createdAt,l.updatedAt " +
                        "FROM debug_logs l " +
                        "INNER JOIN _map_logs ml ON ml.oldId=l.id INNER JOIN _map_insts minst ON minst.oldId=l.instanceId"
                )
                db.execSQL("DROP TABLE debug_logs")
                db.execSQL("ALTER TABLE debug_logs_new RENAME TO debug_logs")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_debug_logs_instanceId ON debug_logs(instanceId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_debug_logs_createdAt ON debug_logs(createdAt)")
                db.execSQL("DROP TABLE _map_logs")

                // ---------- fault_records ----------
                db.execSQL("CREATE TABLE IF NOT EXISTS _map_faults(oldId INTEGER NOT NULL PRIMARY KEY, newId TEXT NOT NULL)")
                db.execSQL("INSERT INTO _map_faults(oldId,newId) SELECT id, $uuidExpr FROM fault_records")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS fault_records_new(" +
                        "id TEXT NOT NULL PRIMARY KEY, logId TEXT NOT NULL, circuit TEXT NOT NULL, symptom TEXT NOT NULL, " +
                        "solution TEXT NOT NULL, occurredAt INTEGER NOT NULL, resolvedAt INTEGER NOT NULL, status INTEGER NOT NULL, " +
                        "updatedAt INTEGER NOT NULL, " +
                        "FOREIGN KEY(logId) REFERENCES debug_logs(id) ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL(
                    "INSERT INTO fault_records_new(id,logId,circuit,symptom,solution,occurredAt,resolvedAt,status,updatedAt) " +
                        "SELECT mf.newId,mlog.newId,f.circuit,f.symptom,f.solution,f.occurredAt,f.resolvedAt,f.status,f.occurredAt " +
                        "FROM fault_records f " +
                        "INNER JOIN _map_faults mf ON mf.oldId=f.id INNER JOIN _map_logs mlog ON mlog.oldId=f.logId"
                )
                db.execSQL("DROP TABLE fault_records")
                db.execSQL("ALTER TABLE fault_records_new RENAME TO fault_records")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_fault_records_logId ON fault_records(logId)")
                db.execSQL("DROP TABLE _map_faults")

                // ---------- tester_accounts（v2新增） ----------
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS tester_accounts(" +
                        "id TEXT NOT NULL PRIMARY KEY, username TEXT NOT NULL, source TEXT NOT NULL, createdAt INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_tester_accounts_username ON tester_accounts(username)"
                )
            }
        }

        /** v2→v3：新增预选待测清单表 planned_items（柜子实例级，建实例时从类型候选池复制初始清单） */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `planned_items` (" +
                        "`id` TEXT NOT NULL, `instanceId` TEXT NOT NULL, `content` TEXT NOT NULL, " +
                        "`enabled` INTEGER NOT NULL, `doneAt` INTEGER NOT NULL, `logId` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
                        "FOREIGN KEY(`instanceId`) REFERENCES `instances`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_planned_items_instanceId` ON `planned_items` (`instanceId`)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_planned_items_instanceId_content` ON `planned_items` (`instanceId`, `content`)"
                )
            }
        }

        /** v3→v4：预选待测项增加三态结果（0未测/1通过/2未通过）与关联故障id */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `planned_items` ADD COLUMN `result` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `planned_items` ADD COLUMN `faultId` TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v4→v5：新增调试员名单表 debuggers（超级口令维护，随备份/WebDAV互通，name唯一） */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `debuggers` (" +
                        "`id` TEXT NOT NULL PRIMARY KEY, " +
                        "`name` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_debuggers_name` ON `debuggers` (`name`)"
                )
            }
        }

        /** v5→v6：新增删除墓碑表 deleted_items（删除操作随同步传播，防"删了又长回来"） */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `deleted_items` (" +
                        "`id` TEXT NOT NULL PRIMARY KEY, " +
                        "`tbl` TEXT NOT NULL, " +
                        "`itemId` TEXT NOT NULL, " +
                        "`deletedAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_deleted_items_tbl_itemId` " +
                        "ON `deleted_items` (`tbl`, `itemId`)"
                )
            }
        }

        /** v6→v7：柜子实例新增精简名 shortName（网格视图显示用，不影响导出） */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `instances` ADD COLUMN `shortName` TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v7→v8：柜子实例新增 sortOrder（拖动排序用，0=默认按名称） */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `instances` ADD COLUMN `sortOrder` INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try {
                    db.execSQL("ALTER TABLE `instances` ADD COLUMN `rowGroup` INTEGER NOT NULL DEFAULT 0")
                } catch (_: Exception) {
                    // column may already exist from broken v2.17 install
                }
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `debug_logs` ADD COLUMN `logType` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v10→v11：新增项目级删除墓碑表 deleted_projects（项目删除走云端 global 区传播，"新者胜"语义与 deleted_items 一致） */
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `deleted_projects` (" +
                        "`id` TEXT NOT NULL PRIMARY KEY, " +
                        "`projectId` TEXT NOT NULL, " +
                        "`deletedAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_deleted_projects_projectId` " +
                        "ON `deleted_projects` (`projectId`)"
                )
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
                .addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
                    MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11
                )
                .build()
    }
}
