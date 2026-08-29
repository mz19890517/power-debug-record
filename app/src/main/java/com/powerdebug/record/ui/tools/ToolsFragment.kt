package com.powerdebug.record.ui.tools

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog.Builder
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.powerdebug.record.App
import com.powerdebug.record.R
import com.powerdebug.record.core.ExportSheets
import com.powerdebug.record.core.WebDavClient
import com.powerdebug.record.core.XlsxWriter
import com.powerdebug.record.data.ConflictFavor
import com.powerdebug.record.data.ExportFilter
import com.powerdebug.record.data.Repository
import com.powerdebug.record.data.RollbackPreview
import com.powerdebug.record.data.db.TesterAccount
import com.powerdebug.record.ui.FilterDialogHelper
import com.powerdebug.record.databinding.FragmentToolsBinding
import com.powerdebug.record.util.CrashLog
import com.powerdebug.record.util.DT
import com.powerdebug.record.util.FixLogStore
import com.powerdebug.record.util.SyncLog
import com.powerdebug.record.util.SyncStore
import com.powerdebug.record.util.WebDavSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class ToolsFragment : Fragment() {

    private var _b: FragmentToolsBinding? = null
    private val b get() = _b!!

    /** 当前导出筛选条件（跨次点击保持） */
    private var currentFilter = ExportFilter()

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument(XLSX_MIME)
    ) { uri -> uri?.let { doExport(it, currentFilter) } }

    private val backupLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument(JSON_MIME)
    ) { uri -> uri?.let { doBackup(it) } }

    private val restoreLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { doRestore(it) } }

    private val rollbackLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { doRollback(it) } }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _b = FragmentToolsBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        b.btnExport.setOnClickListener {
            FilterDialogHelper.show(requireContext(), viewLifecycleOwner.lifecycleScope, currentFilter) { filter ->
                currentFilter = filter
                exportLauncher.launch("电源柜调试日志_${DT.fileStamp()}.xlsx")
            }
        }
        b.btnBackup.setOnClickListener {
            backupLauncher.launch("电源柜调试备份_${DT.fileStamp()}.json")
        }
        b.btnRestore.setOnClickListener {
            restoreLauncher.launch(arrayOf(JSON_MIME, "text/*", "application/octet-stream"))
        }
        b.btnRollback.setOnClickListener {
            rollbackLauncher.launch(arrayOf(JSON_MIME, "text/*", "application/octet-stream"))
        }
        b.btnFixTypes.setOnClickListener { doFixLogTypes() }
        b.btnFixTypesUndo.setOnClickListener { doUndoFixLogTypes() }

        // ---- 账号与同步 ----
        b.btnLogin.setOnClickListener { showLoginDialog() }
        b.btnSwitchUser.setOnClickListener { showSwitchUserDialog() }
        b.btnManageDebuggers.setOnClickListener { showDebuggerGate() }
        b.btnLogout.setOnClickListener {
            SyncStore.setCurrentUser(requireContext(), null)
            refreshAccountUI()
            Toast.makeText(requireContext(), R.string.sync_logged_out, Toast.LENGTH_SHORT).show()
        }
        b.swAutoUpload.isChecked = SyncStore.autoUpload(requireContext())
        b.swAutoUpload.setOnCheckedChangeListener { _, checked ->
            SyncStore.setAutoUpload(requireContext(), checked)
        }
        b.btnSyncNow.setOnClickListener {
            val ctx = requireContext()
            if (SyncStore.currentUser(ctx) == null) {
                toast("请先登录测试账号"); return@setOnClickListener
            }
            toast("正在增量同步…")
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    // 同一次手动同步内冲突裁决只弹一次，选择对本次全部有歧义的文件生效
                    var asked = false
                    var favor = ConflictFavor.CLOUD
                    val summary = WebDavSync.syncAll(ctx) {
                        if (!asked) {
                            asked = true
                            favor = askSyncConflict()
                        }
                        favor
                    }
                    toast(summary)
                    refreshStats()
                } catch (e: Exception) {
                    toast(e.message ?: "同步失败")
                }
            }
        }
        b.btnSyncLog.setOnClickListener { showSyncLogDialog() }

        // 删除防呆开关
        b.swDeleteSafeguard.isChecked = com.powerdebug.record.util.DeleteSafeguard.isEnabled(requireContext())
        b.swDeleteSafeguard.setOnCheckedChangeListener { _, checked ->
            com.powerdebug.record.util.DeleteSafeguard.setEnabled(requireContext(), checked)
        }
    }

    /** 诊断日志：同步过程 + 崩溃记录（黑匣子），可一键复制发开发者 */
    private fun showSyncLogDialog() {
        val ctx = requireContext()
        val sync = SyncLog.read(ctx)
        val crash = CrashLog.read(ctx)
        val content = buildString {
            if (crash.isNotBlank()) {
                append("════ 崩溃记录 ════\n").append(crash)
                    .append("\n════ 同步日志 ════\n")
            }
            append(sync)
        }
        val tv = TextView(ctx).apply {
            text = content.ifBlank { getString(R.string.sync_log_empty) }
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(40, 24, 24, 12)
        }
        val scroll = ScrollView(ctx).apply { addView(tv) }
        Builder(ctx)
            .setTitle(R.string.sync_log_title)
            .setView(scroll)
            .setNeutralButton(R.string.copy) { _, _ ->
                if (content.isNotBlank()) {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("diag_log", content))
                    Toast.makeText(ctx, R.string.sync_log_copied, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.sync_log_clear) { _, _ ->
                SyncLog.clear(ctx); CrashLog.clear(ctx)
                Toast.makeText(ctx, R.string.sync_log_cleared, Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton(R.string.close, null)
            .show()
    }

    /**
     * 时间戳冲突裁决（7.7）：同一条目本机与云端在同一窗口内有更新、无法靠墙钟可靠判定时，
     * 让用户选「保留本地」或「覆盖云端」。挂起直到弹窗关闭；对话框被取消默认按覆盖云端。
     */
    private suspend fun askSyncConflict(): ConflictFavor = withContext(Dispatchers.Main) {
        val ctx = requireContext()
        suspendCancellableCoroutine { cont ->
            Builder(ctx)
                .setTitle(R.string.sync_conflict_title)
                .setMessage(R.string.sync_conflict_msg)
                .setPositiveButton(R.string.sync_conflict_local) { _, _ -> cont.resume(ConflictFavor.LOCAL) }
                .setNegativeButton(R.string.sync_conflict_cloud) { _, _ -> cont.resume(ConflictFavor.CLOUD) }
                .setOnCancelListener { cont.resume(ConflictFavor.CLOUD) }
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStats()
        refreshAccountUI()
        refreshCurrentDebuggerUI()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }

    // ---------- 账号状态展示 ----------

    private fun refreshAccountUI() {
        val ctx = requireContext()
        val user = SyncStore.currentUser(ctx)
        b.tvCurrentUser.text =
            if (user == null) "当前测试员：${getString(R.string.not_logged_in)}（日志不记录归属账号）"
            else "当前测试员：$user"
        val cfg = SyncStore.config(ctx)
        b.tvWebdavStatus.text =
            if (cfg == null) "WebDAV：未配置（仅本地身份标记）"
            else "WebDAV：${cfg.url}"
        viewLifecycleOwner.lifecycleScope.launch {
            val roster = App.repo.debuggers()
            b.tvDebuggers.text =
                if (roster.isEmpty()) getString(R.string.debugger_summary_empty)
                else getString(R.string.debugger_summary, roster.size, roster.joinToString("、") { it.name })
        }
    }

    /** 刷新当前调试员显示行；点击可快速切换（无需密码） */
    private fun refreshCurrentDebuggerUI() {
        val cur = SyncStore.currentDebugger(requireContext())
        b.tvCurrentDebugger.text = cur.ifEmpty { getString(R.string.debugger_none_set) }
        b.tvCurrentDebugger.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val roster = App.repo.debuggers()
                if (roster.isEmpty()) {
                    toast("请先添加调试员名单"); return@launch
                }
                val names = roster.map { it.name }.toTypedArray()
                val checked = names.indexOf(cur).coerceAtLeast(0)
                Builder(requireContext())
                    .setTitle(R.string.debugger_switch_title)
                    .setSingleChoiceItems(names, checked) { dlg, which ->
                        SyncStore.setCurrentDebugger(requireContext(), names[which])
                        toast(getString(R.string.debugger_bound_local, names[which]))
                        refreshCurrentDebuggerUI()
                        dlg.dismiss()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }
    }

    /**
     * 登录/添加测试员。密码=超级口令 → 离线直接注册；否则走WebDAV验证。
     * 弹窗内提供「测试连接」：显示完整诊断报告且不关闭弹窗；
     * 登录失败时同样把诊断报告写入弹窗，便于把失败原因发给开发者。
     */
    private fun showLoginDialog(existingUsername: String = "") {
        val ctx = requireContext()
        val dlgView = layoutInflater.inflate(R.layout.dialog_login, null)
        val etServer = dlgView.findViewById<EditText>(R.id.etServer)
        val etUser = dlgView.findViewById<EditText>(R.id.etUsername)
        val etPass = dlgView.findViewById<EditText>(R.id.etPassword)
        val etLegacy = dlgView.findViewById<EditText>(R.id.etLegacyUrl)
        val btnTest = dlgView.findViewById<View>(R.id.btnTestConn)
        val tvDiag = dlgView.findViewById<TextView>(R.id.tvDiagResult)

        SyncStore.config(ctx)?.let {
            etServer.setText(it.url); etUser.setText(it.user)
        } ?: run { if (existingUsername.isNotEmpty()) etUser.setText(existingUsername) }
        SyncStore.legacyUrl(ctx)?.let { etLegacy.setText(it) }

        fun currentClient(): WebDavClient? {
            val user = etUser.text?.toString()?.trim().orEmpty()
            val pass = etPass.text?.toString() ?: ""
            val url = etServer.text?.toString()?.trim().orEmpty()
            if (user.isEmpty() || url.isEmpty()) return null
            return WebDavClient(url, user, pass)
        }

        btnTest.setOnClickListener {
            val cl = currentClient()
            if (cl == null) {
                tvDiag.visibility = View.VISIBLE
                tvDiag.text = "请先填写服务器地址和账号"
                return@setOnClickListener
            }
            tvDiag.visibility = View.VISIBLE
            tvDiag.text = "正在测试连接…"
            viewLifecycleOwner.lifecycleScope.launch {
                val report = withContext(Dispatchers.IO) { cl.diagnose() }
                tvDiag.text = report
            }
        }

        val dlg = Builder(ctx)
            .setTitle(R.string.sync_login)
            .setView(dlgView)
            .setPositiveButton(R.string.confirm, null) // 点击行为手动接管，失败时不关闭
            .setNegativeButton(R.string.cancel, null)
            .create()

        fun tryLoginOrShowDiag() {
            val username = etUser.text?.toString()?.trim().orEmpty()
            val pass = etPass.text?.toString()?.trim() ?: ""
            val isSuper = pass == SyncStore.SUPER_PASSWORD

            fun showTip(msg: String) {
                tvDiag.visibility = View.VISIBLE
                tvDiag.text = msg
            }

            // 超级口令优先判定（trim后精确匹配），无需服务器地址
            if (isSuper) {
                if (username.isEmpty()) {
                    showTip("✅ 已识别超级口令。\n请再在「账号」栏填写要注册的测试员姓名（如：张三），然后点「确认」，即在本机注册该测试员身份（离线可用，不上传）。\n注：超级口令的作用就是免服务器注册测试员，没有单独的管理界面。")
                    etUser.requestFocus()
                    return
                }
                lifecycleScope.launch {
                    App.repo.registerTester(username, TesterAccount.SOURCE_SUPER)
                    SyncStore.setCurrentUser(ctx, username)
                    refreshAccountUI()
                    toast(getString(R.string.sync_login_super, username))
                    dlg.dismiss()
                }
                return
            }

            if (username.isEmpty()) {
                showTip("请先在「账号」栏填写测试员姓名。\n· 有WebDAV：再填服务器地址和密码后确认登录\n· 无服务器：密码栏输入超级口令即可离线注册")
                return
            }

            val url = etServer.text?.toString()?.trim().orEmpty()
            if (url.isEmpty()) {
                showTip("请填写服务器地址，或使用超级口令（密码长度应为${SyncStore.SUPER_PASSWORD.length}位；当前收到${pass.length}位）。")
                return
            }
            toast(R.string.sync_verifying)
            lifecycleScope.launch {
                val cl = WebDavClient(url, username, pass)
                val ok = try {
                    withContext(Dispatchers.IO) { cl.verify(); true }
                } catch (_: Exception) { false }
                if (ok) {
                    SyncStore.saveConfig(ctx, url, username, pass)
                    // 旧版数据目录（迁移用）：仅登录成功时保存；留空则清除
                    SyncStore.setLegacyUrl(ctx, etLegacy.text?.toString()?.trim()?.takeIf { it.isNotEmpty() })
                    App.repo.registerTester(username, TesterAccount.SOURCE_WEBDAV)
                    SyncStore.setCurrentUser(ctx, username)
                    refreshAccountUI()
                    toast(getString(R.string.sync_login_ok, username))
                    dlg.dismiss()
                } else {
                    // 失败：完整诊断留在弹窗内，可复制发回
                    tvDiag.visibility = View.VISIBLE
                    tvDiag.text = withContext(Dispatchers.IO) { cl.diagnose() }
                        .plus("\n(发送的密码长度 ${pass.length} 位)")
                }
            }
        }

        dlg.show()
        dlg.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener { tryLoginOrShowDiag() }
    }

    /** 从本机已沉淀的测试员中切换归属身份 */
    private fun showSwitchUserDialog() {
        lifecycleScope.launch {
            val accounts = App.repo.testerAccounts()
            if (accounts.isEmpty()) {
                toast("暂无已注册测试员，请先登录")
                return@launch
            }
            val names = accounts.map { it.username }.toTypedArray()
            val cur = SyncStore.currentUser(requireContext())
            val checked = names.indexOf(cur)
            Builder(requireContext())
                .setTitle(R.string.sync_switch)
                .setSingleChoiceItems(names, checked) { dlg, which ->
                    SyncStore.setCurrentUser(requireContext(), names[which])
                    refreshAccountUI()
                    dlg.dismiss()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    // ---------- 调试员名单管理（增/改/删均需超级口令） ----------

    /** 第一步：验证超级口令 */
    private fun showDebuggerGate() {
        val dlgView = layoutInflater.inflate(R.layout.dialog_single_input, null)
        val et = dlgView.findViewById<EditText>(R.id.et_input)
        et.hint = getString(R.string.debugger_gate_hint)
        et.inputType = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD

        val dlg = Builder(requireContext())
            .setTitle(R.string.debugger_gate_title)
            .setMessage(R.string.debugger_gate_msg)
            .setView(dlgView)
            .setPositiveButton(R.string.confirm, null) // 手动接管：口令错误不关闭
            .setNegativeButton(R.string.cancel, null)
            .create()
        dlg.show()
        dlg.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            if (et.text?.toString()?.trim() == SyncStore.SUPER_PASSWORD) {
                dlg.dismiss()
                showDebuggerManager()
            } else {
                toast(getString(R.string.debugger_gate_wrong))
            }
        }
    }

    /** 名单管理主弹窗：点名字→改名/删除；底部「添加」 */
    private fun showDebuggerManager() {
        viewLifecycleOwner.lifecycleScope.launch {
            val roster = App.repo.debuggers()
            val names = roster.map { it.name }.toTypedArray()
            Builder(requireContext())
                .setTitle(getString(R.string.debugger_manager_title, roster.size))
                .setItems(names) { _, which -> showDebuggerItemMenu(roster[which]) }
                .setPositiveButton(R.string.debugger_add) { _, _ -> showDebuggerEditDialog(null) }
                .setNegativeButton(R.string.close, null)
                .show()
        }
    }

    /** 单个调试员操作菜单：改名 / 删除 / 绑定本机 */
    private fun showDebuggerItemMenu(d: com.powerdebug.record.data.db.Debugger) {
        val items = arrayOf(
            getString(R.string.debugger_rename),
            getString(R.string.debugger_delete),
            getString(R.string.debugger_bind_local)
        )
        Builder(requireContext())
            .setTitle(d.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showDebuggerEditDialog(d)
                    1 -> confirmDeleteDebugger(d)
                    2 -> {
                        SyncStore.setCurrentDebugger(requireContext(), d.name)
                        toast(getString(R.string.debugger_bound_local, d.name))
                        refreshCurrentDebuggerUI()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 新增（d=null）/ 改名 共用输入弹窗；完成后回到名单管理 */
    private fun showDebuggerEditDialog(d: com.powerdebug.record.data.db.Debugger?) {
        val dlgView = layoutInflater.inflate(R.layout.dialog_single_input, null)
        val et = dlgView.findViewById<EditText>(R.id.et_input)
        et.hint = getString(R.string.debugger_name_hint)
        d?.let { et.setText(it.name) }

        val dlg = Builder(requireContext())
            .setTitle(if (d == null) R.string.debugger_add else R.string.debugger_rename)
            .setView(dlgView)
            .setPositiveButton(R.string.confirm, null) // 手动接管：校验失败不关闭
            .setNegativeButton(R.string.cancel, null)
            .create()
        dlg.show()
        dlg.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            val name = et.text?.toString()?.trim().orEmpty()
            if (name.isEmpty()) {
                toast(getString(R.string.debugger_name_empty)); return@setOnClickListener
            }
            viewLifecycleOwner.lifecycleScope.launch {
                val ok = if (d == null) App.repo.addDebugger(name)
                else App.repo.renameDebugger(d.id, name)
                if (ok) {
                    toast(
                        getString(
                            if (d == null) R.string.debugger_added else R.string.debugger_renamed,
                            name
                        )
                    )
                    showDebuggerManager() // 回到名单管理
                } else {
                    toast(getString(R.string.debugger_exists))
                }
            }
        }
    }

    private fun confirmDeleteDebugger(d: com.powerdebug.record.data.db.Debugger) {
        com.powerdebug.record.util.DeleteSafeguard.confirmDelete(
            context = requireContext(),
            title = R.string.debugger_delete,
            message = getString(R.string.debugger_delete_confirm, d.name),
            typeName = "调试员"
        ) {
            viewLifecycleOwner.lifecycleScope.launch {
                App.repo.deleteDebugger(d.id)
                toast(getString(R.string.debugger_deleted, d.name))
                refreshAccountUI()
                showDebuggerManager()
            }
        }
    }

    // ---------- Excel 导出 ----------

    private fun toast(msg: String) =
        Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()

    private fun refreshStats() {
        viewLifecycleOwner.lifecycleScope.launch {
            val s = App.repo.stats()
            b.statProjects.text = s.projects.toString()
            b.statTypes.text = s.types.toString()
            b.statLogs.text = s.logs.toString()
            b.statPending.text = s.pendingFaults.toString()
        }
    }

    private fun toast(resId: Int) =
        Toast.makeText(requireContext(), resId, Toast.LENGTH_LONG).show()

    private fun doExport(uri: Uri, filter: ExportFilter) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val (logs, faults) = App.repo.collectExport(filter)
                withContext(Dispatchers.IO) {
                    requireContext().contentResolver.openOutputStream(uri)?.use { out ->
                        XlsxWriter.write(out, ExportSheets.build(
                            requireContext(), logs, faults,
                            logColumns = filter.logColumns,
                            faultColumns = filter.faultColumns
                        ))
                    } ?: throw IllegalStateException("无法打开输出流")
                }
                toast(getString(R.string.export_ok, uri.lastPathSegment ?: ""))
            } catch (e: Exception) {
                toast(getString(R.string.op_failed, e.message ?: e.javaClass.simpleName))
            }
        }
    }

    // ---------- JSON 备份 / 恢复 ----------

    private fun doBackup(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val json = App.repo.backupJson()
                withContext(Dispatchers.IO) {
                    requireContext().contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(json.toByteArray(Charsets.UTF_8))
                    } ?: throw IllegalStateException("无法打开输出流")
                }
                toast(getString(R.string.backup_ok))
            } catch (e: Exception) {
                toast(getString(R.string.op_failed, e.message ?: e.javaClass.simpleName))
            }
        }
    }

    private fun doRestore(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    requireContext().contentResolver.openInputStream(uri)?.use { input ->
                        input.bufferedReader(Charsets.UTF_8).readText()
                    } ?: throw IllegalStateException("无法读取文件")
                }
                // 预解析统计，供确认弹窗展示
                val counts = withContext(Dispatchers.Default) { previewBackup(text) }
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.restore_confirm_title)
                    .setIcon(android.R.drawable.ic_dialog_alert)
                    .setMessage(
                        getString(
                            R.string.restore_confirm_msg,
                            counts[0], counts[1], counts[2], counts[3], counts[4]
                        )
                    )
                    .setPositiveButton(R.string.confirm) { _, _ ->
                        viewLifecycleOwner.lifecycleScope.launch {
                            try {
                                App.repo.restoreJson(text)
                                toast(getString(R.string.restore_ok))
                                refreshStats()
                            } catch (e: Exception) {
                                toast(getString(R.string.op_failed, e.message ?: e.javaClass.simpleName))
                            }
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            } catch (e: Exception) {
                toast(R.string.restore_bad_file)
            }
        }
    }

    /** 返回 [项目数, 类型数, 柜子数, 日志数, 故障数] */
    private fun previewBackup(text: String): IntArray {
        val root = org.json.JSONObject(text)
        if (root.optString("app") != Repository.BACKUP_APP_TAG) throw IllegalArgumentException("bad tag")
        fun count(key: String): Int = root.optJSONArray(key)?.length() ?: 0
        return intArrayOf(
            count("projects"), count("cabinetTypes"), count("instances"),
            count("logs"), count("faults")
        )
    }

    // ---------- 从备份找回被删记录（v2.22） ----------

    private fun doRollback(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // 明文JSON / WebDAV gzip快照按魔数统一识别解压
                val bytes = withContext(Dispatchers.IO) {
                    requireContext().contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IllegalStateException("无法读取文件")
                }
                val text = WebDavSync.decodeSnapshot(bytes)
                // 先只扫描：本机缺失行 + 备份/本机日志构成
                val p = withContext(Dispatchers.Default) { App.repo.rollbackPreview(text) }
                if (p.missingTotal == 0) {
                    showRollbackNoneDialog(p)
                    return@launch
                }
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.rollback_confirm_title)
                    .setIcon(android.R.drawable.ic_dialog_alert)
                    .setMessage(
                        getString(
                            R.string.rollback_confirm_msg,
                            p.missing.logs, p.missing.faults, p.missing.instances, p.missing.planned,
                            p.missing.projects, p.missing.types, p.missing.cands, p.missing.debuggers
                        )
                    )
                    .setPositiveButton(R.string.confirm) { _, _ ->
                        viewLifecycleOwner.lifecycleScope.launch {
                            try {
                                val r = App.repo.rollbackFromBackup(text, apply = true)
                                toast(getString(R.string.rollback_ok, r.total))
                                refreshStats()
                            } catch (e: Exception) {
                                toast(getString(R.string.op_failed, e.message ?: e.javaClass.simpleName))
                            }
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            } catch (e: Exception) {
                toast(R.string.restore_bad_file)
            }
        }
    }

    /** 「备份中无本机缺失记录」时，展示备份 vs 本机日志构成，帮用户判断所选备份是否丢失前的产物 */
    private fun showRollbackNoneDialog(p: RollbackPreview) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.rollback_none_title)
            .setIcon(android.R.drawable.ic_dialog_info)
            .setMessage(
                getString(
                    R.string.rollback_none_msg,
                    p.backupLogs,
                    p.backupLogs - p.backupFaultLogs - p.backupResolutionLogs,
                    p.backupFaultLogs, p.backupResolutionLogs, p.backupFaultRecords,
                    p.localLogs, p.localFaultLogs, p.localResolutionLogs, p.localFaultRecords
                )
            )
            .setPositiveButton(R.string.close, null)
            .show()
    }

    // ---------- 修复/撤销日志类型（v2.24/v2.25） ----------

    /** 把被故障记录指向却显示为「通过」的日志重分类为「故障」，备注=已解决故障现象的日志重分类为「消除」 */
    private fun doFixLogTypes() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val r = withContext(Dispatchers.Default) { App.repo.reclassifyLogTypes(preview = true) }
                if (r.total == 0) {
                    toast(R.string.fix_types_none)
                    return@launch
                }
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.fix_types_confirm_title)
                    .setIcon(android.R.drawable.ic_dialog_info)
                    .setMessage(getString(R.string.fix_types_confirm_msg, r.faultLogs, r.attachedFaults, r.resolutionLogs))
                    .setPositiveButton(R.string.confirm) { _, _ ->
                        viewLifecycleOwner.lifecycleScope.launch {
                            try {
                                val applied = App.repo.reclassifyLogTypes(preview = false)
                                FixLogStore.record(requireContext(), applied.applied)
                                toast(getString(R.string.fix_types_ok, applied.faultLogs, applied.resolutionLogs))
                                refreshStats()
                            } catch (e: Exception) {
                                toast(getString(R.string.op_failed, e.message ?: e.javaClass.simpleName))
                            }
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            } catch (e: Exception) {
                toast(getString(R.string.op_failed, e.message ?: e.javaClass.simpleName))
            }
        }
    }

    /** 撤销类型修复：把记录过的 (id→原类型) 全部还原（后手药） */
    private fun doUndoFixLogTypes() {
        viewLifecycleOwner.lifecycleScope.launch {
            val entries = withContext(Dispatchers.Default) { FixLogStore.all(requireContext()) }
            if (entries.isEmpty()) {
                toast(R.string.fix_types_undo_none)
                return@launch
            }
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.fix_types_undo_title)
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setMessage(getString(R.string.fix_types_undo_msg, entries.size))
                .setPositiveButton(R.string.confirm) { _, _ ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        try {
                            val n = App.repo.undoLogTypeFix(entries)
                            FixLogStore.clear(requireContext())
                            toast(getString(R.string.fix_types_undo_ok, n))
                            refreshStats()
                        } catch (e: Exception) {
                            toast(getString(R.string.op_failed, e.message ?: e.javaClass.simpleName))
                        }
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    companion object {
        const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        const val JSON_MIME = "application/json"
    }
}
