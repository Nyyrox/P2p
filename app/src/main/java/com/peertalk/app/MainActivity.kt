package com.peertalk.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.peertalk.app.rtc.PeerEngine
import com.peertalk.app.ui.PeerTalkApp
import com.peertalk.app.ui.PeerTalkTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val engine = PeerEngine.get(this)
        setContent {
            PeerTalkTheme {
                PeerTalkApp(engine)
            }
        }
    }
}
