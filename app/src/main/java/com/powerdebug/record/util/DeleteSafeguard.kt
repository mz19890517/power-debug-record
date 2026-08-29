package com.powerdebug.record.util

import android.app.AlertDialog
import android.content.Context
import android.widget.EditText
import android.widget.Toast
import com.powerdebug.record.R
import java.util.Calendar

/**
 * 删除防呆：开启后删除操作需输入「当前时间+删除类型」才能确认，防止误触。
 * 例如删除柜子需输入"1730柜子"，删除项目需输入"1730项目"。
 */
object DeleteSafeguard {

    private const val PREFS = "settings"
    private const val KEY = "delete_safeguard_enabled"

    fun isEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true)

    fun setEnabled(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, enabled).apply()
    }

    /** 当前时间 HHmm 字符串（如 "1730"） */
    fun currentTimeStr(): String {
        val c = Calendar.getInstance()
        return String.format("%02d%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    }

    /**
     * 包装删除确认弹窗。
     * @param typeName 删除类型名称，如 "柜子"、"项目"、"类型"、"日志"、"调试员"
     */
    fun confirmDelete(
        context: Context,
        title: Int,
        message: String,
        typeName: String,
        onConfirmed: () -> Unit
    ) {
        val base = AlertDialog.Builder(context)
            .setTitle(title)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setMessage(message)
            .setNegativeButton(R.string.cancel, null)

        if (!isEnabled(context)) {
            base.setPositiveButton(R.string.confirm) { _, _ -> onConfirmed() }
            base.show()
            return
        }

        base.setPositiveButton(R.string.confirm) { _, _ ->
            showTimeVerify(context, typeName, onConfirmed)
        }
        base.show()
    }

    private fun showTimeVerify(context: Context, typeName: String, onConfirmed: () -> Unit) {
        val expected = currentTimeStr() + typeName
        val et = EditText(context).apply {
            hint = context.getString(R.string.safeguard_time_hint, expected)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setPadding(48, 32, 48, 16)
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.safeguard_title)
            .setMessage(context.getString(R.string.safeguard_msg, expected))
            .setView(et)
            .setPositiveButton(R.string.confirm) { _, _ ->
                val input = et.text?.toString()?.trim().orEmpty()
                if (input == expected) {
                    onConfirmed()
                } else {
                    Toast.makeText(context, R.string.safeguard_wrong, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
