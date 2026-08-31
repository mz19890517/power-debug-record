# AGENTS.md — AI 会话工作指引

安卓APP「电源柜调试记录」：电力现场调试日志工具（Kotlin + Room + 传统View）。
仓库即完整交付物：代码、CI、文档都在这里。**新会话请先读本文件再动手。**

## 构建方式

- 只通过 GitHub Actions 构建（push 到 main 自动触发，`gh run watch` 等结果）
- 本地无 gradle wrapper / Android SDK，不要尝试本地 assembleDebug
- 产物：Actions artifact `power-debug-debug-apk`；正式发布用 `gh release create vX.X <apk路径>`

## ⚠️ 签名（最重要的约束）

**v2.26 起为新软件、新仓库、新签名**：包名 `com.powerdebug.record`，与老项目 power-cabinet-debug-log（≤v2.25、包名 com.fieldlog.powerdebug）正式分家，签名链在 v2.26 定点重置，老包用户需卸载重装；**本线所有后续版本靠同一签名覆盖安装**。签名材料：

- `app/signing/powerdebug.keystore.zip` —— keystore 的加密压缩包（已入库）
- **解压密码不在仓库里，需要时向项目所有者（用户本人）索取**
- CI 已配置 Secrets 自动解压+签名：`SIGNING_ZIP_PASSWORD` / `SIGNING_STORE_PASSWORD` / `SIGNING_KEY_PASSWORD`
- 原始 `powerdebug.keystore` 被 .gitignore 排除，但项目所有者本机留有一份

规则：
1. 永远不要替换/删除 keystore（新线 v2.26 启用，alias=powerdebug，SHA-256 `71651EC50784C17E17758EE234373C19095D4BD899ED931174DD2DFD352178F8`），不要改动 alias 或密码配置
2. 永远不要把任何密码明文写进代码、README 或提交信息
3. 若用户忘记密码：keystore 无法恢复，签名链断裂 = 全体用户需卸载重装。提醒用户平时自备份

## 数据库与备份格式

- Room schema 当前 version=11（迁移链 1→2→3→…→11 必须保持完整，禁止 fallbackToDestructiveMigration）
- JSON 备份 schemaVersion=11，字段名=数据库列名，是将来 PC/网页端的交换格式；v2.27 起 DebugLog 快照含 logType（0通过/1故障/2消除），旧备份缺键按 0 兼容解析；v2.10 起快照经 gzip 压缩上传，读取按魔数(1f 8b)自动兼容明文旧格式（util/WebDavSync.decodeSnapshot，公开可复用）；快照头含 deviceTimeZoneOffset；readyKind=full/global/project 区分全量/全局区/单项目三种快照
- v2.26 起同步为「全局区 + 每项目一个文件夹」增量结构（规格第7章）：global/backup_<账号>_<设备>.json 存 类型/候选池/调试员/全部删除墓碑（含项目级），projects/project_<项目名>/backup_<账号>_<设备>.json 存该项目 柜子/日志/故障/预选项+项目行；本地修改检测用「项目版本时钟」(Repository.projectClocks = 该项目所有行最大 updatedAt) 与 SyncStore.projectLastSync 比较，不用文件 mtime；墓碑统一走全局区传播（不按项目拆分，无需为墓碑追溯项目归属）；冲突裁决 ConflictFavor(CLOUD/LOCAL)+CONFLICT_WINDOW_MS=5分钟，手动同步歧义时 ToolsFragment.askSyncConflict 弹窗
- v2.29 项目文件夹名 = 清洗后的项目名（WebDavSync.sanitizeFolderName 替换 `[\\/:*?"<>|]`→`_`、压缩空白、空名/`.`/`..`→`未命名`、截断64；重名经 buildProjectKeys 自动补 `-<id前8位>`；本机按名匹配云端文件夹，匹配不到才 resolvePidInFolder 下载快照解析项目id，兼容旧 project_<UUID> 文件夹；项目改名后旧文件夹保留云端、上传条件含"云端无本机同名文件夹"自动收敛到新名字）
- 旧版迁移（v2.26）：登录弹窗可填「旧版数据目录」(SyncStore.legacyUrl)，首次 syncAll 时 migrateLegacy 读取 v2.x 目录全量快照合并进本机库，再在本次会话内按项目时钟分发进新结构；已迁移文件名记 SyncStore(legacyMigratedFiles)，不删不改旧文件（legacyMigrated 置位后可清空复位重试）
- 新增表/字段：DB version+1 写纯SQL迁移 + 备份版本+1 + parseBackup/restoreJson/merge 三处同步 + README 记录变更说明
- 诊断日志：util/SyncLog.kt（同步过程）+ util/CrashLog.kt（全局未捕获异常黑匣子，App.onCreate 安装），工具页「查看同步日志」可查看/复制/清空——排查现场问题的标准手段
- 合并语义：UUID主键按 id 去重插入，同 id 冲突 updatedAt 新者胜；**删除通过墓碑传播且同样"新者胜"**（v6 起 deleted_items 表：显式删除入口写墓碑；合并先落库远端墓碑→比对 墓碑.deletedAt 与本机存活行∪快照携带行最新 updatedAt：删除早于该行更新→墓碑被击败、自清理（tombDao.deleteByRow）并保留/插入该行；删除晚于该行已知全部更新→按表经DAO删除触发级联→跳过已删id与父链已断的孤儿行。防止被删数据借旧快照复活，也防旧删除（v2.9-v2.20 无条件永久墓碑）清掉他机较新数据）。**项目级删除走独立 deleted_projects 表（v11）**，applyMerge/mergePreview 统一口径（项目判死 deadP/projDead），旧版 deleted_items(tbl=projects) 解析时等价并入
- 找回被删记录（v2.22 Repository.rollbackFromBackup）：扫描备份（明文/gzip 均可）中"本机缺失"的行权威插回，找回行统一刷新 updatedAt=now 并清对应墓碑→下次同步以"新者胜"传播回全队；工具页「从备份找回被删记录」（先预览→确认→写库）。仅一台手机执行即可恢复已全端删除的数据。v2.23 预览与执行共用 rollbackRowsOf 同一口径；「无缺失记录」时弹窗对比备份 vs 本机日志构成（总/通过/故障/消除/故障记录），备份内故障/消除为 0 即说明该备份生成于丢失之后，应改选更早快照重试
- 日志类型修复（v2.24/v2.25 Repository.reclassifyLogTypes）：把被 ≥1 条故障记录指向却 logType=0 的日志重分类为 logType=1，只改类型标注+刷新 updatedAt，幂等；成因=旧版本/旧格式快照无 logType 字段迁移默认 0，行数据与故障记录从未丢失。v2.25 起消除日志（备注=已解决故障现象、同柜同测试内容匹配）一并重分类为 logType=2 恢复解决方法显示；修复记录存本机 FixLogStore 供「撤销类型修复」还原

## 业务模型速查

projects → cabinet_types(候选池 candidate_items) → cabinet_instances → debug_logs → fault_records
planned_items：柜子实例的预选待测清单，三态 result（0未测/1通过/2未通过），未通过项复测✓才转绿。
测试员账号 tester_accounts + WebDAV 团队互通（util/WebDavSync.kt，快照 backup_<账号>_<本机标识>.json，同账号多机不互覆）。
debuggers：调试员名单（v5新增），与登录账号无关，增/改/删全部要超级口令；本机「当前调试员」存SyncStore.currentDebugger（写日志自动归属、点击可切换），日志测试人员绝不回落到登录账号；改名/删除不动历史日志。复测✓时该项关联的未解决故障由Repository自动标记已解决（faultDao.resolveByIds）。
常用模板（v2.9）：项目卡长按「加入常用模板」= saveProjectAsTemplate 把项目各柜启用待测项沉淀进各自类型候选池；「从候选池补充」按钮打开 CandidatePickerActivity 手选器（candidatesByUsage 按使用频次降序=该类型全部柜子清单出现次数，长按拖动连续多选、常用选取=使用≥2次）；柜子长按「从别的柜子拉取」= pullPlannedFromCabinet 整体覆盖本柜清单（旧项记墓碑）。

## 其他约定

- 超级口令 mz9890517 可离线注册测试员（源码 SyncStore.kt 内，属产品功能非机密）
- UI 文案全部走 strings.xml；中文注释是本仓库惯例
- 版本发布节奏：功能完成→versionName/versionCode 递增→README 更新→push→CI 绿→下载 APK 放项目根目录→（重要版本）发 GitHub Release
