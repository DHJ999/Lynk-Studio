package com.duan.lynkcluster

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机自启。只有用户同时打开了「启用仪表盘」和「开机自启」才会拉起服务。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val settings = DashboardSettings.load(context)
        if (settings.enabled && settings.autoStart) {
            DashboardService.start(context)
        }
    }
}
