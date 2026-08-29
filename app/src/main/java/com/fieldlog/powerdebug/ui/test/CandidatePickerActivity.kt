package com.fieldlog.powerdebug.ui.test

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.fieldlog.powerdebug.App
import com.fieldlog.powerdebug.R
import kotlinx.coroutines.launch

/**
 * 候选池手选器（v2.9）：
 * 候选项按「使用频次」降序（该类型全部柜子预选清单中出现次数；常用项浮顶），
 * 手动勾选要加入本柜清单的条目。支持：
 *  · 长按条目进入涂选态 → 手指滑动或滚动经过的行连续勾选，抬手结束
 *  · 「常用选取」一键勾选使用≥2次的项
 *  · 全选 / 清空
 * 确认后加入本柜预选清单，已在清单中的项自动跳过并标注。
 */
class CandidatePickerActivity : AppCompatActivity() {

    companion object {
        /** 常用选取门槛：使用次数达到该值视为"常用" */
        const val COMMON_THRESHOLD = 2

        fun intent(ctx: Context, instanceId: String) =
            Intent(ctx, CandidatePickerActivity::class.java)
                .putExtra("instance_id", instanceId)
    }

    private var instanceId = ""

    private data class Row(val candId: String, val content: String, val usage: Int, val inList: Boolean)

    private val rows = mutableListOf<Row>()
    private val checkedIds = LinkedHashSet<String>()
    private lateinit var adapter: PickAdapter
    private lateinit var btnConfirm: Button

    /** 涂选进行中（长按某行触发，抬手结束）；期间列表照常可滚动 */
    private var painting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_candidate_picker)

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        instanceId = intent.getStringExtra("instance_id").orEmpty()
        btnConfirm = findViewById(R.id.btn_pick_confirm)

        adapter = PickAdapter()
        val rv = findViewById<RecyclerView>(R.id.rv_cands).apply {
            layoutManager = LinearLayoutManager(this@CandidatePickerActivity)
            adapter = this@CandidatePickerActivity.adapter
        }
        refreshConfirm()

        // ---- 长按涂选多行：长按进入涂选态后，手指滑动/滚动经过的行连续勾选 ----
        // 关键：绝不消费事件（恒返回false），RecyclerView滚动照常工作；
        // 只在旁路观察MOVE做行命中勾选，避免旧版suppressLayout冻死列表的问题
        rv.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> if (painting) checkRowAt(rv, e.x, e.y)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> painting = false
            }
            false
        }

        findViewById<View>(R.id.btn_pick_all).setOnClickListener { selectAll(true) }
        findViewById<View>(R.id.btn_pick_clear).setOnClickListener { selectAll(false) }
        findViewById<View>(R.id.btn_pick_common).setOnClickListener { pickCommon() }
        btnConfirm.setOnClickListener { confirmAdd() }

        load()
    }

    private fun load() {
        lifecycleScope.launch {
            val pairs = App.repo.candidatesByUsage(
                App.repo.getInstance(instanceId)?.typeId ?: run { finish(); return@launch }
            )
            val inList = App.db.plannedItemDao().contentsOnce(instanceId).map { it.trim() }.toHashSet()
            rows.clear()
            rows += pairs.map { (c, u) -> Row(c.id, c.content.trim(), u, c.content.trim() in inList) }
            adapter.notifyDataSetChanged()
            findViewById<TextView>(R.id.tv_pick_empty).visibility =
                if (rows.isEmpty()) View.VISIBLE else View.GONE
            findViewById<RecyclerView>(R.id.rv_cands).visibility =
                if (rows.isEmpty()) View.GONE else View.VISIBLE
            refreshConfirm()
        }
    }

    /** 勾/取消一行 */
    private fun toggle(pos: Int) {
        val row = rows.getOrNull(pos) ?: return
        if (row.inList) return // 已在清单的不可再选
        if (!checkedIds.remove(row.candId)) checkedIds.add(row.candId)
        adapter.notifyItemChanged(pos)
        refreshConfirm()
    }

    /** 拖选：把指针压住的行勾上 */
    private fun checkRowAt(rv: RecyclerView, x: Float, y: Float) {
        val child = rv.findChildViewUnder(x, y) ?: return
        val pos = rv.getChildAdapterPosition(child)
        if (pos == RecyclerView.NO_POSITION) return
        val row = rows[pos]
        if (row.inList || row.candId in checkedIds) return
        checkedIds.add(row.candId)
        adapter.notifyItemChanged(pos)
        refreshConfirm()
    }

    private fun selectAll(all: Boolean) {
        if (all) rows.filter { !it.inList }.forEach { checkedIds.add(it.candId) }
        else checkedIds.clear()
        adapter.notifyDataSetChanged()
        refreshConfirm()
    }

    /** 常用选取：勾选使用频次≥门槛的项；一项都没有则提示 */
    private fun pickCommon() {
        val hits = rows.filter { it.usage >= COMMON_THRESHOLD }
        if (hits.isEmpty()) {
            Toast.makeText(this, getString(R.string.pick_common_none, COMMON_THRESHOLD), Toast.LENGTH_SHORT).show()
            return
        }
        checkedIds.clear()
        hits.forEach { if (!it.inList) checkedIds.add(it.candId) }
        adapter.notifyDataSetChanged()
        refreshConfirm()
    }

    private fun refreshConfirm() {
        btnConfirm.text = getString(R.string.pick_confirm_fmt, checkedIds.size)
        btnConfirm.isEnabled = checkedIds.isNotEmpty()
    }

    private fun confirmAdd() {
        if (checkedIds.isEmpty()) return
        lifecycleScope.launch {
            val text = rows.filter { it.candId in checkedIds }.joinToString("\n") { it.content }
            val added = App.repo.addPlannedFromText(instanceId, text)
            Toast.makeText(
                this@CandidatePickerActivity,
                getString(R.string.added_fmt, added, checkedIds.size - added),
                Toast.LENGTH_SHORT
            ).show()
            finish()
        }
    }

    // ---------- 适配器 ----------

    private inner class PickAdapter : RecyclerView.Adapter<PickVH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            PickVH(LayoutInflater.from(parent.context).inflate(R.layout.item_pick_cand, parent, false))

        override fun getItemCount() = rows.size

        override fun onBindViewHolder(h: PickVH, pos: Int) {
            val item = rows[pos]
            h.cb.setOnCheckedChangeListener(null)
            h.cb.isChecked = item.inList || item.candId in checkedIds
            h.tvContent.text = item.content
            h.tvUsage.text =
                if (item.inList) getString(R.string.pick_in_list)
                else getString(R.string.pick_usage_fmt, item.usage)
            h.tvUsage.setTextColor(
                if (item.usage >= COMMON_THRESHOLD && !item.inList)
                    android.graphics.Color.parseColor("#2E7D32")
                else android.graphics.Color.GRAY
            )
            // 已在清单的置灰禁用
            val alpha = if (item.inList) 0.45f else 1f
            h.itemView.alpha = alpha
            h.cb.isEnabled = !item.inList

            h.itemView.setOnClickListener { toggle(h.bindingAdapterPosition) }
            h.cb.setOnClickListener { toggle(h.bindingAdapterPosition) }
            // 长按任意行 → 进入涂选态（列表仍可滚动），并把当前行先勾上
            h.itemView.setOnLongClickListener {
                if (item.inList) return@setOnLongClickListener false
                painting = true
                if (item.candId !in checkedIds) {
                    checkedIds.add(item.candId)
                    adapter.notifyItemChanged(h.bindingAdapterPosition)
                    refreshConfirm()
                }
                true
            }
        }
    }
}

private class PickVH(v: View) : RecyclerView.ViewHolder(v) {
    val cb: CheckBox = v.findViewById(R.id.cb_pick)
    val tvContent: TextView = v.findViewById(R.id.tv_pick_content)
    val tvUsage: TextView = v.findViewById(R.id.tv_pick_usage)
}
