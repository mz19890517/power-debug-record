package com.powerdebug.record.ui.device

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.powerdebug.record.App
import com.powerdebug.record.R
import com.powerdebug.record.core.ExportSheets
import com.powerdebug.record.core.XlsxWriter
import com.powerdebug.record.data.ExportFilter
import com.powerdebug.record.data.db.CabinetInstance
import com.powerdebug.record.data.db.InstanceStatusRow
import com.powerdebug.record.ui.FilterDialogHelper
import com.powerdebug.record.data.db.Project
import com.powerdebug.record.databinding.ItemSimpleCardBinding
import com.powerdebug.record.databinding.ItemSimpleCardGridBinding
import com.powerdebug.record.ui.test.PlannedManageActivity
import com.powerdebug.record.ui.test.TestChecklistActivity
import com.powerdebug.record.util.DeleteSafeguard
import com.powerdebug.record.util.DT
import com.powerdebug.record.util.SyncLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ProjectDetailActivity : AppCompatActivity() {

    companion object {
        const val KEY_PROJECT_ID = "project_id"
        private const val XLSX_MIME =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        private const val PREF_NAME = "project_detail_prefs"
        private const val KEY_GRID_VIEW = "grid_view"
        private const val KEY_NAME_MODE = "name_mode"
        private const val KEY_SORT_MODE = "sort_mode"
        private const val TYPE_LIST = 0
        private const val TYPE_GRID = 1
        private const val TYPE_GRID_GROUPED = 2
        private const val SORT_CUSTOM = 0
        private const val SORT_NAME_ASC = 1
        private const val SORT_NAME_DESC = 2
        private const val SORT_FAULT_DESC = 3
        private const val SORT_TEST_DESC = 4
        private const val MAX_PER_ROW = 4

        fun intent(ctx: Context, projectId: String) =
            Intent(ctx, ProjectDetailActivity::class.java).putExtra(KEY_PROJECT_ID, projectId)
    }

    private var projectId = ""
    private lateinit var adapter: InstanceAdapter
    private var typeNames: Map<String, String> = emptyMap()
    private var project: Project? = null
    private var latestRows: List<InstanceStatusRow> = emptyList()
    private var isGridLayout = false
    private var isShortNameMode = false
    private var sortMode = SORT_CUSTOM

    /** 单柜日志导出（长按菜单入口） */
    private var exportInstanceId = ""
    private var currentInstanceFilter = ExportFilter()
    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument(XLSX_MIME)
    ) { uri -> uri?.let { doExportInstance(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_project_detail)

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        projectId = intent.getStringExtra(KEY_PROJECT_ID).orEmpty()
        val prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE)
        isGridLayout = prefs.getBoolean(KEY_GRID_VIEW, false)
        isShortNameMode = prefs.getBoolean(KEY_NAME_MODE, false)
        sortMode = prefs.getInt(KEY_SORT_MODE, SORT_CUSTOM)

        lifecycleScope.launch {
            typeNames = App.repo.allTypes().associate { it.id to it.name }
        }

        adapter = InstanceAdapter(
            onClick = { routeInstanceClick(it.instance) },
            onLongClick = { showInstanceMenu(it.instance) }
        )
        val rv = findViewById<RecyclerView>(R.id.rv_instances)
        if (isGridLayout) {
            val glm = GridLayoutManager(this, 4)
            glm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    return if (adapter.isGroupedMode) 4 else 1
                }
            }
            rv.layoutManager = glm
        } else {
            rv.layoutManager = LinearLayoutManager(this)
        }
        rv.adapter = adapter

        findViewById<View>(R.id.btn_add_instance).setOnClickListener { showInstanceDialog(null) }

        lifecycleScope.launch {
            App.db.instanceDao().watchByProjectWithStatsAsFlow(projectId).collect { list ->
                latestRows = list
                submitData()
                findViewById<TextView>(R.id.tv_empty_instances).visibility =
                    if (list.isEmpty()) View.VISIBLE else View.GONE
                refreshHeader(list)
            }
        }

        lifecycleScope.launch {
            project = App.repo.getProject(projectId)
            supportActionBar?.title = project?.name ?: getString(R.string.title_project_detail)
            refreshHeader(latestRows)
        }
    }

    /** 根据当前排列方式提交数据 */
    private fun submitData() {
        if (sortMode == SORT_CUSTOM) {
            // 自定义模式：按行分配显示
            val grouped = buildGroupedList()
            adapter.submitGrouped(grouped)
        } else {
            // 自动排序模式：扁平列表
            val sorted = when (sortMode) {
                SORT_NAME_ASC -> latestRows.sortedBy { it.instance.name }
                SORT_NAME_DESC -> latestRows.sortedByDescending { it.instance.name }
                SORT_FAULT_DESC -> latestRows.sortedByDescending { it.pendingFaults }
                SORT_TEST_DESC -> latestRows.sortedByDescending { it.pendingTests }
                else -> latestRows.sortedBy { it.instance.name }
            }
            adapter.submitFlat(sorted)
        }
    }

    /** 根据数据库 rowGroup 字段构建行分组列表，每行最多 MAX_PER_ROW 个 */
    private fun buildGroupedList(): List<List<InstanceStatusRow>> {
        val allRows = mutableListOf<List<InstanceStatusRow>>()
        val assignedIds = mutableSetOf<String>()

        // 按 rowGroup 分组（rowGroup=0 表示未分组）
        val grouped = latestRows.filter { it.instance.rowGroup > 0 }
            .groupBy { it.instance.rowGroup }
            .toSortedMap()

        for ((_, items) in grouped) {
            if (items.isNotEmpty()) {
                allRows.add(items.take(MAX_PER_ROW))
                items.take(MAX_PER_ROW).forEach { assignedIds.add(it.instance.id) }
            }
        }

        // 未分配的柜子追加到新行
        val unassigned = latestRows.filter { it.instance.id !in assignedIds }
        unassigned.chunked(MAX_PER_ROW).forEach { allRows.add(it) }

        return allRows
    }

    private fun refreshHeader(rows: List<InstanceStatusRow>) {
        val p = project ?: return
        val pendingTests = rows.sumOf { it.pendingTests }
        val failedTests = rows.sumOf { it.failedTests }
        val pendingFaults = rows.sumOf { it.pendingFaults }
        findViewById<TextView>(R.id.tv_project_info).text = buildString {
            appendLine("项目：${p.name}")
            if (p.code.isNotBlank()) appendLine("工程编号：${p.code}")
            if (p.remark.isNotBlank()) appendLine("备注：${p.remark}")
            append("共 ${rows.size} 台柜子 · 待测 $pendingTests · 未通过 $failedTests · 待处理故障 $pendingFaults")
        }
    }

    // ---------- 菜单 ----------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_project_detail, menu)
        updateMenuAppearance(menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        updateMenuAppearance(menu)
        return super.onPrepareOptionsMenu(menu)
    }

    private fun updateMenuAppearance(menu: Menu) {
        val toggleItem = menu.findItem(R.id.action_toggle_view)
        val nameModeItem = menu.findItem(R.id.action_name_mode)
        val sortItem = menu.findItem(R.id.action_sort)
        val editItem = menu.findItem(R.id.action_edit_project)
        val deleteItem = menu.findItem(R.id.action_delete_project)

        toggleItem?.isVisible = true
        toggleItem?.setIcon(if (isGridLayout) R.drawable.ic_list_view else R.drawable.ic_grid_view)
        nameModeItem?.isVisible = true
        nameModeItem?.setTitle(if (isShortNameMode) R.string.action_name_mode_full else R.string.action_name_mode_short)
        sortItem?.isVisible = true
        editItem?.isVisible = true
        deleteItem?.isVisible = true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_toggle_view -> { toggleView(); true }
        R.id.action_name_mode -> { toggleNameMode(); true }
        R.id.action_sort -> { showRowEditorDialog(); true }
        R.id.action_edit_project -> { project?.let { editProject(it) }; true }
        R.id.action_delete_project -> { deleteProject(); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun toggleView() {
        isGridLayout = !isGridLayout
        getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit().putBoolean(KEY_GRID_VIEW, isGridLayout).apply()
        val rv = findViewById<RecyclerView>(R.id.rv_instances)
        if (isGridLayout) {
            val glm = GridLayoutManager(this, 4)
            glm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    return if (adapter.isGroupedMode) 4 else 1
                }
            }
            rv.layoutManager = glm
        } else {
            rv.layoutManager = LinearLayoutManager(this)
        }
        adapter.notifyDataSetChanged()
        invalidateOptionsMenu()
    }

    private fun toggleNameMode() {
        isShortNameMode = !isShortNameMode
        getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit().putBoolean(KEY_NAME_MODE, isShortNameMode).apply()
        adapter.notifyDataSetChanged()
        invalidateOptionsMenu()
    }

    private fun setSortMode(mode: Int) {
        sortMode = mode
        getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit().putInt(KEY_SORT_MODE, mode).apply()
        submitData()
        invalidateOptionsMenu()
    }

    // ---------- 行编辑器对话框 ----------

    private fun showRowEditorDialog() {
        val dlgView = layoutInflater.inflate(R.layout.dialog_row_editor, null)
        val sortGroup = dlgView.findViewById<android.widget.RadioGroup>(R.id.rg_sort_options)
        val rowContainer = dlgView.findViewById<LinearLayout>(R.id.row_container)
        val tvRowHint = dlgView.findViewById<TextView>(R.id.tv_row_hint)

        // 设置当前排序选项
        when (sortMode) {
            SORT_NAME_ASC -> sortGroup.check(R.id.rb_sort_name_asc)
            SORT_NAME_DESC -> sortGroup.check(R.id.rb_sort_name_desc)
            SORT_FAULT_DESC -> sortGroup.check(R.id.rb_sort_fault_desc)
            SORT_TEST_DESC -> sortGroup.check(R.id.rb_sort_test_desc)
            else -> sortGroup.check(R.id.rb_sort_custom)
        }

        // 显示行分配区域
        val isCustom = sortMode == SORT_CUSTOM
        rowContainer.visibility = if (isCustom) View.VISIBLE else View.GONE
        tvRowHint.visibility = if (isCustom) View.VISIBLE else View.GONE

        if (isCustom) {
            refreshRowEditorContent(rowContainer)
        }

        // 切换排序选项时刷新行区域
        sortGroup.setOnCheckedChangeListener { _, checkedId ->
            val newCustom = checkedId == R.id.rb_sort_custom
            rowContainer.visibility = if (newCustom) View.VISIBLE else View.GONE
            tvRowHint.visibility = if (newCustom) View.VISIBLE else View.GONE
            if (newCustom) refreshRowEditorContent(rowContainer)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.row_editor_title)
            .setView(dlgView)
            .setPositiveButton(R.string.save) { _, _ ->
                // 保存排序模式
                val newMode = when (sortGroup.checkedRadioButtonId) {
                    R.id.rb_sort_name_asc -> SORT_NAME_ASC
                    R.id.rb_sort_name_desc -> SORT_NAME_DESC
                    R.id.rb_sort_fault_desc -> SORT_FAULT_DESC
                    R.id.rb_sort_test_desc -> SORT_TEST_DESC
                    else -> SORT_CUSTOM
                }
                setSortMode(newMode)

                // 如果是自定义模式，保存行分配
                if (newMode == SORT_CUSTOM) {
                    saveRowAssignmentsFromDialog(rowContainer)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 刷新行编辑器对话框内的行内容 */
    private fun refreshRowEditorContent(container: LinearLayout) {
        container.removeAllViews()
        val grouped = buildGroupedList()

        for ((index, row) in grouped.withIndex()) {
            val rowNum = index + 1
            addRowToEditor(container, rowNum, row)
        }

        // 永远在尾端多加一行空号，方便继续添加
        addRowToEditor(container, grouped.size + 1, emptyList())
    }

    private fun addRowToEditor(container: LinearLayout, rowNum: Int, items: List<InstanceStatusRow>) {
        val rowView = layoutInflater.inflate(R.layout.dialog_row_editor_row, container, false)
        val tvRowNum = rowView.findViewById<TextView>(R.id.tv_row_number)
        val rowItemsContainer = rowView.findViewById<LinearLayout>(R.id.row_items_container)
        val btnAddToRow = rowView.findViewById<View>(R.id.btn_add_to_row)

        tvRowNum.text = getString(R.string.row_number_fmt, rowNum)

        for (item in items) {
            addChipTo(rowItemsContainer, displayName(item))
        }

        btnAddToRow.setOnClickListener {
            showAddToRowDialog(rowNum, rowItemsContainer, container)
        }

        container.addView(rowView)
    }

    /** 名称模式下的显示名（精简名优先，空则全名） */
    private fun displayName(row: InstanceStatusRow): String =
        if (isShortNameMode) row.instance.shortName.ifBlank { row.instance.name } else row.instance.name

    /** 向某行的 chips 容器追加一枚柜子 chip */
    private fun addChipTo(targetContainer: LinearLayout, name: String) {
        val chip = layoutInflater.inflate(R.layout.dialog_row_editor_chip, targetContainer, false)
        val tvChipName = chip.findViewById<TextView>(R.id.tv_chip_name)
        val btnRemove = chip.findViewById<View>(R.id.btn_chip_remove)
        tvChipName.text = name
        btnRemove.setOnClickListener { targetContainer.removeView(chip) }
        targetContainer.addView(chip)
    }

    /** 编辑器对话框内当前所有排的柜子归属（id → 排号），未保存前即生效 */
    private fun buildLiveRowMap(editorRoot: LinearLayout): Map<String, Int> {
        val map = mutableMapOf<String, Int>()
        for (i in 0 until editorRoot.childCount) {
            val rowView = editorRoot.getChildAt(i) ?: continue
            val rowItemsContainer = rowView.findViewById<LinearLayout>(R.id.row_items_container) ?: continue
            for (j in 0 until rowItemsContainer.childCount) {
                val chip = rowItemsContainer.getChildAt(j)
                val tvName = chip.findViewById<TextView>(R.id.tv_chip_name) ?: continue
                val match = latestRows.find { displayName(it) == tvName.text.toString() }
                match?.let { map[it.instance.id] = i + 1 }
            }
        }
        return map
    }

    /**
     * 选择加入本排柜子的弹窗：搜索定位 + 排除所有已分配柜子（含其他排，不再重复选择）
     * + 连续多选（点一个加一个，不关弹窗）——柜子多的大项目也能快速排好一排。
     */
    private fun showAddToRowDialog(rowNum: Int, targetContainer: LinearLayout, editorRoot: LinearLayout) {
        // 实时分布：编辑器内每枚 chip 现在位于哪一排（跨排已分配的不可再选）
        val liveRows = buildLiveRowMap(editorRoot)

        // 全部已分配则直接提示，不空开弹窗
        if (latestRows.none { liveRows[it.instance.id] == null }) {
            Toast.makeText(this, R.string.row_all_assigned, Toast.LENGTH_SHORT).show()
            return
        }

        val dlgView = layoutInflater.inflate(R.layout.dialog_row_add_cabinet, null)
        val etSearch = dlgView.findViewById<EditText>(R.id.et_row_search)
        val listContainer = dlgView.findViewById<LinearLayout>(R.id.ll_row_picker_list)

        fun displayRows(): List<InstanceStatusRow> {
            val q = etSearch.text.toString().trim()
            val filtered = if (q.isEmpty()) latestRows else latestRows.filter {
                it.instance.name.contains(q, ignoreCase = true) ||
                    (it.instance.shortName.isNotEmpty() && it.instance.shortName.contains(q, ignoreCase = true))
            }
            // 未分配在前，其后按所在排号、再按柜名
            return filtered.sortedWith(
                compareBy<InstanceStatusRow> { if (liveRows[it.instance.id] == null) 0 else 1 }
                    .thenBy { liveRows[it.instance.id] ?: Int.MAX_VALUE }
                    .thenBy { it.instance.name }
            )
        }

        fun renderList() {
            listContainer.removeAllViews()
            for (row in displayRows()) {
                val item = layoutInflater.inflate(R.layout.item_row_add_cabinet, listContainer, false)
                val tvName = item.findViewById<TextView>(R.id.tv_picker_name)
                val tvStatus = item.findViewById<TextView>(R.id.tv_picker_status)
                tvName.text = displayName(row)

                val atRow = liveRows[row.instance.id]
                if (atRow == null) {
                    // 未分配：可点选加入本排，选中后立即变为"已在第N排"置灰
                    tvStatus.text = getString(R.string.row_picker_unassigned)
                    item.setOnClickListener {
                        if (targetContainer.childCount >= MAX_PER_ROW) {
                            Toast.makeText(this, R.string.row_max_reached, Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        addChipTo(targetContainer, displayName(row))
                        liveRows[row.instance.id] = rowNum
                        renderList()
                    }
                } else {
                    // 已分配（本排或其他排）：展示去处、禁止重复选择
                    tvStatus.text = getString(R.string.row_picker_in_row_fmt, atRow)
                    item.isEnabled = false
                    item.alpha = 0.45f
                }
                listContainer.addView(item)
            }
        }

        renderList()
        etSearch.doAfterTextChanged { renderList() }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.row_add_cabinet_fmt, rowNum))
            .setView(dlgView)
            .setPositiveButton(R.string.row_picker_done, null)
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 从对话框中读取行分配并保存 */
    private fun saveRowAssignmentsFromDialog(container: LinearLayout) {
        val newAssignments = mutableMapOf<Int, MutableList<String>>()
        for (i in 0 until container.childCount) {
            val rowView = container.getChildAt(i)
            val rowNum = (i + 1)
            val rowItemsContainer = rowView.findViewById<LinearLayout>(R.id.row_items_container) ?: continue

            val ids = mutableListOf<String>()
            for (j in 0 until rowItemsContainer.childCount) {
                val chip = rowItemsContainer.getChildAt(j)
                val tvName = chip.findViewById<TextView>(R.id.tv_chip_name) ?: continue
                val match = latestRows.find { displayName(it) == tvName.text.toString() }
                match?.let { ids.add(it.instance.id) }
            }
            if (ids.isNotEmpty()) {
                newAssignments[rowNum] = ids
            }
        }

        // 保存到数据库（rowGroup 字段随同步互通）
        lifecycleScope.launch {
            var order = 1
            for ((rowNum, ids) in newAssignments.toSortedMap()) {
                for (id in ids) {
                    App.db.instanceDao().updateRowGroup(id, rowNum)
                    App.db.instanceDao().updateSortOrder(id, order++)
                }
            }
            // 未分配的柜子：rowGroup=0，排在最后
            val assignedIds = newAssignments.values.flatten().toSet()
            for (row in latestRows) {
                if (row.instance.id !in assignedIds) {
                    App.db.instanceDao().updateRowGroup(row.instance.id, 0)
                    App.db.instanceDao().updateSortOrder(row.instance.id, order++)
                }
            }
        }
    }

    // ---------- 柜子实例 ----------

    private fun routeInstanceClick(inst: CabinetInstance) {
        lifecycleScope.launch {
            val hasItems = App.db.plannedItemDao().allOfInstanceOnce(inst.id).any { it.enabled }
            if (hasItems) {
                startActivity(TestChecklistActivity.intent(this@ProjectDetailActivity, inst.id))
            } else {
                Toast.makeText(this@ProjectDetailActivity, R.string.planned_first_hint, Toast.LENGTH_SHORT).show()
                startActivity(PlannedManageActivity.intent(this@ProjectDetailActivity, inst.id))
            }
        }
    }

    /** 统一的柜子长按菜单：列表和网格共用 */
    private fun showInstanceMenu(inst: CabinetInstance) {
        AlertDialog.Builder(this)
            .setTitle(inst.name)
            .setItems(
                arrayOf(
                    getString(R.string.menu_edit_instance),
                    getString(R.string.short_name_title),
                    getString(R.string.menu_manage_planned),
                    getString(R.string.menu_export_instance),
                    getString(R.string.menu_pull_planned),
                    getString(R.string.delete)
                )
            ) { _, which ->
                when (which) {
                    0 -> showInstanceDialog(inst)
                    1 -> showShortNameDialog(inst)
                    2 -> startActivity(PlannedManageActivity.intent(this, inst.id))
                    3 -> requestExportInstance(inst)
                    4 -> showPullSourceDialog(inst)
                    5 -> confirmDeleteInstance(inst)
                }
            }
            .show()
    }

    // ---------- 跨柜拉取预选待测 ----------

    private fun showPullSourceDialog(target: CabinetInstance) {
        lifecycleScope.launch {
            val sources = App.repo.allInstancesWithProject().filter { it.instance.id != target.id }
            if (sources.isEmpty()) {
                Toast.makeText(this@ProjectDetailActivity, R.string.pull_no_source, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val dlgView = layoutInflater.inflate(R.layout.dialog_pull_source, null)
            val etSearch = dlgView.findViewById<EditText>(R.id.et_search)
            val rv = dlgView.findViewById<RecyclerView>(R.id.rv_sources)
            val tvEmpty = dlgView.findViewById<TextView>(R.id.tv_pull_empty)

            val srcAdapter = SourceAdapter(sources) { src ->
                confirmPull(target, src)
            }
            rv.layoutManager = LinearLayoutManager(this@ProjectDetailActivity)
            rv.adapter = srcAdapter

            AlertDialog.Builder(this@ProjectDetailActivity)
                .setTitle(getString(R.string.pull_title_fmt, target.name))
                .setView(dlgView)
                .setNegativeButton(R.string.cancel, null)
                .show()

            etSearch.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    srcAdapter.filter(s?.toString()?.trim().orEmpty())
                }
            })
        }
    }

    private fun confirmPull(
        target: CabinetInstance,
        src: com.powerdebug.record.data.db.InstanceRow
    ) {
        lifecycleScope.launch {
            try {
                val srcCount =
                    App.db.plannedItemDao().contentsOnce(src.instance.id).count { it.isNotBlank() }
                val tgtCount = App.db.plannedItemDao().allOfInstanceOnce(target.id).size
                AlertDialog.Builder(this@ProjectDetailActivity)
                    .setTitle(R.string.pull_confirm_title)
                    .setIcon(android.R.drawable.ic_dialog_alert)
                    .setMessage(
                        getString(
                            R.string.pull_confirm_msg,
                            "${src.projectName}·${src.instance.name}", srcCount, tgtCount
                        )
                    )
                    .setPositiveButton(R.string.confirm) { _, _ ->
                        lifecycleScope.launch {
                            try {
                                val n = App.repo.pullPlannedFromCabinet(
                                    target.id, src.instance.id
                                )
                                Toast.makeText(
                                    this@ProjectDetailActivity,
                                    getString(R.string.pull_done_fmt, n),
                                    Toast.LENGTH_SHORT
                                ).show()
                            } catch (e: Exception) {
                                logPullError("执行覆盖", e)
                            }
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            } catch (e: Exception) {
                logPullError("读取清单", e)
            }
        }
    }

    private fun logPullError(stage: String, e: Exception) {
        SyncLog.append(
            this,
            "⚠ 跨柜拉取[$stage] ${e.javaClass.name}: ${e.message} ⏎ " +
                e.stackTraceToString().lineSequence().take(6).joinToString(" ⏎ ")
        )
        Toast.makeText(
            this, "拉取失败[${stage}]：${e.javaClass.simpleName}: ${e.message}",
            Toast.LENGTH_LONG
        ).show()
    }

    private inner class SourceAdapter(
        private val all: List<com.powerdebug.record.data.db.InstanceRow>,
        private val onClick: (com.powerdebug.record.data.db.InstanceRow) -> Unit
    ) : RecyclerView.Adapter<SourceAdapter.SVH>() {

        private val shown = mutableListOf<com.powerdebug.record.data.db.InstanceRow>()

        init { filter("") }

        fun filter(q: String) {
            shown.clear()
            val key = q.trim()
            shown += all.filter {
                key.isEmpty() ||
                    it.projectName.contains(key, ignoreCase = true) ||
                    it.instance.name.contains(key, ignoreCase = true) ||
                    it.instance.deviceCode.contains(key, ignoreCase = true)
            }
            notifyDataSetChanged()
        }

        inner class SVH(val ib: ItemSimpleCardBinding) : RecyclerView.ViewHolder(ib.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            SVH(ItemSimpleCardBinding.inflate(layoutInflater, parent, false))

        override fun getItemCount() = shown.size

        override fun onBindViewHolder(h: SVH, pos: Int) {
            val row = shown[pos]
            h.ib.tvName.text = row.instance.name
            h.ib.tvSub.text = buildString {
                append(row.projectName)
                typeNames[row.instance.typeId]?.let { append(" · ").append(it) }
                if (row.instance.deviceCode.isNotBlank()) append(" · 编号:").append(row.instance.deviceCode)
            }
            h.ib.root.setOnClickListener { onClick(row) }
        }
    }

    private fun requestExportInstance(inst: CabinetInstance) {
        exportInstanceId = inst.id
        FilterDialogHelper.show(this, lifecycleScope, currentInstanceFilter) { filter ->
            currentInstanceFilter = filter
            exportLauncher.launch("电源柜调试日志_${inst.name}_${DT.fileStamp()}.xlsx")
        }
    }

    private fun doExportInstance(uri: Uri) {
        lifecycleScope.launch {
            try {
                val (logs, faults) = App.repo.collectExportOf(instanceId = exportInstanceId, filter = currentInstanceFilter)
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        XlsxWriter.write(out, ExportSheets.build(
                            this@ProjectDetailActivity, logs, faults,
                            logColumns = currentInstanceFilter.logColumns,
                            faultColumns = currentInstanceFilter.faultColumns
                        ))
                    } ?: throw IllegalStateException("无法打开输出流")
                }
                Toast.makeText(
                    this@ProjectDetailActivity,
                    getString(R.string.export_ok, uri.lastPathSegment ?: ""),
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                Toast.makeText(
                    this@ProjectDetailActivity,
                    getString(R.string.op_failed, e.message ?: e.javaClass.simpleName),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun confirmDeleteInstance(inst: CabinetInstance) {
        lifecycleScope.launch {
            val logs = App.db.debugLogDao().countLogsOf(inst.id)
            DeleteSafeguard.confirmDelete(
                context = this@ProjectDetailActivity,
                title = R.string.delete,
                message = getString(R.string.warn_del_instance, inst.name, logs),
                typeName = "柜子"
            ) {
                lifecycleScope.launch { App.repo.deleteInstance(inst.id) }
            }
        }
    }

    private fun showInstanceDialog(existing: CabinetInstance?) {
        val dlgView = layoutInflater.inflate(R.layout.dialog_instance_edit, null)
        val etName = dlgView.findViewById<EditText>(R.id.etInstName)
        val etCode = dlgView.findViewById<EditText>(R.id.etInstCode)
        val etLocation = dlgView.findViewById<EditText>(R.id.etInstLocation)
        val etInstaller = dlgView.findViewById<EditText>(R.id.etInstaller)
        val spType = dlgView.findViewById<android.widget.Spinner>(R.id.spType)

        lifecycleScope.launch {
            val types = App.repo.allTypes()
            if (types.isEmpty()) {
                Toast.makeText(this@ProjectDetailActivity, "请先在「柜子类型」页创建类型模板", Toast.LENGTH_LONG).show()
                return@launch
            }
            spType.adapter = android.widget.ArrayAdapter(
                this@ProjectDetailActivity,
                android.R.layout.simple_spinner_item,
                types.map { it.name }
            ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

            existing?.let {
                etName.setText(it.name); etCode.setText(it.deviceCode)
                etLocation.setText(it.location); etInstaller.setText(it.installer)
                val idx = types.indexOfFirst { t -> t.id == it.typeId }
                if (idx >= 0) spType.setSelection(idx)
            }

            AlertDialog.Builder(this@ProjectDetailActivity)
                .setTitle(if (existing == null) R.string.add_instance else R.string.edit)
                .setView(dlgView)
                .setPositiveButton(R.string.save) { _, _ ->
                    val name = etName.text?.toString()?.trim().orEmpty()
                    if (name.isEmpty()) {
                        Toast.makeText(this@ProjectDetailActivity, R.string.name_required, Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    val type = types.getOrNull(spType.selectedItemPosition) ?: return@setPositiveButton
                    lifecycleScope.launch {
                        App.repo.saveInstance(
                            CabinetInstance(
                                id = existing?.id.orEmpty(),
                                projectId = projectId,
                                typeId = type.id,
                                name = name,
                                deviceCode = etCode.text?.toString()?.trim().orEmpty(),
                                location = etLocation.text?.toString()?.trim().orEmpty(),
                                installer = etInstaller.text?.toString()?.trim().orEmpty(),
                                shortName = existing?.shortName.orEmpty(),
                                sortOrder = existing?.sortOrder ?: 0,
                                createdAt = existing?.createdAt ?: System.currentTimeMillis()
                            )
                        )
                        Toast.makeText(
                            this@ProjectDetailActivity,
                            R.string.planned_seeded_hint,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun showShortNameDialog(inst: CabinetInstance) {
        val et = EditText(this).apply {
            setText(inst.shortName)
            hint = getString(R.string.short_name_hint)
            setPadding(48, 32, 48, 16)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.short_name_title)
            .setView(et)
            .setPositiveButton(R.string.save) { _, _ ->
                val newName = et.text?.toString()?.trim().orEmpty()
                lifecycleScope.launch {
                    App.repo.saveInstance(inst.copy(shortName = newName, updatedAt = System.currentTimeMillis()))
                    if (newName.isNotEmpty()) {
                        Toast.makeText(this@ProjectDetailActivity, getString(R.string.short_name_saved, newName), Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@ProjectDetailActivity, R.string.short_name_cleared, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun editProject(p: Project) {
        val dlgView = layoutInflater.inflate(R.layout.dialog_input_multiline, null)
        val prompt = dlgView.findViewById<TextView>(R.id.tv_prompt)
        val input = dlgView.findViewById<EditText>(R.id.et_input)
        input.minLines = 3
        prompt.text = getString(R.string.project_name) + "\n" + getString(R.string.project_code) + "\n" + getString(R.string.project_remark)
        input.setText("${p.name}\n${p.code}\n${p.remark}")
        AlertDialog.Builder(this)
            .setTitle(R.string.edit_project)
            .setView(dlgView)
            .setPositiveButton(R.string.save) { _, _ ->
                val lines = input.text?.toString()?.lines().orEmpty()
                val name = lines.getOrNull(0)?.trim().orEmpty()
                if (name.isEmpty()) {
                    Toast.makeText(this, R.string.name_required, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    App.repo.saveProject(
                        p.copy(
                            name = name,
                            code = lines.getOrNull(1)?.trim().orEmpty(),
                            remark = lines.drop(2).joinToString("\n").trim()
                        )
                    )
                    this@ProjectDetailActivity.project = App.repo.getProject(projectId)
                    supportActionBar?.title = this@ProjectDetailActivity.project?.name
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun deleteProject() {
        val p = project ?: return
        lifecycleScope.launch {
            val cabinets = App.db.instanceDao().byProjectOnce(p.id).size
            DeleteSafeguard.confirmDelete(
                context = this@ProjectDetailActivity,
                title = R.string.delete,
                message = getString(R.string.warn_del_project, p.name, cabinets),
                typeName = "项目"
            ) {
                lifecycleScope.launch {
                    App.repo.deleteProject(p.id)
                    finish()
                }
            }
        }
    }

    // ---------- 适配器 ----------

    private inner class InstanceAdapter(
        private val onClick: (InstanceStatusRow) -> Unit,
        private val onLongClick: (InstanceStatusRow) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val flatData = mutableListOf<InstanceStatusRow>()
        private val groupedData = mutableListOf<List<InstanceStatusRow>>()
        var isGroupedMode = false
            private set

        /** 扁平模式：自动排序时使用 */
        fun submitFlat(list: List<InstanceStatusRow>) {
            isGroupedMode = false
            flatData.clear(); flatData.addAll(list)
            groupedData.clear()
            notifyDataSetChanged()
        }

        /** 分组模式：自定义行分配时使用 */
        fun submitGrouped(rows: List<List<InstanceStatusRow>>) {
            isGroupedMode = true
            groupedData.clear(); groupedData.addAll(rows)
            flatData.clear()
            notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int) = when {
            isGridLayout && isGroupedMode -> TYPE_GRID_GROUPED
            isGridLayout -> TYPE_GRID
            else -> TYPE_LIST
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return when (viewType) {
                TYPE_GRID -> {
                    val binding = ItemSimpleCardGridBinding.inflate(layoutInflater, parent, false)
                    GridVH(binding)
                }
                TYPE_GRID_GROUPED -> {
                    val view = layoutInflater.inflate(R.layout.item_grid_row_grouped, parent, false)
                    GridGroupedVH(view)
                }
                else -> {
                    val binding = ItemSimpleCardBinding.inflate(layoutInflater, parent, false)
                    ListVH(binding)
                }
            }
        }

        override fun getItemCount(): Int {
            return if (isGroupedMode) {
                if (isGridLayout) groupedData.size else groupedData.sumOf { it.size }
            } else {
                flatData.size
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (holder) {
                is ListVH -> {
                    val row = if (isGroupedMode) {
                        // 列表模式：将分组数据展平，按位置查找
                        var remaining = position
                        var found: InstanceStatusRow? = null
                        for (group in groupedData) {
                            if (remaining < group.size) {
                                found = group[remaining]
                                break
                            } else {
                                remaining -= group.size
                            }
                        }
                        found ?: flatData.getOrElse(position) { return }
                    } else {
                        flatData[position]
                    }
                    bindListRow(holder, row)
                }
                is GridVH -> {
                    val row = flatData[position]
                    bindGridRowFlat(holder, row)
                }
                is GridGroupedVH -> {
                    val rowItems = groupedData[position]
                    bindGridRowGrouped(holder, rowItems)
                }
            }
        }

        private fun bindListRow(h: ListVH, row: InstanceStatusRow) {
            val item = row.instance
            val displayName = if (isShortNameMode) {
                item.shortName.ifBlank { item.name }
            } else {
                item.name
            }
            h.ib.tvName.text = displayName

            val base = buildString {
                if (!isShortNameMode && item.shortName.isNotBlank()) {
                    append("精简名:${item.shortName} · ")
                }
                append(typeNames[item.typeId].orEmpty())
                if (item.deviceCode.isNotBlank()) append(" · 编号:${item.deviceCode}")
                if (item.location.isNotBlank()) append(" · ${item.location}")
                if (item.installer.isNotBlank()) append(" · 安装:${item.installer}")
            }
            val midPart = "  待测 ${row.pendingTests} · "
            val failPart = "未通过 ${row.failedTests}"
            val faultPart = " · 待处理故障 ${row.pendingFaults}"
            val ssb = SpannableStringBuilder(base).append(midPart).append(failPart).append(faultPart)
            if (row.pendingTests > 0) {
                ssb.setSpan(
                    ForegroundColorSpan(Color.parseColor("#B8860B")),
                    base.length, base.length + midPart.length,
                    SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            if (row.failedTests > 0 || row.pendingFaults > 0) {
                val start = base.length + midPart.length
                ssb.setSpan(
                    ForegroundColorSpan(Color.parseColor("#D32F2F")),
                    start, ssb.length,
                    SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            h.ib.tvSub.text = ssb
            h.ib.root.setOnClickListener { onClick(row) }
            h.ib.root.setOnLongClickListener { onLongClick(row); true }
        }

        /** 网格模式：一行内多个柜子，居中显示 */
        private fun bindGridRowGrouped(h: GridGroupedVH, rowItems: List<InstanceStatusRow>) {
            val container = h.itemView as? ViewGroup ?: return
            container.removeAllViews()

            for (item in rowItems) {
                val cardView = layoutInflater.inflate(R.layout.item_cabinet_card_in_row, container, false)
                val tvName = cardView.findViewById<TextView>(R.id.tv_card_name)
                val tvPending = cardView.findViewById<TextView>(R.id.tv_card_pending)
                val tvFaults = cardView.findViewById<TextView>(R.id.tv_card_faults)

                val displayName = if (isShortNameMode) {
                    item.instance.shortName.ifBlank { item.instance.name }
                } else {
                    item.instance.name
                }
                tvName.text = displayName
                tvPending.text = item.pendingTests.toString()
                tvFaults.text = item.pendingFaults.toString()

                cardView.setOnClickListener { onClick(item) }
                cardView.setOnLongClickListener { onLongClick(item); true }

                container.addView(cardView)
            }

            // 居中：行根布局用 android:gravity="center" 已在 XML 中设置
        }

        /** 网格模式：扁平列表每个item一行 */
        private fun bindGridRowFlat(h: GridVH, row: InstanceStatusRow) {
            val item = row.instance
            val displayName = if (isShortNameMode) {
                item.shortName.ifBlank { item.name }
            } else {
                item.name
            }
            h.ib.tvGridName.text = displayName
            h.ib.tvGridName.contentDescription = item.name
            h.ib.tvGridPending.text = row.pendingTests.toString()
            h.ib.tvGridFaults.text = row.pendingFaults.toString()

            val res = resources
            h.ib.tvGridName.textSize = 12f
            h.ib.tvGridPending.textSize = 14f
            h.ib.tvGridFaults.textSize = 14f
            h.ib.tvGridLabelPending.text = res.getString(R.string.grid_label_pending_short)
            h.ib.tvGridLabelFaults.text = res.getString(R.string.grid_label_faults_short)

            h.ib.root.setOnClickListener { onClick(row) }
            h.ib.root.setOnLongClickListener { onLongClick(row); true }
        }

        inner class ListVH(val ib: ItemSimpleCardBinding) : RecyclerView.ViewHolder(ib.root)
        inner class GridVH(val ib: ItemSimpleCardGridBinding) : RecyclerView.ViewHolder(ib.root)
        inner class GridGroupedVH(itemView: View) : RecyclerView.ViewHolder(itemView)
    }
}
