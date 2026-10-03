package com.ikverse.signallab

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ikverse.signallab.scan.Notifier
import com.ikverse.signallab.ui.PermissionPrompt
import com.ikverse.signallab.ui.SignalLabApp
import kotlinx.coroutines.launch

/** The one screen. It holds the app's frame and the system hand-offs the frame cannot do itself: permissions, and opening a coin from a notification. */
class MainActivity : ComponentActivity() {
    private val app get() = application as SignalLabApplication

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        lifecycleScope.launch { app.graph.permissions.evaluate() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dark = SystemBarStyle.dark(0xFF050505.toInt())
        enableEdgeToEdge(statusBarStyle = dark, navigationBarStyle = dark)
        // A notification tap that starts the app opens its coin. Not again after the system restores the screen.
        if (savedInstanceState == null) app.model.open(intent.getStringExtra(Notifier.EXTRA_LINK))
        setContent { SignalLabApp(app.model, debug = BuildConfig.DEBUG) }
        lifecycleScope.launch {
            app.graph.permissions.accepted.collect { openSystemPrompt(it) }
        }
        lifecycleScope.launch {
            app.model.openSystemScreen.collect { openSettingsScreen(it) }
        }
        lifecycleScope.launch {
            // While the app is on screen: keep the background service in step with the active lists, and ask for what
            // scanning needs once there is something to scan.
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.graph.watchlists.lists.collect {
                    app.graph.syncService(applicationContext)
                    app.graph.permissions.evaluate()
                }
            }
        }
    }

    /** A notification tapped while the app is already open. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        app.model.open(intent.getStringExtra(Notifier.EXTRA_LINK))
    }

    override fun onResume() {
        super.onResume()
        // Returning from a system settings screen: the answer may have changed, so ask again what is next.
        lifecycleScope.launch {
            app.graph.permissions.evaluate()
            app.model.refreshSettings()
        }
        // The quiet daily look for a newer release; skipped if one answered in the last day.
        lifecycleScope.launch { app.graph.updates.checkIfDue() }
    }

    private fun launch(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            // Some phones have no such screen: the app's own page in Settings is always there.
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    /** The first-run ask: the user said yes to the explanation, so show Android's own prompt. */
    private fun openSystemPrompt(prompt: PermissionPrompt) {
        when (prompt) {
            PermissionPrompt.NOTIFICATIONS ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            PermissionPrompt.EXACT_ALARMS ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    launch(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
                }
            PermissionPrompt.BATTERY ->
                launch(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
    }

    /**
     * Settings asked for one of Android's own screens. Alerts go to the app's notification settings (Android only shows its prompt
     * once, so it cannot be asked again), alarms to their page, and battery to the exemption prompt, or the list if already exempt.
     */
    private fun openSettingsScreen(prompt: PermissionPrompt) {
        when (prompt) {
            PermissionPrompt.NOTIFICATIONS ->
                launch(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            PermissionPrompt.EXACT_ALARMS ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    launch(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
                } else {
                    launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                }
            PermissionPrompt.BATTERY ->
                if (app.graph.permissions.grants().batteryExempt) launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                else launch(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
    }
}
