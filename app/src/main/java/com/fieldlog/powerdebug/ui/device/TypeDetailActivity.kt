package com.fieldlog.powerdebug.ui.device

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.fieldlog.powerdebug.App
import com.fieldlog.powerdebug.R
import com.fieldlog.powerdebug.data.db.CabinetType
import com.fieldlog.powerdebug.data.db.CandidateItem
import com.fieldlog.powerdebug.data.db.InstanceRow
import kotlinx.coroutines.launch

class TypeDetailActivity : AppCompatActivity() {

    companion object {
        const val KEY_TYPE_ID = "type_id"
        fun intent(ctx: Context, typeId: String) =
            Intent(ctx, TypeDetailActivity::class.java).putExtra(KEY_TYPE_ID, typeId)
    }

    private var typeId = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_type_detail)

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        typeId = intent.getStringExtra(KEY_TYPE_ID).orEmpty()

        // 候选池
        val poolAdapter = PoolAdapter()
        val rvPool = findViewById<RecyclerView>(R.id.rv_pool)
        rvPool.layoutManager = LinearLayoutManager(this)
        rvPool.adapter = poolAdapter

        lifecycleScope.launch {
            App.db.candidateItemDao().watchByTypeAsFlow(typeId).collect { list ->
                poolAdapter.submit(list)
                findViewById<TextView>(R.id.tv_pool_empty).visibility =
                    if (list.isEmpty()) View.VISIBLE else View.GONE
                refreshInfo(list.size)
            }
        }

        val etNewItem = findViewById<EditText>(R.id.et_new_item)
        findViewById<View>(R.id.btn_add_item).setOnClickListener {
            val text = etNewItem.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) return@setOnClickListener
            etNewItem.setText("")
            lifecycleScope.launch {
                val added = App.repo.addCandidatesFromText(typeId, text)
                Toast.makeText(
                    this@TypeDetailActivity,
                    getString(R.string.added_fmt, added, if (added == 0) 1 else 0),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        findViewById<View>(R.id.btn_batch_import).setOnClickListener {
            val dlgView = layoutInflater.inflate(R.layout.dialog_input_multiline, null)
            dlgView.findViewById<TextView>(R.id.tv_prompt).setText(R.string.batch_import_hint)
            dlgView.findViewById<EditText>(R.id.et_input).hint = ""
            AlertDialog.Builder(this)
                .setTitle(R.string.batch_import)
                .setView(dlgView)
                .setPositiveButton(R.string.confirm) { _, _ ->
                    val text = dlgView.findViewById<EditText>(R.id.et_input).text?.toString().orEmpty()
                    if (text.isNotBlank()) {
                        lifecycleScope.launch {
                            val added = App.repo.addCandidatesFromText(typeId, text)
                            Toast.makeText(
                                this@TypeDetailActivity,
                                getString(R.string.added_fmt, added, text.lines().count { it.trim().isNotEmpty() } - added),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        // 使用情况（只读）
        val usageAdapter = UsageAdapter()
        val rvUsage = findViewById<RecyclerView>(R.id.rv_usage)
        rvUsage.layoutManager = LinearLayoutManager(this)
        rvUsage.adapter = usageAdapter

        lifecycleScope.launch {
            val rows = App.repo.instanceUsageOfType(typeId)
            usageAdapter.submit(rows)
            findViewById<TextView>(R.id.tv_usage_empty).visibility =
                if (rows.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun refreshInfo(itemCount: Int) {
        lifecycleScope.launch {
            val t = App.repo.getType(typeId) ?: return@launch
            supportActionBar?.title = t.name
            val usage = App.repo.instanceUsageOfType(typeId).size
            findViewById<TextView>(R.id.tv_type_info).text =
                getString(R.string.type_stat_fmt, itemCount, usage)
                    .let { s -> if (t.remark.isBlank()) s else "$s · ${t.remark}" }
        }
    }

    // ---------- 菜单 ----------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_type_detail, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_rename_type -> { renameType(); true }
        R.id.action_delete_type -> { deleteType(); true }
        else -> super.onOptionsItemSelected(item)
    }

    private suspend fun currentType(): CabinetType? = App.repo.getType(typeId)

    private fun renameType() {
        lifecycleScope.launch {
            val t = currentType() ?: return@launch
            val dlgView = layoutInflater.inflate(R.layout.dialog_input_multiline, null)
            dlgView.findViewById<TextView>(R.id.tv_prompt).setText(R.string.type_name)
            dlgView.findViewById<EditText>(R.id.et_input).minLines = 1
            dlgView.findViewById<EditText>(R.id.et_input).setText(t.name)
            AlertDialog.Builder(this@TypeDetailActivity)
                .setTitle(R.string.edit_type)
                .setView(dlgView)
                .setPositiveButton(R.string.save) { _, _ ->
                    val name = dlgView.findViewById<EditText>(R.id.et_input).text?.toString()?.trim().orEmpty()
                    if (name.isEmpty()) {
                        Toast.makeText(this@TypeDetailActivity, R.string.name_required, Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    lifecycleScope.launch {
                        App.repo.saveType(t.copy(name = name))
                        supportActionBar?.title = name
                        refreshInfo(-1)
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun deleteType() {
        lifecycleScope.launch {
            val t = currentType() ?: return@launch
            val usage = App.repo.instanceUsageOfType(t.id).size
            AlertDialog.Builder(this@TypeDetailActivity)
                .setTitle(R.string.delete)
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setMessage(getString(R.string.warn_del_type, t.name, usage))
                .setPositiveButton(R.string.confirm) { _, _ ->
                    lifecycleScope.launch {
                        App.repo.deleteType(t.id)
                        finish()
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    // ---------- 适配器 ----------

    private inner class PoolAdapter : RecyclerView.Adapter<PoolVH>() {
        private val data = mutableListOf<CandidateItem>()

        fun submit(list: List<CandidateItem>) {
            data.clear(); data.addAll(list); notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            PoolVH(layoutInflater.inflate(R.layout.item_candidate, parent, false))

        override fun getItemCount() = data.size

        override fun onBindViewHolder(h: PoolVH, pos: Int) {
            val item = data[pos]
            h.tvText.text = item.content
            h.btnDel.setOnClickListener {
                AlertDialog.Builder(this@TypeDetailActivity)
                    .setTitle(R.string.del_candidate)
                    .setMessage(item.content)
                    .setPositiveButton(R.string.confirm) { _, _ ->
                        lifecycleScope.launch { App.repo.deleteCandidate(item) }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }
    }

    private inner class UsageAdapter : RecyclerView.Adapter<UsageVH>() {
        private val data = mutableListOf<InstanceRow>()

        fun submit(list: List<InstanceRow>) {
            data.clear(); data.addAll(list); notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UsageVH {
            val v = layoutInflater.inflate(R.layout.item_usage, parent, false)
            v.findViewById<View>(R.id.status_dot).visibility = View.GONE
            v.findViewById<View>(R.id.tv_text).setPaddingRelative(14, 8, 8, 8)
            return UsageVH(v)
        }

        override fun getItemCount() = data.size

        override fun onBindViewHolder(h: UsageVH, pos: Int) {
            val row = data[pos]
            h.tvText.text = "${row.projectName} · ${row.instance.name}"
        }
    }
}

private class PoolVH(v: View) : RecyclerView.ViewHolder(v) {
    val tvText: TextView = v.findViewById(R.id.tv_text)
    val btnDel: View = v.findViewById(R.id.btn_del)
}

private class UsageVH(v: View) : RecyclerView.ViewHolder(v) {
    val tvText: TextView = v.findViewById(R.id.tv_text)
}
