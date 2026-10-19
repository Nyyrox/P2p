package com.peertalk.app

import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.peertalk.app.rtc.CallPhase
import com.peertalk.app.rtc.PeerEngine
import com.peertalk.app.ui.PeerTalkApp
import com.peertalk.app.ui.PeerTalkTheme

class MainActivity : ComponentActivity() {

    /** True while Android shows the call in its small floating window. */
    private val inPip = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val engine = PeerEngine.get(this)
        addOnPictureInPictureModeChangedListener { info -> inPip.value = info.isInPictureInPictureMode }
        setContent {
            PeerTalkTheme {
                PeerTalkApp(engine, inPip.value)
            }
        }
    }

    /** Home / recents pressed during a call: keep the call visible in a floating window. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (PeerEngine.get(this).state.value.callPhase != CallPhase.ACTIVE) return
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        try {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder().setAspectRatio(Rational(9, 16)).build(),
            )
        } catch (_: Throwable) {
            // Not allowed right now (e.g. disabled in system settings): the call just keeps running in the background.
        }
    }
}
