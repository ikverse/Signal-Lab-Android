package com.ikverse.signallab.model

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.ikverse.signallab.ledger.LegacyImporter
import com.ikverse.signallab.ledger.PreferenceStore
import com.ikverse.signallab.ledger.Preferences
import com.ikverse.signallab.update.UpdateController
import com.ikverse.signallab.update.UpdateState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What Android currently allows the app. */
data class Grants(val notifications: Boolean = true, val exactAlarms: Boolean = true, val batteryExempt: Boolean = false)

enum class Permission { NOTIFICATIONS, EXACT_ALARMS, BATTERY }

data class SettingsUi(
    val loaded: Boolean = false,
    val prefs: Preferences = Preferences(),
    val version: String = "",
    /** Where the update stands, in a sentence. */
    val update: String = "",
    /** The update button's words, when there is something it can do. */
    val updateAction: String? = null,
    /** What was brought over from Signal Lab 1. */
    val imported: String? = null,
    val grants: Grants = Grants(),
)

class SettingsModel(
    private val context: Context,
    private val prefs: PreferenceStore,
    private val updates: UpdateController,
    lastImport: StateFlow<LegacyImporter.Result?>,
    private val version: String,
    private val scope: CoroutineScope,
) {
    private val grants = MutableStateFlow(readGrants())

    val state: StateFlow<SettingsUi> = combine(prefs.preferences, updates.state, lastImport, grants) { p, u, imp, g ->
        SettingsUi(true, p, version, updateWords(u), updateAction(u), importWords(imp), g)
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), SettingsUi())

    /** After coming back from one of Android's settings screens. */
    fun refreshGrants() {
        grants.value = readGrants()
    }

    fun set(key: String, value: String) {
        scope.launch { prefs.set(key, value) }
    }

    fun setBool(key: String, value: Boolean) {
        scope.launch { prefs.setBool(key, value) }
    }

    fun update() {
        scope.launch {
            when (updates.state.value) {
                is UpdateState.Available, is UpdateState.NeedsPermission, is UpdateState.Failed -> updates.install()
                else -> updates.checkNow()
            }
        }
    }

    private fun readGrants(): Grants {
        val notifications = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val alarms = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
        val battery = context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true
        return Grants(notifications, alarms, battery)
    }

    companion object {
        fun updateWords(u: UpdateState): String = when (u) {
            is UpdateState.Idle -> "Checks for a new version once a day."
            is UpdateState.UpToDate -> "Up to date."
            is UpdateState.Available -> "Version ${u.release.version} is available."
            is UpdateState.Downloading -> "Downloading version ${u.release.version}…"
            is UpdateState.NeedsPermission -> "Android needs your permission to install updates from this app."
            is UpdateState.Handed -> "Android's installer is open."
            is UpdateState.Failed -> u.message
            UpdateState.Checking -> "Checking…"
        }

        fun updateAction(u: UpdateState): String? = when (u) {
            is UpdateState.Available -> "Install"
            is UpdateState.NeedsPermission, is UpdateState.Failed -> "Try again"
            is UpdateState.Downloading, is UpdateState.Handed, UpdateState.Checking -> null
            else -> "Check now"
        }

        fun importWords(r: LegacyImporter.Result?): String? = when (r) {
            is LegacyImporter.Result.Imported -> "Brought over from Signal Lab 1: ${r.log.calls} trades, ${r.log.reports} reports, ${r.log.pins} pinned coins."
            is LegacyImporter.Result.AlreadyImported -> "Signal Lab 1's record was brought over on first start."
            else -> null
        }
    }
}
