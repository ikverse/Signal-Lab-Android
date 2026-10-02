package com.ikverse.signallab

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.ikverse.signallab.ui.DebugPanel

/** The real screens arrive in M5. Until then a debug build shows the data layer working, and a release build shows its name. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as SignalLabApplication
        setContent {
            if (BuildConfig.DEBUG) {
                DebugPanel(app.debugState)
            } else {
                Box(Modifier.fillMaxSize().background(Color(0xFF050505)), contentAlignment = Alignment.Center) {
                    Text("Signal Lab ${BuildConfig.VERSION_NAME}", color = Color(0xFFD1D4DC))
                }
            }
        }
    }
}
