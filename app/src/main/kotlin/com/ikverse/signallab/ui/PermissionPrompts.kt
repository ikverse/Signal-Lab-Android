package com.ikverse.signallab.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow

/** The three things scanning in the background needs the user to allow, in the order they are asked. */
enum class PermissionPrompt(val title: String, val text: String) {
    NOTIFICATIONS(
        "Allow alerts?",
        "Signal Lab can tell you when a paper trade opens or closes. Without this the alerts still appear inside the app.",
    ),
    EXACT_ALARMS(
        "Wake exactly on the hour?",
        "Candles close on the hour. With \"Alarms & reminders\" allowed, Signal Lab wakes right then. Without it Android may " +
            "delay a scan by minutes, and a signal noticed after its entry candle has ended is not traded.",
    ),
    BATTERY(
        "Keep scanning with the screen off?",
        "Android stops apps it thinks are idle. Allow Signal Lab to ignore battery optimisation so scans keep running overnight. " +
            "On Samsung phones also check that Signal Lab is not in the sleeping apps list under Battery.",
    ),
}

/** What the permission dialogs may see and do. The system screens they lead to are opened by the activity. */
interface PermissionPrompts {
    /** The prompt to show now, or null. */
    val pending: StateFlow<PermissionPrompt?>

    /** The user chose to continue: the activity opens the system's own prompt. */
    fun accept(prompt: PermissionPrompt)

    /** The user chose "Not now". It is remembered, and the prompt is not shown again. */
    fun decline(prompt: PermissionPrompt)
}

@Composable
fun PermissionDialogs(prompts: PermissionPrompts) {
    val prompt = prompts.pending.collectAsStateWithLifecycle().value ?: return
    MaterialTheme(colorScheme = darkColorScheme(surface = Color(0xFF131722), onSurface = Color(0xFFD1D4DC), primary = Palette.Accent)) {
        AlertDialog(
            onDismissRequest = { prompts.decline(prompt) },
            title = { Text(prompt.title) },
            text = { Text(prompt.text) },
            confirmButton = { TextButton(onClick = { prompts.accept(prompt) }) { Text("Continue") } },
            dismissButton = { TextButton(onClick = { prompts.decline(prompt) }) { Text("Not now") } },
        )
    }
}
