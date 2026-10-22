package com.ikverse.signallab.view.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ikverse.signallab.ledger.Preferences
import com.ikverse.signallab.model.Permission
import com.ikverse.signallab.model.SettingsUi
import com.ikverse.signallab.view.common.Overline
import com.ikverse.signallab.view.common.Panel
import com.ikverse.signallab.view.common.QuietButton
import com.ikverse.signallab.view.common.RowsPanel
import com.ikverse.signallab.view.common.SwitchRow
import com.ikverse.signallab.view.common.TapRow
import com.ikverse.signallab.view.theme.Ink
import com.ikverse.signallab.view.theme.Type
import java.util.Locale

/** What the screen can ask for. */
class SettingsActions(
    val set: (String, String) -> Unit,
    val setBool: (String, Boolean) -> Unit,
    val update: () -> Unit,
    val openPermission: (Permission) -> Unit,
    val syncSignIn: () -> Unit = {},
    val syncNow: () -> Unit = {},
    val syncOff: () -> Unit = {},
)

@Composable
fun SettingsScreen(state: SettingsUi, actions: SettingsActions, modifier: Modifier = Modifier) {
    if (!state.loaded) {
        Box(modifier.fillMaxSize().testTag("settings-loading"))
        return
    }
    val p = state.prefs
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("settings"),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Group("Copying calls on Binance") {
            RowsPanel(rows = listOf(
                @Composable { NumberRow("Money I trade with, USDT", p.tradeMoney ?: 0.0, 0, "trade-money") { actions.set(Preferences.TRADE_MONEY, it.toString()) } },
            ))
            Text("Your own Binance balance. Each call then says how many USDT to buy, sized the same way as on paper. Signal Lab never trades for you.", style = Type.Caption)
        }
        Group("Paper account") {
            RowsPanel(rows = listOf(
                @Composable { NumberRow("Starting size, USDT", p.accountSize, 0, "account") { actions.set(Preferences.ACCOUNT_SIZE, it.toString()) } },
                @Composable { NumberRow("Round-trip fees on BTC and ETH, %", p.costMajors * 100, 2, "cost-majors") { actions.set(Preferences.COST_MAJORS, (it / 100).toString()) } },
                @Composable { NumberRow("Round-trip fees on other coins, %", p.costOthers * 100, 2, "cost-others") { actions.set(Preferences.COST_OTHERS, (it / 100).toString()) } },
            ))
            Text("Fees include the spread and slippage of a market order. New calls use them; finished calls keep the fees they were charged.", style = Type.Caption)
        }
        Group("Calls and data") {
            RowsPanel(rows = listOf(
                @Composable { SwitchRow("Notify me", "New calls and exits of the strategies that are on", p.notifications) { actions.setBool(Preferences.NOTIFICATIONS, it) } },
                @Composable { SwitchRow("Futures data", "Funding and open interest, for the market line", p.futuresData) { actions.setBool(Preferences.FUTURES, it) } },
                @Composable {
                    SwitchRow("Use Binance.US", "For networks that cannot reach Binance.com; it has no futures data", p.binanceUs) {
                        actions.set(Preferences.DATA_HOST, if (it) Preferences.HOST_US else "https://api.binance.com")
                    }
                },
            ))
        }
        Group("What Android allows") {
            RowsPanel(rows = listOf(
                @Composable { GrantRow("Notifications", state.grants.notifications) { actions.openPermission(Permission.NOTIFICATIONS) } },
                @Composable { GrantRow("Exact alarms, for the daily close", state.grants.exactAlarms) { actions.openPermission(Permission.EXACT_ALARMS) } },
                @Composable { GrantRow("No battery limits, so Samsung does not put it to sleep", state.grants.batteryExempt) { actions.openPermission(Permission.BATTERY) } },
            ))
        }
        Group("Sync between devices") { SyncPanel(state.sync, actions) }
        Group("Analyst") {
            Panel {
                Text(
                    "The analyst's Claude API key, model, monthly budget, daily brief and weekly review are on the Analyst tab, under Key, model and budget.",
                    style = Type.Small,
                )
            }
        }
        Group("Updates") {
            Panel {
                Text("Version ${state.version}", style = Type.Body.copy(color = Ink.Text))
                Text(state.update, style = Type.Small, modifier = Modifier.testTag("update-line"))
                state.updateAction?.let { QuietButton(it, actions.update, Modifier.testTag("update-button")) }
            }
        }
        Panel {
            state.imported?.let { Text(it, style = Type.Small) }
            Text(
                "Signal Lab trades on paper only and never touches an exchange account. Its calls are research, not financial advice.",
                style = Type.Small,
            )
        }
    }
}

@Composable
private fun SyncPanel(s: com.ikverse.signallab.sync.SyncStatus, actions: SettingsActions) {
    Panel(Modifier.testTag("sync")) {
        when {
            !s.enabled -> {
                Text("Keep this phone's ledger in step with your other devices, through a hidden folder in your Google Drive that only Signal Lab can see.", style = Type.Small)
                QuietButton(if (s.busy) "Signing in…" else "Sign in with Google", actions.syncSignIn, Modifier.testTag("sync-sign-in"), enabled = !s.busy)
            }
            else -> {
                val last = s.lastSyncedAt?.let { "last synced ${com.ikverse.signallab.view.common.Words.utc(it)}" } ?: "not synced yet"
                Text(listOfNotNull("On", s.account, last, if (s.devices > 0) com.ikverse.signallab.view.common.Words.count(s.devices, "other device") else null).joinToString(" · "),
                    style = Type.Body.copy(color = Ink.Text), modifier = Modifier.testTag("sync-line"))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (s.needsSignIn) QuietButton("Sign in again", actions.syncSignIn, Modifier.testTag("sync-sign-in"), enabled = !s.busy)
                    else QuietButton(if (s.busy) "Syncing…" else "Sync now", actions.syncNow, Modifier.testTag("sync-now"), enabled = !s.busy)
                    QuietButton("Turn off", actions.syncOff, Modifier.testTag("sync-off"), enabled = !s.busy)
                }
            }
        }
        s.problem?.let { Text(it, style = Type.Small.copy(color = Ink.Loss), modifier = Modifier.testTag("sync-problem")) }
    }
}

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Overline(title, Modifier.padding(start = 4.dp))
        content()
    }
}

@Composable
private fun GrantRow(title: String, granted: Boolean, onOpen: () -> Unit) {
    TapRow(onOpen) {
        Text(title, style = Type.Body.copy(color = Ink.Text), modifier = Modifier.weight(1f))
        Text(if (granted) "Allowed" else "Not allowed", style = Type.Small.copy(color = if (granted) Ink.Gain else Ink.Accent))
    }
}

/** A number the person can type, saved when they press Done. */
@Composable
private fun NumberRow(label: String, value: Double, decimals: Int, tag: String, onSave: (Double) -> Unit) {
    var text by rememberSaveable(value) { mutableStateOf(String.format(Locale.US, "%.${decimals}f", value)) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Type.Body.copy(color = Ink.Text), modifier = Modifier.weight(1f))
        OutlinedTextField(
            text, { text = it }, singleLine = true, modifier = Modifier.width(120.dp).testTag(tag),
            textStyle = Type.Number,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { text.toDoubleOrNull()?.takeIf { it > 0 }?.let(onSave) }),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Ink.Accent, unfocusedBorderColor = Ink.Outline),
        )
    }
}
