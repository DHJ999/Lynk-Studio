package com.duan.lynkcluster

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * 让仪表盘脱离主界面存活。
 *
 * 前台服务只负责“保活 + 通知”，真正的显示逻辑在 [DashboardEngine]。
 * Android 14 起前台服务必须声明类型，这里用 specialUse（副屏显示）。
 */
class DashboardService : Service() {

    override fun onCreate() {
        super.onCreate()
        DashboardEngine.init(applicationContext)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val settings = DashboardSettings.load(this)
        if (!settings.enabled) {
            stopSelf()
            return START_NOT_STICKY
        }

        return try {
            val notification = buildNotification(settings)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }

            DashboardEngine.start(applicationContext, settings)
            START_STICKY
        } catch (t: Throwable) {
            Log.e(TAG, "启动仪表盘前台服务失败", t)
            stopSelf()
            START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        DashboardEngine.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "仪表盘",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "仪表盘运行状态"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(settings: DashboardSettings): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val theme = ClusterThemes.byId(settings.themeId)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("仪表盘运行中")
            .setContentText("${theme.label} · ${settings.mode.label}")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "lynk_cluster_dashboard"
        private const val NOTIFICATION_ID = 1107

        fun start(context: Context): Boolean = runCatching {
            context.startForegroundService(Intent(context, DashboardService::class.java))
            true
        }.getOrElse {
            Log.w(TAG, "无法启动仪表盘服务：${it.javaClass.simpleName}: ${it.message}")
            false
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, DashboardService::class.java))
            }.onFailure {
                Log.w(TAG, "停止仪表盘服务失败：${it.javaClass.simpleName}: ${it.message}")
            }
        }

        private const val TAG = "LynkCluster/Service"
    }
}
