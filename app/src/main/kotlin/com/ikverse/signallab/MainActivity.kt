package com.ikverse.signallab

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import com.ikverse.signallab.ui.FoldInfo
import kotlinx.coroutines.flow.MutableStateFlow
import com.ikverse.signallab.analyst.ClaudeHandoff
import com.ikverse.signallab.analyst.Handoff
import com.ikverse.signallab.scan.Notifier
import com.ikverse.signallab.ui.DimLevel
import com.ikverse.signallab.ui.PermissionPrompt
import com.ikverse.signallab.ui.SignalLabApp
import com.ikverse.signallab.ui.WebPool
import com.ikverse.signallab.ui.hideStatusBar
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The one screen. It holds the app's frame and the system hand-offs the frame cannot do itself: permissions, opening a coin from a
 * notification, handing a question to the Claude app, and keeping an answer shared back from it.
 */
class MainActivity : ComponentActivity() {
    private val app get() = application as SignalLabApplication

    /** The chart's and Learn's web views, built once and kept while this screen lives; see [WebPool]. */
    private val webPool by lazy { WebPool(this) }

    /** Where the fold is, while the screen has one. */
    private val fold = MutableStateFlow<FoldInfo?>(null)

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        lifecycleScope.launch { app.graph.permissions.evaluate() }
    }

    /** Google's account picker and consent screen for sync, and what the user chose there. */
    private val syncConsent = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        lifecycleScope.launch { app.graph.deviceSync.onConsent(result.resultCode == RESULT_OK, result.data) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dark = SystemBarStyle.dark(0xFF050505.toInt())
        enableEdgeToEdge(statusBarStyle = dark, navigationBarStyle = dark)
        // A notification tap that starts the app opens its coin, and a shared answer is kept. Not again after the system restores the screen.
        if (savedInstanceState == null) {
            app.model.open(intent.getStringExtra(Notifier.EXTRA_LINK))
            receiveShared(intent)
        }
        setContent {
            val fold by fold.collectAsState()
            SignalLabApp(app.model, debug = BuildConfig.DEBUG, webPool = webPool, fold = fold)
        }
        lifecycleScope.launch {
            // A foldable's fold, as the app's panes and the held-up half of a half-open phone need it. A phone that does not fold never emits one.
            WindowInfoTracker.getOrCreate(this@MainActivity).windowLayoutInfo(this@MainActivity).collect { info ->
                val density = resources.displayMetrics.density
                fold.value = info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()?.let { f ->
                    val horizontal = f.orientation == FoldingFeature.Orientation.HORIZONTAL
                    FoldInfo(
                        horizontal, f.state == FoldingFeature.State.HALF_OPENED,
                        (if (horizontal) f.bounds.top else f.bounds.left) / density,
                        (if (horizontal) f.bounds.height() else f.bounds.width()) / density,
                    )
                }
            }
        }
        applyStatusBar(resources.configuration)
        lifecycleScope.launch {
            app.graph.permissions.accepted.collect { openSystemPrompt(it) }
        }
        lifecycleScope.launch {
            app.model.openSystemScreen.collect { openSettingsScreen(it) }
        }
        lifecycleScope.launch {
            app.model.handoffs.collect { openClaude(it) }
        }
        lifecycleScope.launch {
            app.graph.deviceSync.consent.collect { syncConsent.launch(IntentSenderRequest.Builder(it.intentSender).build()) }
        }
        lifecycleScope.launch {
            // While the app is on screen: keep the screen on and dim if the user asked for that.
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.model.settings.settings.map { it.dimScreen to it.dimLevel }.distinctUntilChanged().collect { (on, level) -> applyDim(on, level) }
            }
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

    override fun onStart() {
        super.onStart()
        // Once the screen has drawn and nothing is being touched, start Android's web engine, so that the first chart or Learn page
        // does not pay for it on a tap.
        Looper.myQueue().addIdleHandler {
            webPool.warmUp()
            false
        }
    }

    override fun onStop() {
        super.onStop()
        // Away from the screen: give back the memory of the web views that are not on show. The ones on show stay, because they are
        // still part of the screen that will be there when the app comes back.
        webPool.releaseIdle()
    }

    override fun onDestroy() {
        super.onDestroy()
        webPool.releaseAll()
    }

    /**
     * Keeps the screen on and turns this window's brightness down to [level], or gives both back. It only touches this window, so
     * the phone's own brightness setting is untouched and returns the moment the app is left.
     */
    private fun applyDim(on: Boolean, level: DimLevel) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.also {
            it.screenBrightness = if (on) level.brightness else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
    }

    /** Turning the phone does not rebuild this screen (see the manifest), so the status bar is decided again here. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyStatusBar(newConfig)
    }

    /**
     * Hides the status bar on a phone held sideways, where the screen is short (see [hideStatusBar]), and shows it again otherwise. A
     * swipe down from the top edge shows it for a moment. The gesture bar at the bottom is never hidden.
     */
    private fun applyStatusBar(config: Configuration) {
        val bars = WindowCompat.getInsetsController(window, window.decorView)
        bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (hideStatusBar(config.screenWidthDp, config.screenHeightDp)) bars.hide(WindowInsetsCompat.Type.statusBars())
        else bars.show(WindowInsetsCompat.Type.statusBars())
    }

    /** A notification tapped, or an answer shared, while the app is already open. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        app.model.open(intent.getStringExtra(Notifier.EXTRA_LINK))
        receiveShared(intent)
    }

    /** Text shared into the app from another (Claude's answer, shared back): kept as an Analyst report. */
    private fun receiveShared(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND || intent.type?.startsWith("text/") != true) return
        intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.let { app.model.receiveShared(it) }
    }

    /** Opens the Claude app with the question as a draft and the data file attached; Android's share menu if Claude will not take it. */
    private fun openClaude(handoff: Handoff) {
        try {
            startActivity(ClaudeHandoff.intent(this, handoff))
        } catch (_: ActivityNotFoundException) {
            startActivity(ClaudeHandoff.intent(this, handoff, toClaude = false))
        }
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
        // Opening the app brings in what the other devices did (no more often than sync's own spacing allows).
        app.graph.deviceSync.requestSync()
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
