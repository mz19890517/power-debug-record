package com.fieldlog.powerdebug.ui.log

import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.fieldlog.powerdebug.App
import com.fieldlog.powerdebug.R
import com.fieldlog.powerdebug.data.LogDeleteMode
import com.fieldlog.powerdebug.data.db.CabinetInstance
import com.fieldlog.powerdebug.data.db.CabinetType
import com.fieldlog.powerdebug.data.db.DebugLog
import com.fieldlog.powerdebug.data.db.LogListItem
import com.fieldlog.powerdebug.data.db.Project
import com.fieldlog.powerdebug.databinding.FragmentLogListBinding
import com.fieldlog.powerdebug.databinding.ItemLogBinding
import com.fieldlog.powerdebug.util.DT
import kotlinx.coroutines.launch

class LogListFragment : Fragment() {

    private var _b: FragmentLogListBinding? = null
    private val b get() = _b!!

    private lateinit var adapter: LogAdapter

    private var projects: List<Project> = emptyList()
    private var types: List<CabinetType> = emptyList()
    private var instances: List<CabinetInstance> = emptyList()

    private var selProjectId = ""
    private var selTypeId = ""
    private var selInstanceId = ""
    private var selStatus = -1

    private val handler = Handler(Looper.getMainLooper())
    private val searchRunnable = Runnable { reload() }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _b = FragmentLogListBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = LogAdapter(
            onClick = { showTimelineFromLog(it) },
            onLongClick = { confirmDelete(it) }
        )
        b.rvLogs.layoutManager = LinearLayoutManager(requireContext())
        b.rvLogs.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            App.repo.watchProjects().collect {
                projects = it
                refreshProjectSpinner()
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            App.repo.watchTypes().collect {
                types = it
                refreshTypeSpinner()
            }
        }

        b.spStatus.adapter = ArrayAdapter(
            requireContext(),
            R.layout.spinner_item_small,
            arrayOf(
                getString(R.string.filter_all_status),
                getString(R.string.filter_pending),
                getString(R.string.filter_resolved)
            )
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        b.spStatus.onItemSelectedListener = selListener { pos ->
            selStatus = intArrayOf(-1, 0, 1)[pos]
            reload()
        }

        b.etCircuitFilter.addTextChangedListener(debounceWatcher)
        b.etTextSearch.addTextChangedListener(debounceWatcher)

        viewLifecycleOwner.lifecycleScope.launch { reloadInstances() }
    }

    override fun onResume() {
        super.onResume()
        if (::adapter.isInitialized) reload()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }

    // ---------- 时间线（点击日志条目） ----------

    private fun showTimelineFromLog(item: LogListItem) {
        lifecycleScope.launch {
            val timeline = App.repo.historyTimeline(item.log.instanceId, item.log.testContent)

            if (timeline.isEmpty()) {
                AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.timeline_title, item.log.testContent))
                    .setMessage(getString(R.string.timeline_empty))
                    .setPositiveButton(R.string.close, null)
                    .show()
                return@launch
            }

            val header = TextView(requireContext()).apply {
                setPadding(48, 32, 48, 16)
                textSize = 15f
            }
            val holder = arrayOfNulls<TimelineLogAdapter>(1)
            val rv = RecyclerView(requireContext()).apply {
                layoutManager = LinearLayoutManager(requireContext())
            }
            holder[0] = TimelineLogAdapter(
                timeline,
                onEditSolution = { log, fr ->
                    showEditSolutionDialog(log, fr) {
                        viewLifecycleOwner.lifecycleScope.launch {
                            holder[0]?.submit(App.repo.historyTimeline(item.log.instanceId, item.log.testContent))
                        }
                    }
                }
            ).also { rv.adapter = it }

            val container = android.widget.LinearLayout(requireContext()).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                addView(header)
                addView(rv)
            }

            val passCount = timeline.count { it.first.logType == DebugLog.LOG_TYPE_PASS }
            val faultCount = timeline.count { it.first.logType == DebugLog.LOG_TYPE_FAULT }
            val resCount = timeline.count { it.first.logType == DebugLog.LOG_TYPE_RESOLUTION }
            header.text = buildString {
                appendLine("共 ${timeline.size} 条记录")
                if (passCount > 0) append("通过 $passCount  ")
                if (faultCount > 0) append("故障 $faultCount  ")
                if (resCount > 0) append("消除 $resCount")
            }

            AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.timeline_title, item.log.testContent))
                .setView(container)
                .setPositiveButton(R.string.close, null)
                .show()
        }
    }

    /** 时间线消除条目：编辑/添加解决方法（同步到测试页同时间线） */
    private fun showEditSolutionDialog(
        log: com.fieldlog.powerdebug.data.db.DebugLog,
        fr: com.fieldlog.powerdebug.data.db.FaultRecord,
        onSaved: () -> Unit
    ) {
        val et = EditText(requireContext()).apply {
            hint = getString(R.string.fault_solution)
            setText(fr.solution)
            minLines = 2
            gravity = android.view.Gravity.TOP
        }
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.timeline_solution_edit_title, log.remark))
            .setView(et)
            .setPositiveButton(R.string.save) { _, _ ->
                val text = et.text?.toString()?.trim().orEmpty()
                viewLifecycleOwner.lifecycleScope.launch {
                    App.repo.updateFaultSolution(fr.id, text)
                    onSaved()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------- 删除逻辑（按logType分支） ----------

    private fun confirmDelete(item: LogListItem) {
        when (item.log.logType) {
            DebugLog.LOG_TYPE_PASS -> confirmDeletePassLog(item)
            DebugLog.LOG_TYPE_FAULT -> confirmDeleteFaultLog(item)
            DebugLog.LOG_TYPE_RESOLUTION -> confirmDeleteResolutionLog(item)
        }
    }

    /** 通过日志：现有三选项（重测/连项删除） */
    private fun confirmDeletePassLog(item: LogListItem) {
        viewLifecycleOwner.lifecycleScope.launch {
            val linked = App.repo.linkedPlannedOfLog(item.log.id)
            if (linked.isEmpty()) {
                com.fieldlog.powerdebug.util.DeleteSafeguard.confirmDelete(
                    context = requireContext(),
                    title = R.string.delete,
                    message = "删除「${item.instanceName} · ${item.log.circuit.ifEmpty { getString(R.string.whole_cabinet) }}」这条通过日志？",
                    typeName = "日志"
                ) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        App.repo.deleteLog(item.log.id, LogDeleteMode.RESTORE_PLANNED)
                        reload()
                    }
                }
            } else {
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.delete)
                    .setIcon(android.R.drawable.ic_dialog_alert)
                    .setMessage(
                        "删除「${item.instanceName} · ${item.log.circuit.ifEmpty { getString(R.string.whole_cabinet) }}」这条通过日志？\n" +
                            "它完成了 ${linked.size} 项预选待测项目。"
                    )
                    .setPositiveButton(R.string.del_log_retest) { _, _ ->
                        com.fieldlog.powerdebug.util.DeleteSafeguard.confirmDelete(
                            context = requireContext(),
                            title = R.string.delete,
                            message = "确认删除并恢复预选待测项？",
                            typeName = "日志"
                        ) {
                            viewLifecycleOwner.lifecycleScope.launch {
                                App.repo.deleteLog(item.log.id, LogDeleteMode.RESTORE_PLANNED)
                                reload()
                            }
                        }
                    }
                    .setNeutralButton(R.string.del_log_purge) { _, _ ->
                        com.fieldlog.powerdebug.util.DeleteSafeguard.confirmDelete(
                            context = requireContext(),
                            title = R.string.delete,
                            message = "确认删除并连项删除预选待测项？",
                            typeName = "日志"
                        ) {
                            viewLifecycleOwner.lifecycleScope.launch {
                                App.repo.deleteLog(item.log.id, LogDeleteMode.PURGE_PLANNED)
                                reload()
                            }
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }
    }

    /** 故障日志：两选项 */
    private fun confirmDeleteFaultLog(item: LogListItem) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.delete)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setMessage("删除「${item.log.testContent}」的故障日志？")
            .setPositiveButton(R.string.del_fault_and_resolution) { _, _ ->
                com.fieldlog.powerdebug.util.DeleteSafeguard.confirmDelete(
                    context = requireContext(),
                    title = R.string.delete,
                    message = "确认删除此故障生成日志和关联消除日志？",
                    typeName = "日志"
                ) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        App.repo.deleteLog(item.log.id, LogDeleteMode.DELETE_FAULT)
                        reload()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 消除日志：两选项 */
    private fun confirmDeleteResolutionLog(item: LogListItem) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.delete)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setMessage("删除「${item.log.testContent}」的消除日志？")
            .setPositiveButton(R.string.del_resolution_reject) { _, _ ->
                com.fieldlog.powerdebug.util.DeleteSafeguard.confirmDelete(
                    context = requireContext(),
                    title = R.string.delete,
                    message = "确认删除消除日志并驳回？故障将恢复为待处理。",
                    typeName = "日志"
                ) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        App.repo.deleteLog(item.log.id, LogDeleteMode.DELETE_RESOLUTION)
                        reload()
                    }
                }
            }
            .setNeutralButton(R.string.del_fault_and_resolution) { _, _ ->
                com.fieldlog.powerdebug.util.DeleteSafeguard.confirmDelete(
                    context = requireContext(),
                    title = R.string.delete,
                    message = "确认删除此故障的生成日志和消除日志？",
                    typeName = "日志"
                ) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        App.repo.deleteLog(item.log.id, LogDeleteMode.DELETE_RESOLUTION_PURGE)
                        reload()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------- 查询 ----------

    private fun reload() {
        viewLifecycleOwner.lifecycleScope.launch {
            val list = try {
                App.repo.searchLogs(
                    projectId = selProjectId,
                    typeId = selTypeId,
                    instanceId = selInstanceId,
                    status = selStatus,
                    circuit = b.etCircuitFilter.text?.toString()?.trim().orEmpty(),
                    q = b.etTextSearch.text?.toString()?.trim().orEmpty()
                )
            } catch (e: Exception) {
                emptyList<LogListItem>()
            }
            adapter.submit(list)
            b.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    // ---------- 筛选联动 ----------

    private fun refreshProjectSpinner() {
        val labels = mutableListOf(getString(R.string.filter_all_projects))
        projects.forEach { labels.add(it.name) }
        b.spProject.bind(labels) { pos ->
            val newId = projects.getOrNull(pos - 1)?.id ?: ""
            if (newId != selProjectId) {
                selProjectId = newId
                selInstanceId = ""
                viewLifecycleOwner.lifecycleScope.launch { reloadInstances() }
            }
        }
        selectSpinner(b.spProject, projects.indexOfFirst { it.id == selProjectId } + 1)
    }

    private fun refreshTypeSpinner() {
        val labels = mutableListOf(getString(R.string.filter_all_types))
        types.forEach { labels.add(it.name) }
        b.spType.bind(labels) { pos ->
            val newId = types.getOrNull(pos - 1)?.id ?: ""
            if (newId != selTypeId) {
                selTypeId = newId
                selInstanceId = ""
                viewLifecycleOwner.lifecycleScope.launch { reloadInstances() }
            }
        }
        selectSpinner(b.spType, types.indexOfFirst { it.id == selTypeId } + 1)
    }

    private suspend fun reloadInstances() {
        instances = App.db.instanceDao().byProjectAndTypeOnce(selProjectId, selTypeId)
        val labels = mutableListOf(getString(R.string.filter_all_instances))
        instances.forEach { labels.add(it.name) }
        b.spInstance.bind(labels) { pos ->
            val newId = instances.getOrNull(pos - 1)?.id ?: ""
            if (newId != selInstanceId) {
                selInstanceId = newId
                reload()
            }
        }
        selectSpinner(b.spInstance, instances.indexOfFirst { it.id == selInstanceId } + 1)
        reload()
    }

    // ---------- Spinner 工具 ----------

    private fun Spinner.bind(items: List<String>, onSel: (Int) -> Unit) {
        tag = true
        adapter = ArrayAdapter(requireContext(), R.layout.spinner_item_small, items).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (tag == true) return
                onSel(pos)
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun selectSpinner(sp: Spinner, pos: Int) {
        sp.tag = true
        if (pos >= 0 && sp.selectedItemPosition != pos) sp.setSelection(pos, false)
        else sp.tag = false
        sp.post { sp.tag = false }
    }

    private fun selListener(onSel: (Int) -> Unit): AdapterView.OnItemSelectedListener =
        object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = onSel(pos)
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

    private val debounceWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
        override fun onTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
        override fun afterTextChanged(s: Editable?) {
            handler.removeCallbacks(searchRunnable)
            handler.postDelayed(searchRunnable, 350)
        }
    }
}

class LogAdapter(
    private val onClick: (LogListItem) -> Unit,
    private val onLongClick: (LogListItem) -> Unit
) : RecyclerView.Adapter<LogAdapter.VH>() {

    private val data = mutableListOf<LogListItem>()

    fun submit(list: List<LogListItem>) {
        data.clear()
        data.addAll(list)
        notifyDataSetChanged()
    }

    class VH(val ib: ItemLogBinding) : RecyclerView.ViewHolder(ib.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemLogBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = data.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val item = data[pos]
        val ctx = h.ib.root.context
        val circuitTxt = item.log.circuit.ifEmpty { ctx.getString(R.string.whole_cabinet) }
        h.ib.tvTitle.text = "${item.projectName} · ${item.instanceName} · $circuitTxt"
        h.ib.tvDate.text = DT.full(item.log.createdAt)

        h.ib.tvContent.text = item.log.testContent
        val tester = item.log.tester.takeIf { it.isNotBlank() }?.let { " · 测试:$it" }.orEmpty()
        val author = item.log.createdBy.takeIf { it.isNotBlank() && it != item.log.tester }
            ?.let { " · 记录:$it" }.orEmpty()
        h.ib.tvFooter.text = "${item.projectName} · ${item.typeName}$tester$author"

        // 第五行：根据logType显示
        when (item.log.logType) {
            DebugLog.LOG_TYPE_PASS -> {
                h.ib.tvStatus.visibility = View.VISIBLE
                h.ib.tvStatus.text = ctx.getString(R.string.log_type_pass)
                h.ib.tvStatus.setTextColor(ctx.getColor(R.color.primary))
            }
            DebugLog.LOG_TYPE_FAULT -> {
                h.ib.tvStatus.visibility = View.VISIBLE
                h.ib.tvStatus.text = item.log.remark
                h.ib.tvStatus.setTextColor(ctx.getColor(R.color.danger))
            }
            DebugLog.LOG_TYPE_RESOLUTION -> {
                h.ib.tvStatus.visibility = View.VISIBLE
                h.ib.tvStatus.text = "${item.log.remark}  ${ctx.getString(R.string.fault_resolved_label)}"
                h.ib.tvStatus.setTextColor(ctx.getColor(R.color.primary))
            }
            else -> h.ib.tvStatus.visibility = View.GONE
        }

        h.ib.root.setOnClickListener { onClick(item) }
        h.ib.root.setOnLongClickListener { onLongClick(item); true }
    }
}

/** 时间线适配器（日志列表用）；消除条目可点击编辑解决方法 */
private class TimelineLogAdapter(
    private var data: List<Triple<com.fieldlog.powerdebug.data.db.DebugLog, String, List<com.fieldlog.powerdebug.data.db.FaultRecord>>>,
    private val onEditSolution: ((com.fieldlog.powerdebug.data.db.DebugLog, com.fieldlog.powerdebug.data.db.FaultRecord) -> Unit)? = null
) : RecyclerView.Adapter<TimelineLogAdapter.VH>() {

    fun submit(newData: List<Triple<com.fieldlog.powerdebug.data.db.DebugLog, String, List<com.fieldlog.powerdebug.data.db.FaultRecord>>>) {
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
                h.tvFaults.setTextColor(ctx.getColor(R.color.danger))
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
                h.tvFaults.setTextColor(ctx.getColor(R.color.primary))
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
