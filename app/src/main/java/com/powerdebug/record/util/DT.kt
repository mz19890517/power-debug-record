package com.powerdebug.record.util

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object DT {

    private fun fmt(pattern: String) = SimpleDateFormat(pattern, Locale.CHINA)

    fun full(ms: Long): String =
        if (ms <= 0) "" else fmt("yyyy-MM-dd HH:mm").format(Date(ms))

    /** 纯日期（yyyy-MM-dd），用于调试起始/完成日期展示 */
    fun dateOnly(ms: Long): String =
        if (ms <= 0) "" else fmt("yyyy-MM-dd").format(Date(ms))

    fun fileStamp(): String = fmt("yyyyMMdd_HHmmss").format(Date())

    /** 依次弹出日期、时间选择器，回调选中的时间戳 */
    fun pick(context: Context, initial: Long, onPicked: (Long) -> Unit) {
        val cal = Calendar.getInstance()
        if (initial > 0) cal.timeInMillis = initial
        DatePickerDialog(
            context,
            { _, y, m, d ->
                cal.set(Calendar.YEAR, y)
                cal.set(Calendar.MONTH, m)
                cal.set(Calendar.DAY_OF_MONTH, d)
                TimePickerDialog(
                    context,
                    { _, h, mi ->
                        cal.set(Calendar.HOUR_OF_DAY, h)
                        cal.set(Calendar.MINUTE, mi)
                        cal.set(Calendar.SECOND, 0)
                        cal.set(Calendar.MILLISECOND, 0)
                        onPicked(cal.timeInMillis)
                    },
                    cal.get(Calendar.HOUR_OF_DAY),
                    cal.get(Calendar.MINUTE),
                    true
                ).show()
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    /** 仅选日期（保留时分秒为 0），回调选中的当日零点。用于调试起始/完成日期手动修改 */
    fun pickDate(context: Context, initial: Long, onPicked: (Long) -> Unit) {
        val cal = Calendar.getInstance()
        if (initial > 0) cal.timeInMillis = initial
        DatePickerDialog(
            context,
            { _, y, m, d ->
                cal.set(Calendar.YEAR, y)
                cal.set(Calendar.MONTH, m)
                cal.set(Calendar.DAY_OF_MONTH, d)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                onPicked(cal.timeInMillis)
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }
}
