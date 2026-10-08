package com.xueweijian.eg2media.index

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo

/** 索引 worker 前台化（真机"索引中闪退"取证 Round 18：LMK 不杀前台服务 + 进度常驻可见） */
object IndexForeground {

    const val CHANNEL_ID = "eg2_indexing"
    const val NOTIF_ID_IMAGE = 1001
    const val NOTIF_ID_VIDEO = 1002

    fun info(context: Context, notifId: Int, title: String, text: String): ForegroundInfo {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "本地索引", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(notifId, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notifId, n)
        }
    }
}
