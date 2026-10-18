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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.peertalk.app.rtc.PeerEngine

/**
 * Keeps the process alive while a session is open. The foreground-service *types* are chosen
 * from what is actually running, because Android 14+ refuses types whose permission is missing:
 *
 *  - [ACTION_SESSION]: chat only (dataSync). No mic or camera, so no permission is needed.
 *  - [ACTION_CALL]:    in a call. Adds microphone / camera when those are granted and in use.
 *  - [ACTION_SCREEN]:  start a screen share. Adds mediaProjection (stays while sharing).
 *  - [ACTION_STOP_SCREEN]: "Stop sharing" button on the notification.
 */
class CallService : Service() {

    companion object {
        const val ACTION_SESSION = "com.peertalk.app.SESSION"
        const val ACTION_CALL = "com.peertalk.app.CALL"
        const val ACTION_SCREEN = "com.peertalk.app.SCREEN"
        const val ACTION_STOP_SCREEN = "com.peertalk.app.STOP_SCREEN"
        private const val EXTRA_DATA = "projection_data"
        private const val CHANNEL_ID = "peertalk_call"
        private const val NOTIF_ID = 42
        private const val TAG = "PeerTalk"

        fun start(context: Context, action: String, projectionData: Intent? = null) {
            val i = Intent(context, CallService::class.java).setAction(action)
            if (projectionData != null) i.putExtra(EXTRA_DATA, projectionData)
            ContextCompat.startForegroundService(context, i)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_SESSION
        val engine = PeerEngine.get(this)

        if (action == ACTION_STOP_SCREEN) {
            engine.stopScreenShare()
            return START_NOT_STICKY
        }

        try {
            enterForeground(action, engine)
        } catch (e: Throwable) {
            // Android refused the foreground service (missing permission, wrong time, ...).
            // Report it instead of crashing the whole app.
            Log.e(TAG, "foreground service refused for $action", e)
            if (action == ACTION_SCREEN) engine.reportError("Android blocked screen sharing: ${e.message}")
            return START_NOT_STICKY
        }

        if (action == ACTION_SCREEN) {
            val data = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_DATA, Intent::class.java) }
            if (data != null) engine.startScreenShare(data)
            else engine.reportError("Screen share permission was lost. Try again.")
        }
        return START_NOT_STICKY
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun enterForeground(action: String, engine: PeerEngine) {
        val s = engine.state.value
        val sharing = action == ACTION_SCREEN || s.sharingScreen
        val inCall = action != ACTION_SESSION

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
        val text = when {
            sharing -> "Sharing your screen"
            inCall && s.cameraOn -> "Video call in progress"
            inCall -> "Voice call in progress"
            else -> "Chat connected"
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_peertalk)
            .setContentTitle("PeerTalk")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
        if (sharing) {
            val stop = PendingIntent.getService(
                this, 1, Intent(this, CallService::class.java).setAction(ACTION_STOP_SCREEN),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop sharing", stop)
        }
        val notification: Notification = builder.build()

        var type = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            if (sharing) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (inCall && granted(Manifest.permission.RECORD_AUDIO)) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            if (s.cameraOn && granted(Manifest.permission.CAMERA)) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            }
        }
        ServiceCompat.startForeground(this, NOTIF_ID, notification, type)
    }
}
