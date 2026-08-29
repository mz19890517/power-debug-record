package com.fieldlog.powerdebug

import android.app.Application
import com.fieldlog.powerdebug.data.Repository
import com.fieldlog.powerdebug.data.db.AppDatabase
import com.fieldlog.powerdebug.util.CrashLog
import com.fieldlog.powerdebug.util.SyncLog

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
