package com.powerdebug.record

import android.app.Application
import com.powerdebug.record.data.Repository
import com.powerdebug.record.data.db.AppDatabase
import com.powerdebug.record.util.CrashLog
import com.powerdebug.record.util.SyncLog

class App : Application() {

    companion object {
        lateinit var db: AppDatabase
            private set
        lateinit var repo: Repository
            private set
    }

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        SyncLog.append(this, "APP启动 v${packageManager.getPackageInfo(packageName, 0).versionName}")
        db = AppDatabase.build(this)
        repo = Repository(db)
    }
}
