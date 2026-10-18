package com.peertalk.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.peertalk.app.rtc.PeerEngine

/**
 * Keeps the process alive while a session is open.
 *
 *  - [ACTION_SESSION]: chat only. No microphone type, so no mic permission is needed.
 *  - [ACTION_CALL]:    voice call. Adds the microphone type, only if RECORD_AUDIO is granted.
 *  - [ACTION_SCREEN]:  screen share. Adds the mediaProjection type (Android 14+ requirement).
 */
class CallService : Service() {

    companion object {
        const val ACTION_SESSION = "com.peertalk.app.SESSION"
        const val ACTION_CALL = "com.peertalk.app.CALL"
        const val ACTION_SCREEN = "com.peertalk.app.SCREEN"
        private const val EXTRA_DATA = "projection_data"
        private const val CHANNEL_ID = "peertalk_call"
        private const val NOTIF_ID = 42

        fun start(context: Context, action: String, projectionData: Intent? = null) {
            val i = Intent(context, CallService::class.java).setAction(action)
            if (projectionData != null) i.putExtra(EXTRA_DATA, projectionData)
            ContextCompat.startForegroundService(context, i)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_SESSION
        enterForeground(action)
        if (action == ACTION_SCREEN) {
            val data = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_DATA, Intent::class.java) }
            if (data != null) PeerEngine.get(this).startScreenShare(data)
        }
        return START_NOT_STICKY
    }

    private fun enterForeground(action: String) {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Active session", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = when (action) {
            ACTION_SCREEN -> "Sharing your screen"
            ACTION_CALL -> "Voice call in progress"
            else -> "Chat connected"
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentTitle("PeerTalk")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()

        var type = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            val micOk = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            if (micOk && action != ACTION_SESSION) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (action == ACTION_SCREEN) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        }
        ServiceCompat.startForeground(this, NOTIF_ID, notification, type)
    }
}
