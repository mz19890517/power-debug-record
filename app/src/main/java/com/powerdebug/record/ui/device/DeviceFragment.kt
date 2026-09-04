package com.powerdebug.record.ui.device

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.powerdebug.record.App
import com.powerdebug.record.R
import com.powerdebug.record.core.ExportSheets
import com.powerdebug.record.core.XlsxWriter
import com.powerdebug.record.data.ExportFilter
import com.powerdebug.record.data.db.ProjectListItem
import com.powerdebug.record.ui.FilterDialogHelper
import com.powerdebug.record.data.db.TypeListItem
import com.powerdebug.record.databinding.FragmentDeviceBinding
import com.powerdebug.record.databinding.ItemSimpleCardBinding
import com.powerdebug.record.util.DT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DeviceFragment : Fragment() {

    private var _b: FragmentDeviceBinding? = null
    private val b get() = _b!!

    private lateinit var projectAdapter: ProjectAdapter
    private lateinit var typeAdapter: TypeAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _b = FragmentDeviceBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        projectAdapter = ProjectAdapter(
            onClick = { startActivity(ProjectDetailActivity.intent(requireContext(), it.project.id)) },
            onLongClick = { showProjectMenu(it) }
        )
        b.rvProjects.layoutManager = LinearLayoutManager(requireContext())
        b.rvProjects.adapter = projectAdapter

        typeAdapter = TypeAdapter(
            onClick = { startActivity(TypeDetailActivity.intent(requireContext(), it.type.id)) },
            onLongClick = { showTypeMenu(it) }
        )
        b.rvTypes.layoutManager = LinearLayoutManager(requireContext())
        b.rvTypes.adapter = typeAdapter

        viewLifecycleOwner.lifecycleScope.launch {
            App.repo.watchProjectItems().collect {
                projectAdapter.submit(it)
                b.tvEmptyProjects.visibility = if (it.isEmpty()) View.VISIBLE else View.GONE
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            App.repo.watchTypeItems().collect {
                typeAdapter.submit(it)
                b.tvEmptyTypes.visibility = if (it.isEmpty()) View.VISIBLE else View.GONE
            }
        }

        b.btnAddProject.setOnClickListener { editProjectDialog(null) }
        b.btnAddType.setOnClickListener { editTypeDialog(null) }

        b.tabs.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab) {
                val showProjects = tab.position == 0
                b.pageProjects.visibility = if (showProjects) View.VISIBLE else View.GONE
                b.pageTypes.visibility = if (showProjects) View.GONE else View.VISIBLE
            }

            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab) {}
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab) {}
        })
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }

    // ---------- 项目 ----------

    private fun showProjectMenu(item: ProjectListItem) {
        AlertDialog.Builder(requireContext())
            .setTitle(item.project.name)
            .setItems(
                arrayOf(
                    getString(R.string.edit_project),
                    getString(R.string.menu_add_template),
                    getString(R.string.menu_export_project),
                    getString(R.string.delete)
                )
            ) { _, which ->
                when (which) {
                    0 -> editProjectDialog(item.project)
                    1 -> saveAsTemplate(item)
                    2 -> requestExportProject(item)
                    3 -> confirmDeleteProject(item)
                }
            }
            .show()
    }

    /**
     * 「加入常用模板」：把本项目各柜子当前启用的预选待测项沉淀为
     * 各自类型的候选池条目（同名自动跳过），供以后同类柜子快速勾选；
     * 候选池按使用频次排序，用得越多排得越靠前。
     */
    private fun saveAsTemplate(item: ProjectListItem) {
        viewLifecycleOwner.lifecycleScope.launch {
            val added = App.repo.saveProjectAsTemplate(item.project.id)
            val msg =
                if (added == 0) getString(R.string.template_none)
                else getString(R.string.template_done_fmt, added, item.project.name)
            Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
        }
    }

    // ---------- 项目定向导出 ----------

    private var exportProjectId = ""
    private var currentProjectFilter = ExportFilter()
    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument(XLSX_MIME)
    ) { uri -> uri?.let { doExportProject(it) } }

    private fun requestExportProject(item: ProjectListItem) {
        exportProjectId = item.project.id
        FilterDialogHelper.show(requireContext(), viewLifecycleOwner.lifecycleScope, currentProjectFilter) { filter ->
            currentProjectFilter = filter
            exportLauncher.launch("电源柜调试日志_${item.project.name}_${DT.fileStamp()}.xlsx")
        }
    }

    private fun doExportProject(uri: android.net.Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val (logs, faults) = App.repo.collectExportOf(projectId = exportProjectId, filter = currentProjectFilter)
                withContext(Dispatchers.IO) {
                    requireContext().contentResolver.openOutputStream(uri)?.use { out ->
                        XlsxWriter.write(out, ExportSheets.build(
                            requireContext(), logs, faults,
                            logColumns = currentProjectFilter.logColumns,
                            faultColumns = currentProjectFilter.faultColumns
                        ))
                    } ?: throw IllegalStateException("无法打开输出流")
                }
                Toast.makeText(
                    requireContext(),
                    getString(R.string.export_ok, uri.lastPathSegment ?: ""),
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.op_failed, e.message ?: e.javaClass.simpleName),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun editProjectDialog(existing: com.powerdebug.record.data.db.Project?) {
        val dlgView = layoutInflater.inflate(R.layout.dialog_project_edit, null)
        val etName = dlgView.findViewById<android.widget.EditText>(R.id.et_name)
        val etCode = dlgView.findViewById<android.widget.EditText>(R.id.et_code)
        val etRemark = dlgView.findViewById<android.widget.EditText>(R.id.et_remark)
        val tvStart = dlgView.findViewById<android.widget.TextView>(R.id.tv_debug_start)
        val tvEnd = dlgView.findViewById<android.widget.TextView>(R.id.tv_debug_end)
        val btnClear = dlgView.findViewById<android.widget.TextView>(R.id.btn_clear_debug_end)

        // 选中的日期（中央可变状态）；新建默认为创建时刻(now)，即起始=创建日期
        var startMs = if (existing == null) System.currentTimeMillis() else existing.debugStartDate
        var endMs = existing?.debugEndDate ?: 0L

        fun refreshDateLabels() {
            tvStart.text = getString(R.string.debug_start_label) + "：点击设置 → " + (if (startMs > 0) DT.dateOnly(startMs) else getString(R.string.debug_end_unset))
            tvEnd.text = getString(R.string.debug_end_label) + "：点击设置 → " + (if (endMs > 0) DT.dateOnly(endMs) else getString(R.string.debug_end_unset))
            btnClear.visibility = if (endMs > 0) android.view.View.VISIBLE else android.view.View.GONE
        }

        if (existing != null) etName.setText(existing.name)
        if (existing != null) etCode.setText(existing.code)
        if (existing != null) etRemark.setText(existing.remark)
        refreshDateLabels()

        tvStart.setOnClickListener {
            DT.pickDate(requireContext(), startMs) { tvStart.post { startMs = it; refreshDateLabels() } }
        }
        tvEnd.setOnClickListener {
            DT.pickDate(requireContext(), endMs) { tvEnd.post { endMs = it; refreshDateLabels() } }
        }
        btnClear.setOnClickListener { endMs = 0L; refreshDateLabels() }

        AlertDialog.Builder(requireContext())
            .setTitle(if (existing == null) R.string.new_project else R.string.edit_project)
            .setView(dlgView)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = etName.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) {
                    android.widget.Toast.makeText(requireContext(), R.string.name_required, android.widget.Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val code = etCode.text?.toString()?.trim().orEmpty()
                val remark = etRemark.text?.toString()?.trim().orEmpty()
                viewLifecycleOwner.lifecycleScope.launch {
                    App.repo.saveProject(
                        com.powerdebug.record.data.db.Project(
                            id = existing?.id.orEmpty(), name = name,
                            code = code, remark = remark,
                            debugStartDate = startMs, debugEndDate = endMs,
                            createdAt = existing?.createdAt ?: System.currentTimeMillis()
                        )
                    )
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteProject(item: ProjectListItem) {
        com.powerdebug.record.util.DeleteSafeguard.confirmDelete(
            context = requireContext(),
            title = R.string.delete,
            message = getString(R.string.warn_del_project, item.project.name, item.cabinetCount),
            typeName = "项目"
        ) {
            viewLifecycleOwner.lifecycleScope.launch { App.repo.deleteProject(item.project.id) }
        }
    }

    // ---------- 类型 ----------

    private fun showTypeMenu(item: TypeListItem) {
        AlertDialog.Builder(requireContext())
            .setTitle(item.type.name)
            .setItems(arrayOf(getString(R.string.edit_type), getString(R.string.delete))) { _, which ->
                when (which) {
                    0 -> editTypeDialog(item.type)
                    1 -> confirmDeleteType(item)
                }
            }
            .show()
    }

    private fun editTypeDialog(existing: com.powerdebug.record.data.db.CabinetType?) {
        val dlgView = layoutInflater.inflate(R.layout.dialog_input_multiline, null)
        val prompt = dlgView.findViewById<android.widget.TextView>(R.id.tv_prompt)
        val input = dlgView.findViewById<android.widget.EditText>(R.id.et_input)
        input.minLines = 2
        prompt.text = getString(R.string.type_name) + "\n" + getString(R.string.type_remark_hint)
        existing?.let { input.setText("${it.name}\n${it.remark}") }
        AlertDialog.Builder(requireContext())
            .setTitle(if (existing == null) R.string.new_type else R.string.edit_type)
            .setView(dlgView)
            .setPositiveButton(R.string.save) { _, _ ->
                val lines = input.text?.toString()?.lines().orEmpty()
                val name = lines.getOrNull(0)?.trim().orEmpty()
                if (name.isEmpty()) {
                    android.widget.Toast.makeText(requireContext(), R.string.name_required, android.widget.Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val remark = lines.drop(1).joinToString("\n").trim()
                viewLifecycleOwner.lifecycleScope.launch {
                    App.repo.saveType(
                        com.powerdebug.record.data.db.CabinetType(
                            id = existing?.id.orEmpty(), name = name, remark = remark,
                            createdAt = existing?.createdAt ?: System.currentTimeMillis()
                        )
                    )
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteType(item: TypeListItem) {
        com.powerdebug.record.util.DeleteSafeguard.confirmDelete(
            context = requireContext(),
            title = R.string.delete,
            message = getString(R.string.warn_del_type, item.type.name, item.instanceCount),
            typeName = "类型"
        ) {
            viewLifecycleOwner.lifecycleScope.launch { App.repo.deleteType(item.type.id) }
        }
    }

    companion object {
        private const val XLSX_MIME =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    }
}

// ---------- 适配器 ----------

private class ProjectAdapter(
    private val onClick: (ProjectListItem) -> Unit,
    private val onLongClick: (ProjectListItem) -> Unit
) : RecyclerView.Adapter<ProjectAdapter.VH>() {

    private val data = mutableListOf<ProjectListItem>()

    fun submit(list: List<ProjectListItem>) {
        data.clear(); data.addAll(list); notifyDataSetChanged()
    }

    class VH(val ib: ItemSimpleCardBinding) : RecyclerView.ViewHolder(ib.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemSimpleCardBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = data.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val item = data[pos]
        h.ib.tvName.text = item.project.name
        h.ib.tvSub.text = buildString {
            append(h.ib.root.context.getString(R.string.cabinets_fmt, item.cabinetCount, item.logCount))
            if (item.project.code.isNotBlank()) append(" · ${item.project.code}")
            if (item.pendingTests > 0 || item.failedTests > 0 || item.pendingFaults > 0)
                append(" · 待测${item.pendingTests}·未通过${item.failedTests}·待处理${item.pendingFaults}")
            if (item.project.debugStartDate > 0 || item.project.debugEndDate > 0) {
                val start = DT.dateOnly(item.project.debugStartDate)
                val end = if (item.project.debugEndDate > 0) DT.dateOnly(item.project.debugEndDate)
                          else h.ib.root.context.getString(R.string.debug_end_unset)
                append(" · ${h.ib.root.context.getString(R.string.debug_period_fmt, start, end)}")
            }
            if (item.project.remark.isNotBlank()) append(" · ${item.project.remark}")
        }
        h.ib.root.setOnClickListener { onClick(item) }
        h.ib.root.setOnLongClickListener { onLongClick(item); true }
    }
}

private class TypeAdapter(
    private val onClick: (TypeListItem) -> Unit,
    private val onLongClick: (TypeListItem) -> Unit
) : RecyclerView.Adapter<TypeAdapter.VH>() {

    private val data = mutableListOf<TypeListItem>()

    fun submit(list: List<TypeListItem>) {
        data.clear(); data.addAll(list); notifyDataSetChanged()
    }

    class VH(val ib: ItemSimpleCardBinding) : RecyclerView.ViewHolder(ib.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemSimpleCardBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = data.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val item = data[pos]
        val ctx = h.ib.root.context
        h.ib.tvName.text = item.type.name
        h.ib.tvSub.text = ctx.getString(R.string.type_stat_fmt, item.itemCount, item.instanceCount)
            .let { s -> if (item.type.remark.isBlank()) s else "$s · ${item.type.remark}" }
        h.ib.root.setOnClickListener { onClick(item) }
        h.ib.root.setOnLongClickListener { onLongClick(item); true }
    }
}
