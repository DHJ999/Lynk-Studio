package com.duan.lynkcluster

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * 媒体会话注入探针。
 *
 * 目的：验证仪表**右侧可变卡片**（音乐/油耗那个位置）的数据是否来自 Android 的
 * MediaSession。如果成立，我们就能往右侧卡片里推任意文字/封面，等于「编辑右侧内容」。
 *
 * 链路推断：
 *   MediaSession（本类） → com.geely.dimservice/impl/MediaImpl → DIM/VDBus → QNX 右侧卡片
 *
 * 用法（不重编译即可换内容）：
 *   adb shell am start-foreground-service -n com.duan.lynkcluster/.MediaProbeService \
 *       --es title "标题" --es artist "副标题" --es album "专辑"
 * 关闭：
 *   adb shell am stop-service -n com.duan.lynkcluster/.MediaProbeService
 *
 * 注意：本类只推元数据，不播放音频。部分车机会过滤掉「无音频输出」的会话，
 * 如果是那样，右侧不会有反应，需要改成真播一段静音音轨（见 probePlaybackState 注释）。
 */
class MediaProbeService : Service() {

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForegroundCompat()
        activate(sessionTitle = null)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 允许通过 adb 反复改内容
        intent?.let { activate(sessionTitle = it) }
        return START_STICKY
    }

    private fun activate(sessionTitle: Intent?) {
        val title = sessionTitle?.getStringExtra("title") ?: "LYNK CLUSTER"
        val artist = sessionTitle?.getStringExtra("artist") ?: "仪表右侧内容注入"
        val album = sessionTitle?.getStringExtra("album") ?: "PROBE"

        val s = session ?: MediaSession(this, MEDIA_SESSION_TAG).also {
            session = it
            it.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS)
        }

        s.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, album)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, 240_000L)
                .build(),
        )

        s.setPlaybackState(
            PlaybackState.Builder()
                .setState(PlaybackState.STATE_PLAYING, 0L, 1.0f)
                .setActions(
                    PlaybackState.ACTION_PLAY or
                        PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE,
                )
                .build(),
        )

        s.isActive = true
        Log.i(TAG, "MediaSession 已激活: title=$title artist=$artist")
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "媒体注入探针", NotificationManager.IMPORTANCE_LOW).apply {
                description = "仪表右侧卡片媒体会话测试"
                setShowBadge(false)
            },
        )
    }

    private fun startForegroundCompat() {
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("媒体会话注入中")
            .setContentText("用于测试仪表右侧卡片数据源")
            .setOngoing(true)
            .setShowWhen(false)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        session?.let {
            it.isActive = false
            it.release()
        }
        session = null
        Log.i(TAG, "MediaSession 已释放")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "LynkCluster/MediaProbe"
        private const val CHANNEL_ID = "lynk_cluster_media_probe"
        private const val NOTIFICATION_ID = 1109
        private const val MEDIA_SESSION_TAG = "LynkClusterProbe"
    }
}
