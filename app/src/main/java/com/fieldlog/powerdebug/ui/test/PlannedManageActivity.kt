package com.fieldlog.powerdebug.ui.test

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.fieldlog.powerdebug.App
import com.fieldlog.powerdebug.R
import com.fieldlog.powerdebug.data.db.PlannedItem
import com.fieldlog.powerdebug.util.DT
import kotlinx.coroutines.launch

/** 预选待测清单管理：启用/停用、加自定义条目、从候选池补充、删除 */
class PlannedManageActivity : AppCompatActivity() {

    companion object {
        fun intent(ctx: Context, instanceId: String) =
            Intent(ctx, PlannedManageActivity::class.java)
                .putExtra("instance_id", instanceId)
    }

    private var instanceId = ""
    private var typeId = ""
    private lateinit var adapter: PlannedAdapter

    /** 候选池缓存（内容→使用次数），输入联想用 */
    private val pool = mutableListOf<Pair<String, Int>>()
    /** 本柜现有清单内容（trim后），联想行标注"已在本柜" */
    private val currentContents = mutableSetOf<String>()
    private lateinit var etNewItem: EditText
    private lateinit var boxSuggest: ViewGroup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_planned_manage)

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        instanceId = intent.getStringExtra("instance_id").orEmpty()

        adapter = PlannedAdapter()
        findViewById<RecyclerView>(R.id.rv_planned).apply {
            layoutManager = LinearLayoutManager(this@PlannedManageActivity)
            adapter = this@PlannedManageActivity.adapter
        }

        // 兜底自愈：先清掉「原因见日志但故障记录已不存在」的幽灵未通过项再加载列表
        lifecycleScope.launch {
            App.repo.healGhostFailures()
        }

        lifecycleScope.launch {
            val inst = App.repo.getInstance(instanceId) ?: run { finish(); return@launch }
            typeId = inst.typeId
            supportActionBar?.title = "预选待测 · ${inst.name}"
        }

        lifecycleScope.launch {
            App.repo.watchPlannedOf(instanceId).collect { list ->
                // 未通过项带出故障原因摘要
                val faultIds = list.map { it.faultId }.filter { it.isNotEmpty() }
                val reasons = if (faultIds.isNotEmpty())
                    App.db.faultRecordDao().byIdsOnce(faultIds).associate { it.id to it.symptom }
                else emptyMap()
                adapter.submit(list, reasons)
                currentContents.clear()
                currentContents.addAll(list.map { it.content.trim() })
                findViewById<TextView>(R.id.tv_planned_empty).visibility =
                    if (list.isEmpty()) View.VISIBLE else View.GONE
                findViewById<RecyclerView>(R.id.rv_planned).visibility =
                    if (list.isEmpty()) View.GONE else View.VISIBLE
                refreshInfo(list)
            }
        }

        etNewItem = findViewById(R.id.et_new_item)
        boxSuggest = findViewById(R.id.box_cand_suggest)

        // 候选池联想：加载本类型候选池，输入时把"包含输入文字"的候选项列在框下，
        // 点选回填标准描述再添加——避免手打几个字差异造成重复项
        lifecycleScope.launch {
            val inst = App.repo.getInstance(instanceId) ?: return@launch
            pool.clear()
            pool += App.repo.candidatesByUsage(inst.typeId).map { (c, u) -> c.content.trim() to u }
            filterSuggest()
        }
        etNewItem.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = filterSuggest()
        })

        findViewById<View>(R.id.btn_add_item).setOnClickListener {
            val text = etNewItem.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) return@setOnClickListener
            etNewItem.setText("")
            lifecycleScope.launch {
                val added = App.repo.addPlannedFromText(instanceId, text)
                Toast.makeText(
                    this@PlannedManageActivity,
                    getString(R.string.added_fmt, added, if (added == 0) 1 else 0),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        // 从候选池补充 → 打开候选选择器（按使用频次排序，手动勾选，不再整池全加）
        findViewById<View>(R.id.btn_sync_pool).setOnClickListener {
            startActivity(CandidatePickerActivity.intent(this, instanceId))
        }
    }

    /** 输入联想：列出候选池中"包含输入文字"的条目（最多8条），点选回填标准描述 */
    private fun filterSuggest() {
        if (!this::etNewItem.isInitialized || !this::boxSuggest.isInitialized) return
        val q = etNewItem.text?.toString()?.trim().orEmpty()
        val matches = if (q.isEmpty()) emptyList() else
            pool.filter { it.first.contains(q, ignoreCase = true) }.take(8)
        boxSuggest.removeAllViews()
        if (matches.isEmpty()) {
            boxSuggest.visibility = View.GONE
            return
        }
        boxSuggest.visibility = View.VISIBLE
        // 容器里首个子View是XML里的说明文字，保留它在最上，动态行追加其后
        val caption = TextView(this).apply {
            text = getString(R.string.suggest_caption)
            textSize = 11f
            setTextColor(android.graphics.Color.GRAY)
            setPadding(0, dp(6), 0, 0)
        }
        boxSuggest.addView(caption)
        matches.forEach { (content, usage) ->
            val inList = content in currentContents
            val tag = if (inList) getString(R.string.suggest_in_list)
            else getString(R.string.suggest_usage_fmt, usage)
            val tv = TextView(this).apply {
                text = "· $content　($tag)"
                textSize = 13f
                setTextColor(
                    if (inList) android.graphics.Color.parseColor("#2E7D32")
                    else androidx.core.content.ContextCompat.getColor(context, R.color.on_surface)
                )
                setPadding(dp(10), dp(7), dp(6), dp(7))
                // 点击整行回填标准描述；已在本柜的也可回填（便于对照删除多余项）
                setOnClickListener {
                    etNewItem.setText(content)
                    etNewItem.setSelection(content.length)
                }
            }
            boxSuggest.addView(tv)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private suspend fun refreshInfo(list: List<PlannedItem>) {
        val pending = list.count { it.enabled && it.result == PlannedItem.RESULT_UNTESTED }
        val failed = list.count { it.enabled && it.result == PlannedItem.RESULT_FAIL }
        val passed = list.count { it.result == PlannedItem.RESULT_PASS }
        val disabled = list.count { !it.enabled && it.result != PlannedItem.RESULT_PASS }
        findViewById<TextView>(R.id.tv_info).text =
            getString(R.string.planned_stat_fmt, list.size, pending, failed, passed, disabled)
    }

    private inner class PlannedAdapter : RecyclerView.Adapter<PlannedVH>() {
        private val data = mutableListOf<PlannedItem>()
        private val reasons = mutableMapOf<String, String>()

        fun submit(list: List<PlannedItem>, reasonMap: Map<String, String>) {
            data.clear(); data.addAll(list)
            reasons.clear(); reasons.putAll(reasonMap)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            PlannedVH(LayoutInflater.from(parent.context).inflate(R.layout.item_planned, parent, false))

        override fun getItemCount() = data.size

        override fun onBindViewHolder(h: PlannedVH, pos: Int) {
            val item = data[pos]
            h.cbEnabled.setOnCheckedChangeListener(null)
            h.cbEnabled.isChecked = item.enabled
            h.tvText.text = item.content
            h.tvStatus.text = when {
                item.result == PlannedItem.RESULT_PASS ->
                    getString(R.string.planned_pass_fmt, DT.full(item.doneAt))
                item.result == PlannedItem.RESULT_FAIL ->
                    getString(
                        R.string.planned_fail_fmt,
                        DT.full(item.doneAt),
                        reasons[item.faultId] ?: getString(R.string.planned_reason_in_log)
                    )
                !item.enabled -> getString(R.string.planned_disabled)
                else -> getString(R.string.planned_pending)
            }
            h.tvStatus.setTextColor(
                when {
                    item.result == PlannedItem.RESULT_PASS -> android.graphics.Color.parseColor("#2E7D32")
                    item.result == PlannedItem.RESULT_FAIL -> android.graphics.Color.parseColor("#D32F2F")
                    else -> android.graphics.Color.GRAY
                }
            )
            h.cbEnabled.setOnCheckedChangeListener { _, checked ->
                lifecycleScope.launch { App.repo.updatePlanned(item.copy(enabled = checked)) }
            }
            // 点已测项：可撤销本次结果（恢复未测，重测）
            h.tvText.setOnClickListener {
                if (item.result != PlannedItem.RESULT_UNTESTED) {
                    AlertDialog.Builder(this@PlannedManageActivity)
                        .setTitle(R.string.planned_undo_title)
                        .setMessage(item.content)
                        .setPositiveButton(R.string.confirm) { _, _ ->
                            lifecycleScope.launch {
                                App.repo.updatePlanned(
                                    item.copy(result = PlannedItem.RESULT_UNTESTED, doneAt = 0, logId = "", faultId = "")
                                )
                            }
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            }
            h.btnDel.setOnClickListener {
                AlertDialog.Builder(this@PlannedManageActivity)
                    .setTitle(R.string.delete)
                    .setMessage(item.content)
                    .setPositiveButton(R.string.confirm) { _, _ ->
                        lifecycleScope.launch { App.repo.deletePlanned(item) }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }
    }
}

private class PlannedVH(v: View) : RecyclerView.ViewHolder(v) {
    val cbEnabled: CheckBox = v.findViewById(R.id.cb_enabled)
    val tvText: TextView = v.findViewById(R.id.tv_text)
    val tvStatus: TextView = v.findViewById(R.id.tv_status)
    val btnDel: ImageButton = v.findViewById(R.id.btn_del)
}
