package com.ikverse.signallab.scan

import android.app.AlarmManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import com.ikverse.signallab.data.SettingsStore
import com.ikverse.signallab.ui.PermissionPrompt
import com.ikverse.signallab.ui.PermissionPrompts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the system currently allows. */
data class Grants(val notifications: Boolean, val exactAlarms: Boolean, val batteryExempt: Boolean)

/**
 * Asks for what background scanning needs, one thing at a time and each at most once, and only after
 * the user has a list switched on: asking before there is anything to scan would be asking for no reason.
 * A refusal is respected; the settings screen (M5) is where it is changed later.
 */
class PermissionFlow(
    private val context: Context,
    private val settings: SettingsStore,
    private val hasActiveCoins: suspend () -> Boolean,
    private val scope: CoroutineScope,
    private val sdk: Int = Build.VERSION.SDK_INT,
) : PermissionPrompts {
    private val state = MutableStateFlow<PermissionPrompt?>(null)
    private val accepts = MutableSharedFlow<PermissionPrompt>(extraBufferCapacity = 4)

    override val pending: StateFlow<PermissionPrompt?> = state.asStateFlow()

    /** Prompts the user accepted; the activity opens the matching system screen. */
    val accepted: SharedFlow<PermissionPrompt> = accepts.asSharedFlow()

    fun grants(): Grants {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        return Grants(
            notifications = NotificationManagerCompat.from(context).areNotificationsEnabled(),
            exactAlarms = sdk < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms(),
            batteryExempt = power.isIgnoringBatteryOptimizations(context.packageName),
        )
    }

    /** Works out which prompt, if any, is next. Call after a list is switched on, and when the app returns to the screen. */
    suspend fun evaluate() {
        val asked = PermissionPrompt.entries.filter { settings.getBoolean(SettingsStore.ASKED_PREFIX + it.name, false) }.toSet()
        state.value = next(sdk, hasActiveCoins(), grants(), asked)
    }

    override fun accept(prompt: PermissionPrompt) {
        scope.launch {
            settings.setBoolean(SettingsStore.ASKED_PREFIX + prompt.name, true)
            state.value = null
            accepts.emit(prompt)
        }
    }

    override fun decline(prompt: PermissionPrompt) {
        scope.launch {
            settings.setBoolean(SettingsStore.ASKED_PREFIX + prompt.name, true)
            evaluate()
        }
    }

    companion object {
        /**
         * The next prompt: alerts, then exact alarms, then battery, skipping what is already allowed,
         * what this Android version does not ask for, and what has been asked before.
         */
        fun next(sdk: Int, hasActiveCoins: Boolean, grants: Grants, asked: Set<PermissionPrompt>): PermissionPrompt? {
            if (!hasActiveCoins) return null
            return when {
                sdk >= Build.VERSION_CODES.TIRAMISU && !grants.notifications && PermissionPrompt.NOTIFICATIONS !in asked -> PermissionPrompt.NOTIFICATIONS
                sdk >= Build.VERSION_CODES.S && !grants.exactAlarms && PermissionPrompt.EXACT_ALARMS !in asked -> PermissionPrompt.EXACT_ALARMS
                !grants.batteryExempt && PermissionPrompt.BATTERY !in asked -> PermissionPrompt.BATTERY
                else -> null
            }
        }
    }
}
