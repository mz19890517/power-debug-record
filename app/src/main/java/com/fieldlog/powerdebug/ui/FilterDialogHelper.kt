package com.fieldlog.powerdebug.ui

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Context
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import com.fieldlog.powerdebug.App
import com.fieldlog.powerdebug.R
import com.fieldlog.powerdebug.core.ExportSheets
import com.fieldlog.powerdebug.data.ExportFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * 导出筛选弹窗辅助工具。
 * 弹窗关闭后通过 [onFilter] 回调 ExportFilter。
 */
object FilterDialogHelper {

    fun show(
        ctx: Context,
        scope: CoroutineScope,
        initialFilter: ExportFilter = ExportFilter(),
        onFilter: (ExportFilter) -> Unit
    ) {
        val inflater = android.view.LayoutInflater.from(ctx)
        val view = inflater.inflate(R.layout.dialog_export_filter, null)

        val rgStatus = view.findViewById<RadioGroup>(R.id.rgStatus)
        val tvTestersLabel = view.findViewById<TextView>(R.id.tvTestersLabel)
        val llTesters = view.findViewById<LinearLayout>(R.id.llTesters)
        val tvTypesLabel = view.findViewById<TextView>(R.id.tvTypesLabel)
        val llTypes = view.findViewById<LinearLayout>(R.id.llTypes)
        val etDateFrom = view.findViewById<EditText>(R.id.etDateFrom)
        val etDateTo = view.findViewById<EditText>(R.id.etDateTo)
        val tvLogColsLabel = view.findViewById<TextView>(R.id.tvLogColsLabel)
        val llLogCols = view.findViewById<LinearLayout>(R.id.llLogCols)
        val tvFaultColsLabel = view.findViewById<TextView>(R.id.tvFaultColsLabel)
        val llFaultCols = view.findViewById<LinearLayout>(R.id.llFaultCols)
        val tvColsHint = view.findViewById<TextView>(R.id.tvColsHint)

        // 状态预选
        initialFilter.status?.let { s ->
            when (s) {
                0 -> view.findViewById<RadioButton>(R.id.rbStatusFault)?.isChecked = true
                1 -> view.findViewById<RadioButton>(R.id.rbStatusPass)?.isChecked = true
            }
        }

        // 日期预选
        val cal = Calendar.getInstance()
        fun fmtDate(ms: Long): String {
            if (ms <= 0) return ""
            cal.timeInMillis = ms
            return "%04d-%02d-%02d".format(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
        }
        fun parseDate(s: String): Long {
            if (s.isBlank()) return 0
            return try {
                val parts = s.split("-")
                Calendar.getInstance().apply {
                    set(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt(), 0, 0, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
            } catch (_: Exception) { 0 }
        }
        if (initialFilter.dateFrom > 0) etDateFrom.setText(fmtDate(initialFilter.dateFrom))
        if (initialFilter.dateTo > 0) etDateTo.setText(fmtDate(initialFilter.dateTo))

        fun showDatePicker(et: EditText) {
            val c = Calendar.getInstance()
            val text = et.text?.toString()?.trim().let { if (it.isNullOrEmpty()) null else it }
            text?.let {
                try {
                    val p = it.split("-")
                    c.set(p[0].toInt(), p[1].toInt() - 1, p[2].toInt())
                } catch (_: Exception) {}
            }
            DatePickerDialog(ctx, { _, y, m, d ->
                et.setText("%04d-%02d-%02d".format(y, m + 1, d))
            }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
        }
        etDateFrom.setOnClickListener { showDatePicker(etDateFrom) }
        etDateTo.setOnClickListener { showDatePicker(etDateTo) }
        etDateFrom.isFocusable = false
        etDateTo.isFocusable = false

        // 异步加载测试员列表、柜子类型和列名
        scope.launch {
            val testers = withContext(Dispatchers.IO) { App.repo.debuggers() }
            if (testers.isEmpty()) {
                tvTestersLabel.text = ctx.getString(R.string.export_filter_testers_label)
                llTesters.visibility = View.GONE
            } else {
                llTesters.removeAllViews()
                testers.forEach { d ->
                    val cb = CheckBox(ctx).apply {
                        text = d.name
                        isChecked = initialFilter.testers.isEmpty() || d.name in initialFilter.testers
                        tag = d.name
                    }
                    llTesters.addView(cb)
                }
            }

            // 柜子类型
            val types = withContext(Dispatchers.IO) { App.repo.allTypes() }
            if (types.isEmpty()) {
                tvTypesLabel.visibility = View.GONE
                llTypes.visibility = View.GONE
            } else {
                llTypes.removeAllViews()
                types.forEach { t ->
                    val cb = CheckBox(ctx).apply {
                        text = t.name
                        isChecked = initialFilter.typeIds.isEmpty() || t.id in initialFilter.typeIds
                        tag = t.id
                    }
                    llTypes.addView(cb)
                }
            }

            // 日志列
            llLogCols.removeAllViews()
            ExportSheets.LOG_COL_NAMES.forEachIndexed { idx, name ->
                val cb = CheckBox(ctx).apply {
                    text = name
                    isChecked = initialFilter.logColumns.isEmpty() || idx in initialFilter.logColumns
                    tag = idx
                }
                llLogCols.addView(cb)
            }

            // 故障列
            llFaultCols.removeAllViews()
            ExportSheets.FAULT_COL_NAMES.forEachIndexed { idx, name ->
                val cb = CheckBox(ctx).apply {
                    text = name
                    isChecked = initialFilter.faultColumns.isEmpty() || idx in initialFilter.faultColumns
                    tag = idx
                }
                llFaultCols.addView(cb)
            }
        }

        AlertDialog.Builder(ctx)
            .setTitle(R.string.export_filter_title)
            .setView(view)
            .setPositiveButton(R.string.confirm) { _, _ ->
                // 收集状态
                val status = when (rgStatus.checkedRadioButtonId) {
                    R.id.rbStatusFault -> 0
                    R.id.rbStatusPass -> 1
                    else -> null
                }
                // 收集测试人员
                val selTesters = mutableSetOf<String>()
                for (i in 0 until llTesters.childCount) {
                    val cb = llTesters.getChildAt(i) as? CheckBox
                    if (cb?.isChecked == true) cb.tag?.let { selTesters.add(it as String) }
                }
                // 全选=空集（导出时不筛选）
                val testers = if (selTesters.size == llTesters.childCount || llTesters.childCount == 0) emptySet()
                else selTesters

                // 收集柜子类型
                val selTypes = mutableSetOf<String>()
                for (i in 0 until llTypes.childCount) {
                    val cb = llTypes.getChildAt(i) as? CheckBox
                    if (cb?.isChecked == true) cb.tag?.let { selTypes.add(it as String) }
                }
                val typeIds = if (selTypes.size == llTypes.childCount || llTypes.childCount == 0) emptySet()
                else selTypes

                // 收集日期
                val dateFrom = parseDate(etDateFrom.text?.toString()?.trim().orEmpty())
                val dateTo = parseDate(etDateTo.text?.toString()?.trim().orEmpty())

                // 收集日志列
                val selLogCols = mutableSetOf<Int>()
                for (i in 0 until llLogCols.childCount) {
                    val cb = llLogCols.getChildAt(i) as? CheckBox
                    if (cb?.isChecked == true) (cb.tag as? Int)?.let { selLogCols.add(it) }
                }
                // 全选=空集（导出时全列）
                val logCols = if (selLogCols.size == ExportSheets.LOG_COL_NAMES.size) emptySet() else selLogCols

                // 收集故障列
                val selFaultCols = mutableSetOf<Int>()
                for (i in 0 until llFaultCols.childCount) {
                    val cb = llFaultCols.getChildAt(i) as? CheckBox
                    if (cb?.isChecked == true) (cb.tag as? Int)?.let { selFaultCols.add(it) }
                }
                val faultCols = if (selFaultCols.size == ExportSheets.FAULT_COL_NAMES.size) emptySet() else selFaultCols

                onFilter(ExportFilter(
                    status = status,
                    testers = testers,
                    typeIds = typeIds,
                    dateFrom = dateFrom,
                    dateTo = dateTo,
                    logColumns = logCols,
                    faultColumns = faultCols
                ))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
