package com.lxpro.music

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lxpro.core.datastore.SettingsRepository
import com.lxpro.core.library.LocalMusicRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 开机自动扫描本地曲库（设置 → 本地与扫描）。
 *
 * ⚠️ 只在设置开启时工作；用 [goAsync] 把扫描挪出主线程，
 * 系统广播的执行窗口有限，超大曲库可能被系统提前掐断（不会损坏索引，最多下次进 App 再扫）。
 */
@AndroidEntryPoint
class BootScanReceiver : BroadcastReceiver() {

    @Inject
    lateinit var settings: SettingsRepository

    @Inject
    lateinit var library: LocalMusicRepository

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                if (settings.autoScanOnBoot.first()) {
                    runCatching { library.scan() }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
