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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import com.ikverse.signallab.model.Permission
import com.ikverse.signallab.runner.AndroidCallAlerts
import com.ikverse.signallab.view.AppModels
import com.ikverse.signallab.view.analyst.AnalystActions
import com.ikverse.signallab.view.SignalLabRoot
import com.ikverse.signallab.view.chart.ChartHost
import com.ikverse.signallab.view.chart.LocalChartHost
import com.ikverse.signallab.view.settings.SettingsActions
import com.ikverse.signallab.view.theme.SignalLabTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The one screen. It holds the app's frame and what only an activity can do: Android's permission screens and file pickers. */
class MainActivity : ComponentActivity() {
    private val graph get() = (application as SignalLabApplication).graph

    /** The chart's web view, built once and kept while this screen lives. */
    private val chart by lazy { ChartHost(this) }

    /** A call to open, from a tapped notification. */
    private val openCall = mutableStateOf<Long?>(null)

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { graph.settings.refreshGrants() }

    private val saveCsv = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val text = graph.ledgerCsv()
            withContext(Dispatchers.IO) { contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dark = SystemBarStyle.dark(0xFF0A0C0E.toInt())
        enableEdgeToEdge(statusBarStyle = dark, navigationBarStyle = dark)
        if (savedInstanceState == null) openCall.value = callIn(intent)
        val models = AppModels(
            graph.today, graph.ledgerModel, graph.strategiesModel, graph.markets, graph.calls, graph.settings, graph.preferences,
            SettingsActions(graph.settings::set, graph.settings::setBool, graph.settings::update, ::openPermission),
            exportCsv = { saveCsv.launch("signal-lab-ledger.csv") },
            analyst = graph.analystModel,
            analystActions = object : AnalystActions {
                private val a = graph.analystModel
                override fun send(text: String) = a.send(text)
                override fun stop() = a.stop()
                override fun saveKey(key: String) = a.saveKey(key)
                override fun removeKey() = a.removeKey()
                override fun setModel(id: String) = a.setModel(id)
                override fun setCap(usd: Double) = a.setCap(usd)
                override fun setBrief(on: Boolean) = a.setBrief(on)
                override fun setReview(on: Boolean) = a.setReview(on)
                override fun clearChat() = a.clearChat()
                override fun shareToClaude(question: String) {
                    lifecycleScope.launch {
                        val text = a.handoffText(question)
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                        val claude = Intent(send).setPackage(CLAUDE_APP)
                        val target = if (claude.resolveActivity(packageManager) != null) claude else Intent.createChooser(send, "Ask Claude")
                        runCatching { startActivity(target) }
                    }
                }

                override fun openKeyPage() {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(CONSOLE_KEYS))) }
                }
            },
        )
        setContent {
            SignalLabTheme {
                CompositionLocalProvider(LocalChartHost provides chart) {
                    SignalLabRoot(models, openCall.value)
                }
            }
        }
        askForNotificationsOnce()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        callIn(intent)?.let { openCall.value = it }
    }

    override fun onResume() {
        super.onResume()
        graph.settings.refreshGrants()
        lifecycleScope.launch { graph.updates.checkIfDue() }
    }

    override fun onDestroy() {
        super.onDestroy()
        chart.view.destroy()
    }

    private fun callIn(intent: Intent?): Long? = intent?.getLongExtra(AndroidCallAlerts.EXTRA_CALL, -1L)?.takeIf { it > 0 }

    /** Calls are only worth having if they reach you: on Android 13 and later the app asks once, on first start. */
    private fun askForNotificationsOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        lifecycleScope.launch {
            val dao = graph.ledger.dao()
            if (dao.setting(ASKED_NOTIFICATIONS) != null || graph.settings.state.value.grants.notifications && graph.settings.state.value.loaded) return@launch
            graph.prefsStore.set(ASKED_NOTIFICATIONS, "1")
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun openPermission(p: Permission) {
        when (p) {
            Permission.NOTIFICATIONS ->
                launch(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            Permission.EXACT_ALARMS ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) launch(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
                else launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            Permission.BATTERY ->
                if (graph.settings.state.value.grants.batteryExempt) launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                else launch(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
    }

    private fun launch(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // Some phones lack the screen asked for: the app's own page in Settings is always there.
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    private companion object {
        const val ASKED_NOTIFICATIONS = "asked_notifications_v2"
        const val CONSOLE_KEYS = "https://console.anthropic.com/settings/keys"
        const val CLAUDE_APP = "com.anthropic.claude"
    }
}
