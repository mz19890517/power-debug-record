package com.fieldlog.powerdebug.ui.test

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.fieldlog.powerdebug.App
import com.fieldlog.powerdebug.R
import com.fieldlog.powerdebug.data.db.DebugLog
import com.fieldlog.powerdebug.data.db.FaultRecord
import com.fieldlog.powerdebug.data.db.PlannedItem
import com.fieldlog.powerdebug.util.DT
import com.fieldlog.powerdebug.util.SyncStore
import kotlinx.coroutines.launch

/**
 * 「开始测试」现场模式（v2.18 重构）：
 * - 所有操作（添加故障/消除故障/通过）均暂存本地状态
 * - 点击「生成日志」按钮才统一逐条独立生成日志（永不合并）
 * - 返回上级时如有未保存操作，弹窗提醒是否保存
 * - 已通过项：点文字→时间线；长按→驳回菜单
 */
class TestChecklistActivity : AppCompatActivity() {

    companion object {
        fun intent(ctx: Context, instanceId: String) =
            Intent(ctx, TestChecklistActivity::class.java)
                .putExtra("instance_id", instanceId)
    }

    private var instanceId = ""

    // 暂存状态
    private val passIds = mutableSetOf<String>()                    // 通过的测试项ID
    private val failNotes = mutableMapOf<String, String>()          // itemId -> 故障原因(换行分隔)
    private val resolvedFaultIds = mutableMapOf<String, MutableList<String>>() // itemId -> [faultId, ...]
    private val solutionsByKey = mutableMapOf<String, String>()         // faultId或symptom -> 解决方法
    private val lastReasons = mutableMapOf<String, String>()        // faultId -> symptom（DB缓存）

    private val passedItems = mutableListOf<PlannedItem>()
    private lateinit var adapter: CheckAdapter
    private lateinit var tvCount: TextView
    private lateinit var btnGenerate: Button
    private lateinit var tvInfo: TextView

    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (hasUnsavedChanges()) {
                showUnsavedDialog()
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_test_checklist)

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
            .setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        onBackPressedDispatcher.addCallback(this, backCallback)

        instanceId = intent.getStringExtra("instance_id").orEmpty()
        tvCount = findViewById(R.id.tv_count)
        btnGenerate = findViewById(R.id.btn_generate)
        tvInfo = findViewById(R.id.tv_info)
        btnGenerate.setOnClickListener { confirmGenerate() }
        tvInfo.setOnClickListener { showSwitchDebuggerDialog() }

        adapter = CheckAdapter()
        findViewById<RecyclerView>(R.id.rv_checks).apply {
            layoutManager = LinearLayoutManager(this@TestChecklistActivity)
            adapter = this@TestChecklistActivity.adapter
        }

        loadData()
    }

    private fun loadData() {
        lifecycleScope.launch {
            // 兜底自愈：先清掉「原因见日志但故障记录已不存在」的幽灵未通过项再加载
            App.repo.healGhostFailures()
            val inst = App.repo.getInstance(instanceId) ?: run { finish(); return@launch }
            supportActionBar?.title = "开始测试 · ${inst.name}"
            refreshInfo()

            val pending = App.db.plannedItemDao().pendingForTestOnce(instanceId)
            passedItems.clear()
            passedItems.addAll(
                App.db.plannedItemDao().allOfInstanceOnce(instanceId)
                    .filter { it.enabled && it.result == PlannedItem.RESULT_PASS }
            )
            // 缓存历史故障原因
            val allFaultIds = pending.map { it.faultId }
                .flatMap { id -> id.split(",").filter { it.isNotEmpty() } }
            if (allFaultIds.isNotEmpty()) {
                App.db.faultRecordDao().byIdsOnce(allFaultIds)
                    .forEach { lastReasons[it.id] = it.symptom }
            }
            adapter.submit(pending)
            pendingTotal = pending.size
            updateEmptyState(pending)
            refreshCount()
        }
    }

    private fun updateEmptyState(pending: List<PlannedItem> = emptyList()) {
        val hasData = pending.isNotEmpty() || passedItems.isNotEmpty()
        findViewById<TextView>(R.id.tv_check_empty).visibility =
            if (hasData) View.GONE else View.VISIBLE
        findViewById<RecyclerView>(R.id.rv_checks).visibility =
            if (hasData) View.VISIBLE else View.GONE
    }

    private fun refreshInfo() {
        lifecycleScope.launch {
            val inst = App.repo.getInstance(instanceId) ?: return@launch
            val dbg = SyncStore.currentDebugger(this@TestChecklistActivity)
            tvInfo.text = getString(
                R.string.start_test_hint,
                inst.name,
                inst.deviceCode.ifBlank { getString(R.string.whole_cabinet) },
                dbg.ifEmpty { getString(R.string.debugger_none_set) }
            )
        }
    }

    private fun showSwitchDebuggerDialog() {
        lifecycleScope.launch {
            val names = App.repo.debuggers().map { it.name }
            if (names.isEmpty()) {
                Toast.makeText(this@TestChecklistActivity, R.string.debugger_empty_hint, Toast.LENGTH_LONG).show()
                return@launch
            }
            val cur = SyncStore.currentDebugger(this@TestChecklistActivity)
            AlertDialog.Builder(this@TestChecklistActivity)
                .setTitle(R.string.debugger_switch_title)
                .setSingleChoiceItems(names.toTypedArray(), names.indexOf(cur)) { dlg, which ->
                    SyncStore.setCurrentDebugger(this@TestChecklistActivity, names[which])
                    refreshInfo()
                    dlg.dismiss()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun hasUnsavedChanges() =
        passIds.isNotEmpty() || failNotes.isNotEmpty() || resolvedFaultIds.isNotEmpty()

    private fun refreshCount() {
        val operated = passIds.size + failNotes.size
        tvCount.text = getString(R.string.check_count_fmt, operated, pendingTotal)
        btnGenerate.isEnabled = hasUnsavedChanges()
    }

    private var pendingTotal = 0

    // ---------- 保存/生成 ----------

    private fun showUnsavedDialog() {
        val dbg = SyncStore.currentDebugger(this)
        if (dbg.isBlank()) {
            Toast.makeText(this, R.string.debugger_pick_first, Toast.LENGTH_LONG).show()
            showSwitchDebuggerDialog()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.unsaved_changes)
            .setMessage(R.string.unsaved_changes_msg)
            .setPositiveButton(R.string.save_and_generate) { _, _ -> doGenerate(dbg) }
            .setNeutralButton(R.string.discard_changes) { _, _ -> finish() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmGenerate() {
        val dbg = SyncStore.currentDebugger(this)
        if (dbg.isBlank()) {
            Toast.makeText(this, R.string.debugger_pick_first, Toast.LENGTH_LONG).show()
            showSwitchDebuggerDialog()
            return
        }
        val count = passIds.size + failNotes.size + resolvedFaultIds.size
        AlertDialog.Builder(this)
            .setTitle(R.string.generate_log)
            .setMessage(
                getString(R.string.generate_independent_fmt, count) +
                    "\n" + getString(R.string.generate_as_debugger, dbg)
            )
            .setPositiveButton(R.string.confirm) { _, _ -> doGenerate(dbg) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun doGenerate(testerInput: String) {
        lifecycleScope.launch {
            try {
                App.repo.generateIndependentLogs(
                    instanceId = instanceId,
                    passIds = passIds.toList(),
                    failItems = failNotes.mapValues { (_, v) ->
                        v.split("\n").filter { it.isNotBlank() }
                    },
                    resolvedFaults = resolvedFaultIds.mapValues { it.value.toList() },
                    tester = testerInput.trim(),
                    actor = SyncStore.currentUser(this@TestChecklistActivity).orEmpty(),
                    solutions = solutionsByKey
                )
                Toast.makeText(this@TestChecklistActivity, R.string.log_generated, Toast.LENGTH_SHORT).show()
                finish()
            } catch (e: Exception) {
                Toast.makeText(this@TestChecklistActivity, e.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    // ---------- 对话框 ----------

    /** 统计某测试项未消除的故障数（DB + 本次新增暂存） */
    private suspend fun countUnresolvedFaults(item: PlannedItem): Int {
        var count = 0
        // 本次新增的故障（暂存文本）
        val newFaultText = failNotes[item.id]
        if (!newFaultText.isNullOrBlank()) {
            count += newFaultText.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.size
        }
        // DB中已有的未解决故障
        val resolved = resolvedFaultIds[item.id] ?: emptyList()
        if (item.faultId.isNotBlank()) {
            val faultIds = item.faultId.split(",").filter { it.isNotEmpty() && it !in resolved }
            if (faultIds.isNotEmpty()) {
                count += App.db.faultRecordDao().byIdsOnce(faultIds)
                    .count { it.status == FaultRecord.STATUS_PENDING }
            }
        }
        return count
    }

    /** 通过时有未消除故障：弹窗确认是否全部消除 */
    private fun showPassWithFaultsConfirm(item: PlannedItem, faultCount: Int) {
        AlertDialog.Builder(this)
            .setTitle(R.string.btn_pass)
            .setMessage(getString(R.string.pass_with_faults_msg, faultCount))
            .setPositiveButton(R.string.pass_resolve_all) { _, _ ->
                // 自动消除所有未解决故障
                autoResolveAllFaults(item)
                passIds.add(item.id)
                failNotes.remove(item.id)
                adapter.notifyDataSetChanged()
                refreshCount()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 自动将某测试项所有未解决故障标记为已消除 */
    private fun autoResolveAllFaults(item: PlannedItem) {
        val resolved = resolvedFaultIds.getOrPut(item.id) { mutableListOf() }
        // 将PlannedItem中关联的未解决故障全部加入resolvedFaultIds
        if (item.faultId.isNotBlank()) {
            item.faultId.split(",").filter { it.isNotEmpty() && it !in resolved }.forEach { resolved.add(it) }
        }
    }

    /** 多故障输入对话框：换行分隔多条故障 */
    private fun showMultiFaultDialog(item: PlannedItem) {
        val et = EditText(this).apply {
            hint = getString(R.string.fault_multi_input_hint)
            setText(failNotes[item.id].orEmpty())
            minLines = 3
            gravity = android.view.Gravity.TOP
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle("未通过：${item.content}")
            .setMessage(getString(R.string.fail_symptom_required))
            .setView(et)
            .setPositiveButton(R.string.confirm, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val text = et.text?.toString()?.trim().orEmpty()
                if (text.isEmpty()) {
                    Toast.makeText(this, R.string.err_symptom_empty, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val faults = text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
                if (faults.isEmpty()) {
                    Toast.makeText(this, R.string.err_symptom_empty, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                failNotes[item.id] = faults.joinToString("\n")
                passIds.remove(item.id)
                adapter.notifyDataSetChanged()
                refreshCount()
                dlg.dismiss()
            }
        }
        dlg.show()
    }

    /**
     * 故障列表对话框：显示故障条目，每条可通过/撤回。
     * 从DB读取FaultRecord，resolvedFaultIds跟踪本次会话中消除的故障。
     */
    private fun showFaultListDialog(item: PlannedItem) {
        lifecycleScope.launch {
            val dbFaults = App.repo.faultsForTestItem(instanceId, item.content, item.faultId)
            if (dbFaults.isEmpty() && failNotes[item.id].isNullOrBlank()) {
                Toast.makeText(this@TestChecklistActivity, R.string.fault_list_empty, Toast.LENGTH_SHORT).show()
                return@launch
            }

            // 合并：DB中的故障 + 本次新增的故障（暂存文本）
            val allFaults = mutableListOf<Pair<String, Boolean>>() // (symptom, isResolvedInDb)
            dbFaults.forEach { allFaults.add(it.symptom to (it.status == FaultRecord.STATUS_RESOLVED)) }
            val newFaultText = failNotes[item.id]
            if (!newFaultText.isNullOrBlank()) {
                newFaultText.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.forEach { sym ->
                    if (allFaults.none { it.first == sym }) allFaults.add(sym to false)
                }
            }

            // 当前会话中消除的故障（UI层追踪）
            val sessionResolved = mutableSetOf<String>() // symptom
            // 会话中填写的解决方法（symptom -> 文本，可空）
            val solutions = mutableMapOf<String, String>()

            val holder = arrayOfNulls<FaultListAdapter>(1)
            val rv = RecyclerView(this@TestChecklistActivity).apply {
                layoutManager = LinearLayoutManager(this@TestChecklistActivity)
                setPadding(48, 16, 48, 0)
            }

            fun displayRows(): List<Triple<String, Boolean, String>> = allFaults.map { (sym, dbResolved) ->
                val isResolved = dbResolved || sym in sessionResolved
                val sol = if (dbResolved) dbFaults.firstOrNull { it.symptom == sym }?.solution.orEmpty()
                else solutions[sym].orEmpty()
                Triple(sym, isResolved, sol)
            }

            fun refreshDialog() {
                holder[0]?.submit(displayRows())
            }

            holder[0] = FaultListAdapter(
                displayRows(),
                onPass = { position ->
                    val sym = allFaults[position].first
                    showSolutionInputDialog(sym, solutions[sym].orEmpty()) { solution ->
                        solutions[sym] = solution
                        sessionResolved.add(sym)
                        refreshDialog()
                    }
                },
                onUndo = { position ->
                    val (sym, _) = allFaults[position]
                    sessionResolved.remove(sym)
                    refreshDialog()
                }
            )
            rv.adapter = holder[0]

            AlertDialog.Builder(this@TestChecklistActivity)
                .setTitle("故障列表 · ${item.content}")
                .setView(rv)
                .setPositiveButton(R.string.confirm) { _, _ ->
                    // 确认：将本次消除的故障记录到resolvedFaultIds（DB故障记id，新故障记symptom文本）
                    if (sessionResolved.isNotEmpty()) {
                        val committedKeys = mutableListOf<String>()
                        val committedSolutions = mutableMapOf<String, String>()
                        for (sym in sessionResolved) {
                            val pendingDb = dbFaults
                                .filter { it.symptom == sym && it.status == FaultRecord.STATUS_PENDING }
                            if (pendingDb.isNotEmpty()) {
                                for (f in pendingDb) {
                                    committedKeys.add(f.id)
                                    if (solutions[sym] != null) committedSolutions[f.id] = solutions.getValue(sym)
                                }
                            } else {
                                committedKeys.add(sym)
                                if (solutions[sym] != null) committedSolutions[sym] = solutions.getValue(sym)
                            }
                        }
                        resolvedFaultIds.getOrPut(item.id) { mutableListOf() }.addAll(committedKeys)
                        solutionsByKey.putAll(committedSolutions)
                        // 最后一个故障已消除 → 本次自动标记通过并生成通过日志
                        if (allFaults.all { (sym, dbResolved) -> dbResolved || sym in sessionResolved }) {
                            passIds.add(item.id)
                        }
                        adapter.notifyDataSetChanged()
                        refreshCount()
                    }
                }
                .setNeutralButton(R.string.fault_add_new) { _, _ ->
                    showMultiFaultDialog(item)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    /** 故障「通过」弹窗：填写解决方法（可空） */
    private fun showSolutionInputDialog(symptom: String, current: String, onSave: (String) -> Unit) {
        val et = EditText(this).apply {
            hint = getString(R.string.solution_input_hint)
            setText(current)
            minLines = 2
            gravity = android.view.Gravity.TOP
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.fault_pass_solution_title, symptom))
            .setView(et)
            .setPositiveButton(R.string.confirm) { _, _ ->
                onSave(et.text?.toString()?.trim().orEmpty())
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 已通过项/日志列表：测试流程+历史故障时间线对话框；消除条目点击可编辑解决方法 */
    fun showTimelineDialog(instanceId: String, title: String) {
        lifecycleScope.launch {
            val timeline = App.repo.historyTimeline(instanceId, title)

            if (timeline.isEmpty()) {
                AlertDialog.Builder(this@TestChecklistActivity)
                    .setTitle(getString(R.string.timeline_title, title))
                    .setMessage(getString(R.string.timeline_empty))
                    .setPositiveButton(R.string.close, null)
                    .show()
                return@launch
            }

            val tv = TextView(this@TestChecklistActivity).apply {
                setPadding(48, 32, 48, 16)
                textSize = 15f
            }
            val holder = arrayOfNulls<TimelineAdapter>(1)
            val rv = RecyclerView(this@TestChecklistActivity).apply {
                layoutManager = LinearLayoutManager(this@TestChecklistActivity)
            }
            holder[0] = TimelineAdapter(
                timeline,
                onEditSolution = { log, fr ->
                    showEditSolutionDialog(log, fr) {
                        lifecycleScope.launch {
                            holder[0]?.submit(App.repo.historyTimeline(instanceId, title))
                        }
                    }
                }
            ).also { rv.adapter = it }

            val container = android.widget.LinearLayout(this@TestChecklistActivity).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                addView(tv)
                addView(rv)
            }

            val passCount = timeline.count { it.first.logType == DebugLog.LOG_TYPE_PASS }
            val faultCount = timeline.count { it.first.logType == DebugLog.LOG_TYPE_FAULT }
            val resCount = timeline.count { it.first.logType == DebugLog.LOG_TYPE_RESOLUTION }
            tv.text = buildString {
                appendLine("共 ${timeline.size} 条记录")
                if (passCount > 0) append("通过 $passCount  ")
                if (faultCount > 0) append("故障 $faultCount  ")
                if (resCount > 0) append("消除 $resCount")
                if (isNotEmpty()) appendLine()
                append("当前状态：✓ 已通过")
            }

            AlertDialog.Builder(this@TestChecklistActivity)
                .setTitle(getString(R.string.timeline_title, title))
                .setView(container)
                .setPositiveButton(R.string.close, null)
                .show()
        }
    }

    /** 时间线消除条目：编辑/添加解决方法 */
    private fun showEditSolutionDialog(log: DebugLog, fr: FaultRecord, onSaved: () -> Unit) {
        val et = EditText(this).apply {
            hint = getString(R.string.fault_solution)
            setText(fr.solution)
            minLines = 2
            gravity = android.view.Gravity.TOP
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.timeline_solution_edit_title, log.remark))
            .setView(et)
            .setPositiveButton(R.string.save) { _, _ ->
                val text = et.text?.toString()?.trim().orEmpty()
                lifecycleScope.launch {
                    App.repo.updateFaultSolution(fr.id, text)
                    onSaved()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 已通过项长按：弹出菜单 → 驳回 */
    private fun showPassedContextMenu(item: PlannedItem) {
        val options = arrayOf(getString(R.string.reject_menu_title))
        AlertDialog.Builder(this)
            .setTitle(item.content)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showRejectPassDialog(item)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 驳回通过对话框：输入故障原因 + 删除通过日志 + 重置为未测 */
    private fun showRejectPassDialog(item: PlannedItem) {
        val et = EditText(this).apply {
            hint = getString(R.string.fault_multi_input_hint)
            minLines = 2
            gravity = android.view.Gravity.TOP
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.reject_pass_title)
            .setMessage(getString(R.string.reject_pass_msg, item.content))
            .setView(et)
            .setPositiveButton(R.string.confirm) { _, _ ->
                val text = et.text?.toString()?.trim().orEmpty()
                if (text.isBlank()) {
                    Toast.makeText(this, R.string.err_symptom_empty, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val faults = text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
                if (faults.isEmpty()) {
                    Toast.makeText(this, R.string.err_symptom_empty, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    App.repo.rejectPassedItem(item)
                    failNotes[item.id] = faults.joinToString("\n")
                    passIds.remove(item.id)
                    // 重新加载数据
                    loadData()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------- 适配器 ----------

    private inner class CheckAdapter : RecyclerView.Adapter<CheckVH>() {
        private val data = mutableListOf<PlannedItem>()

        fun submit(list: List<PlannedItem>) {
            data.clear(); data.addAll(list); notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            CheckVH(LayoutInflater.from(parent.context).inflate(R.layout.item_check, parent, false))

        override fun getItemCount() = data.size + passedItems.size

        override fun onBindViewHolder(h: CheckVH, pos: Int) {
            // ===== 已通过历史项 =====
            if (pos >= data.size) {
                val p = passedItems[pos - data.size]
                h.tvText.text = p.content
                h.tvText.setTextColor(Color.parseColor("#2E7D32"))
                h.tvReason.visibility = View.VISIBLE
                h.tvReason.text =
                    if (p.doneAt > 0) getString(R.string.planned_passed_at_fmt, DT.full(p.doneAt))
                    else getString(R.string.planned_passed)
                h.btnPass.visibility = View.GONE
                h.btnFail.visibility = View.GONE
                // 点击文字 → 时间线
                h.tvText.setOnClickListener { showTimelineDialog(instanceId, p.content) }
                // 长按文字 → 驳回菜单
                h.tvText.setOnLongClickListener { showPassedContextMenu(p); true }
                h.tvText.isLongClickable = true
                return
            }

            // ===== 待测试项 =====
            val item = data[pos]
            val markedPass = item.id in passIds
            val markedFail = item.id in failNotes && item.id !in passIds

            // 故障显示逻辑
            val faultText = failNotes[item.id]
            val faultCount = faultText?.split("\n")?.count { it.isNotBlank() } ?: 0

            val faultSummary = when {
                markedFail && faultCount == 1 -> faultText?.trim() ?: ""
                markedFail && faultCount > 1 -> getString(R.string.fault_count_fmt, faultCount)
                !markedFail && item.faultId.isNotBlank() -> {
                    val ids = item.faultId.split(",").filter { it.isNotEmpty() }
                    if (ids.size == 1) "上次未通过：${lastReasons[ids[0]] ?: "原因见日志"}"
                    else "上次未通过：${ids.size}条故障"
                }
                else -> ""
            }

            h.tvText.text = item.content
            h.tvText.setTextColor(
                when {
                    markedPass -> Color.parseColor("#2E7D32")
                    markedFail -> Color.parseColor("#D32F2F")
                    item.result == PlannedItem.RESULT_FAIL -> Color.parseColor("#B8860B")
                    else -> Color.parseColor("#212121")
                }
            )

            h.tvReason.visibility = if (faultSummary.isBlank()) View.GONE else View.VISIBLE
            h.tvReason.text = faultSummary

            // 通过按钮
            h.btnPass.apply {
                visibility = View.VISIBLE
                alpha = if (markedPass) 1f else 0.55f
                text = if (markedPass) "✓ 通过" else getString(R.string.btn_pass)
                setOnClickListener {
                    if (markedPass) {
                        passIds.remove(item.id)
                        notifyDataSetChanged(); refreshCount()
                    } else {
                        lifecycleScope.launch {
                            val unresolvedCount = countUnresolvedFaults(item)
                            if (unresolvedCount > 0) {
                                showPassWithFaultsConfirm(item, unresolvedCount)
                            } else {
                                passIds.add(item.id)
                                failNotes.remove(item.id)
                                notifyDataSetChanged(); refreshCount()
                            }
                        }
                    }
                }
            }

            // 问题按钮
            h.btnFail.apply {
                visibility = View.VISIBLE
                alpha = if (markedFail) 1f else 0.55f
                text = if (markedFail) "✗ 已记" else getString(R.string.btn_fail)
                setOnClickListener {
                    if (markedFail || item.faultId.isNotBlank()) {
                        showFaultListDialog(item)
                    } else {
                        showMultiFaultDialog(item)
                    }
                }
            }

            // 点击文字：有故障项→故障列表；上次未通过项→故障列表
            h.tvText.setOnClickListener {
                when {
                    markedFail -> showFaultListDialog(item)
                    item.faultId.isNotBlank() -> showFaultListDialog(item)
                }
            }
        }
    }
}

private class CheckVH(v: View) : RecyclerView.ViewHolder(v) {
    val tvText: TextView = v.findViewById(R.id.tv_text)
    val tvReason: TextView = v.findViewById(R.id.tv_reason)
    val btnPass: Button = v.findViewById(R.id.btn_pass)
    val btnFail: Button = v.findViewById(R.id.btn_fail)
}

/**
 * 故障列表适配器：每条故障可点击通过/撤回。
 * 已解决的项显示删除线 + 绿色"已处理"（含解决方法）+ 撤回按钮。
 */
private class FaultListAdapter(
    private var data: List<Triple<String, Boolean, String>>, // (symptom, isResolved, solution)
    private val onPass: (Int) -> Unit,
    private val onUndo: (Int) -> Unit
) : RecyclerView.Adapter<FaultListAdapter.VH>() {

    fun submit(newData: List<Triple<String, Boolean, String>>) {
        data = newData
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvFault: TextView = v.findViewById(R.id.tv_fault_text)
        val btnAction: Button = v.findViewById(R.id.btn_fault_pass)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_fault_list, parent, false)
        return VH(v)
    }

    override fun getItemCount() = data.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val (symptom, isResolved, solution) = data[pos]
        val ctx = h.itemView.context
        h.tvFault.text = buildString {
            append(symptom)
            if (isResolved) {
                append("  ").append(ctx.getString(R.string.fault_resolved_label))
                if (solution.isNotBlank()) append(ctx.getString(R.string.timeline_solution_fmt, solution))
            }
        }
        h.tvFault.setTextColor(if (isResolved) Color.parseColor("#2E7D32") else Color.parseColor("#212121"))
        if (isResolved) {
            h.tvFault.paintFlags = h.tvFault.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        } else {
            h.tvFault.paintFlags = h.tvFault.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
        }
        h.btnAction.text = if (isResolved) ctx.getString(R.string.undo) else ctx.getString(R.string.fault_pass)
        h.btnAction.setTextColor(if (isResolved) Color.parseColor("#757575") else Color.parseColor("#2E7D32"))
        h.btnAction.setOnClickListener { if (isResolved) onUndo(pos) else onPass(pos) }
    }
}

/** 时间线适配器：显示测试流程+故障记录；消除条目可点击编辑解决方法 */
private class TimelineAdapter(
    private var data: List<Triple<DebugLog, String, List<FaultRecord>>>,
    private val onEditSolution: ((DebugLog, FaultRecord) -> Unit)? = null
) : RecyclerView.Adapter<TimelineAdapter.VH>() {

    fun submit(newData: List<Triple<DebugLog, String, List<FaultRecord>>>) {
        data = newData
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvLog: TextView = v.findViewById(R.id.tv_timeline_log)
        val tvFaults: TextView = v.findViewById(R.id.tv_timeline_faults)
        val tvPass: TextView = v.findViewById(R.id.tv_timeline_pass)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_timeline, parent, false)
        return VH(v)
    }

    override fun getItemCount() = data.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val (log, remark, faults) = data[pos]
        val ctx = h.itemView.context
        h.tvLog.text = ctx.getString(R.string.timeline_log_fmt, DT.full(log.createdAt), log.tester)

        when (log.logType) {
            DebugLog.LOG_TYPE_FAULT -> {
                h.tvFaults.visibility = View.VISIBLE
                h.tvFaults.text = ctx.getString(R.string.timeline_fault_fmt, remark)
                h.tvFaults.setTextColor(Color.parseColor("#D32F2F"))
                h.tvPass.visibility = View.GONE
            }
            DebugLog.LOG_TYPE_RESOLUTION -> {
                h.tvFaults.visibility = View.VISIBLE
                val sol = faults.firstOrNull()?.solution.orEmpty()
                h.tvFaults.text = buildString {
                    append(ctx.getString(R.string.timeline_fault_fmt, remark))
                    append("  ").append(ctx.getString(R.string.fault_resolved_label))
                    if (sol.isNotBlank()) append(ctx.getString(R.string.timeline_solution_fmt, sol))
                }
                h.tvFaults.setTextColor(Color.parseColor("#2E7D32"))
                h.tvPass.visibility = View.GONE
                val fr = faults.firstOrNull()
                val edit = onEditSolution
                if (edit != null && fr != null) {
                    h.itemView.setOnClickListener { edit(log, fr) }
                }
            }
            else -> {
                h.tvFaults.visibility = View.GONE
                h.tvPass.visibility = View.VISIBLE
                h.tvPass.text = ctx.getString(R.string.timeline_pass)
            }
        }
    }
}
