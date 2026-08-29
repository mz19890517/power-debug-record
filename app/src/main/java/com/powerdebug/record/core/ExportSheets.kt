package com.powerdebug.record.core

import android.content.Context
import com.powerdebug.record.R
import com.powerdebug.record.data.db.DebugLog
import com.powerdebug.record.data.db.FaultExportRow
import com.powerdebug.record.data.db.FaultRecord
import com.powerdebug.record.data.db.LogListItem
import com.powerdebug.record.util.DT

/**
 * Excel 双表（调试日志+故障记录）构建器。
 * 全量导出与项目/单柜定向导出共用同一格式，保证 A4 打印适配一致。
 * @param logColumns 日志表选中列索引（空=全选）
 * @param faultColumns 故障表选中列索引（空=全选）
 */
object ExportSheets {

    /** 日志表全部列定义（供弹窗展示列名，索引对应） */
    val LOG_COL_NAMES = listOf(
        "序号", "项目", "柜子类型", "实例名称", "设备编号", "回路",
        "测试内容", "日志类型", "测试人员", "备注", "安装人员", "创建账号", "修改账号",
        "记录时间", "更新时间"
    )

    /** 故障表全部列定义 */
    val FAULT_COL_NAMES = listOf(
        "序号", "项目", "柜子实例", "设备编号", "故障回路",
        "问题现象", "解决方法", "发生时间", "解决完成时间", "状态", "关联日志时间"
    )

    fun build(
        ctx: Context,
        logs: List<LogListItem>,
        faults: List<FaultExportRow>,
        logColumns: Set<Int> = emptySet(),
        faultColumns: Set<Int> = emptySet()
    ): List<XlsxWriter.SheetDef> {
        val allLogCols = (0 until LOG_COL_NAMES.size).toSet()
        val allFaultCols = (0 until FAULT_COL_NAMES.size).toSet()
        val selLog = if (logColumns.isEmpty()) allLogCols else logColumns.intersect(allLogCols)
        val selFault = if (faultColumns.isEmpty()) allFaultCols else faultColumns.intersect(allFaultCols)

        fun logRow(i: Int, it: LogListItem) = listOf(
            (i + 1).toString(),
            it.projectName,
            it.typeName,
            it.instanceName,
            it.deviceCode,
            it.log.circuit.ifEmpty { ctx.getString(R.string.whole_cabinet) },
            it.log.testContent,
            when (it.log.logType) {
                DebugLog.LOG_TYPE_FAULT -> "故障"
                DebugLog.LOG_TYPE_RESOLUTION -> "消除"
                else -> "通过"
            },
            it.log.tester,
            it.log.remark,
            if (it.installer.isBlank()) "" else "${it.installer}",
            it.log.createdBy,
            it.log.updatedBy,
            DT.full(it.log.createdAt),
            DT.full(it.log.updatedAt)
        )

        fun faultRow(i: Int, f: FaultExportRow) = listOf(
            (i + 1).toString(),
            f.projectName,
            f.instanceName,
            f.deviceCode,
            f.fault.circuit.ifEmpty { ctx.getString(R.string.whole_cabinet) },
            f.fault.symptom,
            f.fault.solution,
            DT.full(f.fault.occurredAt),
            if (f.fault.status == FaultRecord.STATUS_RESOLVED) DT.full(f.fault.resolvedAt) else "",
            ctx.getString(
                if (f.fault.status == FaultRecord.STATUS_RESOLVED) R.string.fault_status_resolved
                else R.string.fault_status_pending
            ),
            DT.full(f.fault.occurredAt).ifEmpty { "-" }
        )

        fun <T> pickCols(allCols: List<String>, selected: Set<Int>, row: (Int) -> List<String>): List<List<String>> =
            row(0).indices.filter { it in selected }.map { colIdx -> row(0).map { it } } // just to type-check

        // 按选中列索引从全量行中提取，并重算wrapCols在新列中的位置
        val sortedLog = selLog.sorted()
        val sortedFault = selFault.sorted()

        val logRows = logs.mapIndexed { i, it ->
            val full = logRow(i, it)
            sortedLog.map { full[it] }
        }
        val faultRows = faults.mapIndexed { i, f ->
            val full = faultRow(i, f)
            sortedFault.map { full[it] }
        }

        // 日志表需要自动换行的列：原索引5=回路、7=备注（按测试人员换列后为7）
        val logWrapOrig = setOf(5, 7)
        val logWrap = sortedLog.withIndex().filter { it.value in logWrapOrig }.map { it.index }.toSet()

        // 故障表需要自动换行的列：原索引4=故障回路、5=问题现象
        val faultWrapOrig = setOf(4, 5)
        val faultWrap = sortedFault.withIndex().filter { it.value in faultWrapOrig }.map { it.index }.toSet()

        return listOf(
            XlsxWriter.SheetDef(
                name = "调试日志",
                headers = sortedLog.map { LOG_COL_NAMES[it] },
                rows = logRows,
                wrapCols = logWrap
            ),
            XlsxWriter.SheetDef(
                name = "故障记录",
                headers = sortedFault.map { FAULT_COL_NAMES[it] },
                rows = faultRows,
                wrapCols = faultWrap,
                landscape = true
            )
        )
    }
}
