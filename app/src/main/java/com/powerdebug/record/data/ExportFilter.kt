package com.powerdebug.record.data

/** 导出筛选条件 + 列选择 */
data class ExportFilter(
    /** 状态筛选：null=全部，0=仅含故障，1=仅通过 */
    val status: Int? = null,
    /** 测试人员筛选：空=全部，非空=仅选中的人 */
    val testers: Set<String> = emptySet(),
    /** 柜子类型筛选：空=全部，非空=仅选中的类型 */
    val typeIds: Set<String> = emptySet(),
    /** 日期范围：空=不限 */
    val dateFrom: Long = 0,
    val dateTo: Long = 0,
    /** 日志表选中列索引（空=全部列） */
    val logColumns: Set<Int> = emptySet(),
    /** 故障表选中列索引（空=全部列） */
    val faultColumns: Set<Int> = emptySet()
) {
    /** 是否有实际筛选条件 */
    fun hasFilter() = status != null || testers.isNotEmpty() || typeIds.isNotEmpty() || dateFrom > 0 || dateTo > 0
    /** 是否有列选择（非全选） */
    fun hasColumnFilter() = logColumns.isNotEmpty() || faultColumns.isNotEmpty()
}
