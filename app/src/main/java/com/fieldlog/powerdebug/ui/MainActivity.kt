package com.fieldlog.powerdebug.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import com.fieldlog.powerdebug.App
import com.fieldlog.powerdebug.R
import com.fieldlog.powerdebug.databinding.ActivityMainBinding
import com.fieldlog.powerdebug.ui.device.DeviceFragment
import com.fieldlog.powerdebug.ui.log.LogListFragment
import com.fieldlog.powerdebug.ui.tools.ToolsFragment
import com.fieldlog.powerdebug.util.SyncStore
import com.fieldlog.powerdebug.util.WebDavSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (savedInstanceState == null) show(LogListFragment())

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_logs -> show(LogListFragment())
                R.id.nav_device -> show(DeviceFragment())
                R.id.nav_tools -> show(ToolsFragment())
            }
            true
        }

        maybeAutoSync()

        // 兜底自愈：应用启动即修正「标记未通过但故障记录已全部不存在」的幽灵测试项，
        // 保证项目/柜子卡片与测试清单不会残留「原因见日志但无故障」的假未通过状态
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { App.repo.healGhostFailures() }
        }
    }

    /**
     * 打开应用时自动双向同步（开关开启且已登录时）：
     * 推送本机快照并把其他测试员的快照合并进来，60秒节流防止旋转重建频繁触发。
     */
    private fun maybeAutoSync() {
        if (!SyncStore.autoUpload(this)) return
        if (SyncStore.currentUser(this) == null || SyncStore.config(this) == null) return
        if (!SyncStore.shouldAutoSyncNow(this)) return
        val ctx = this
        CoroutineScope(Dispatchers.IO).launch {
            try { WebDavSync.syncAll(ctx) } catch (_: Exception) {}
        }
    }

    private fun show(f: Fragment) {
        supportFragmentManager.commit {
            replace(R.id.container, f)
        }
    }
}
