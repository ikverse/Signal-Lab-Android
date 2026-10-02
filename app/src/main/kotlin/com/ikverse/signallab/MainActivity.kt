package com.ikverse.signallab

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ikverse.signallab.ui.DebugPanel
import com.ikverse.signallab.ui.PermissionDialogs
import com.ikverse.signallab.ui.PermissionPrompt
import kotlinx.coroutines.launch

/** The real screens arrive in M5. Until then a debug build shows the data layer working, and a release build shows its name. */
class MainActivity : ComponentActivity() {
    private val app get() = application as SignalLabApplication
    private var prices: AutoCloseable? = null

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        lifecycleScope.launch { app.graph.permissions.evaluate() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PermissionDialogs(app.graph.permissions)
            if (BuildConfig.DEBUG) {
                DebugPanel(app.debugState)
            } else {
                Box(Modifier.fillMaxSize().background(Color(0xFF050505)), contentAlignment = Alignment.Center) {
                    Text("Signal Lab ${BuildConfig.VERSION_NAME}", color = Color(0xFFD1D4DC))
                }
            }
        }
        lifecycleScope.launch {
            app.graph.permissions.accepted.collect { openSystemPrompt(it) }
        }
        lifecycleScope.launch {
            // While the app is on screen: follow the active lists, keep the background service in step with
            // them, ask for what scanning needs once there is something to scan, and stream prices for them.
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.graph.watchlists.lists.collect { lists ->
                    app.graph.syncService(applicationContext)
                    app.graph.permissions.evaluate()
                    prices?.close()
                    prices = app.graph.priceFeed.watch(lists.filter { it.active }.flatMap { it.symbols }.toSet())
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Returning from a system settings screen: the answer may have changed, so ask again what is next.
        lifecycleScope.launch { app.graph.permissions.evaluate() }
    }

    override fun onStop() {
        prices?.close()
        prices = null
        super.onStop()
    }

    private fun openSystemPrompt(prompt: PermissionPrompt) {
        when (prompt) {
            PermissionPrompt.NOTIFICATIONS ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            PermissionPrompt.EXACT_ALARMS ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
                }
            PermissionPrompt.BATTERY ->
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
    }
}
