package com.ikverse.signallab.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

/** A fraction as the user types it in a percent box: 0.001 as "0.1", 0.00075 as "0.075", 0 as "0". */
fun percentInput(fraction: Double): String = "%.4f".format(java.util.Locale.ROOT, fraction * 100).trimEnd('0').trimEnd('.').ifEmpty { "0" }

/** Settings: costs, background scanning, the data source, what Android has allowed, and about. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SettingsScreen(model: SettingsModel, hasDebug: Boolean, onOpenDebug: () -> Unit, modifier: Modifier = Modifier) {
    val s by model.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var fee by remember(s.feePerSide) { mutableStateOf(percentInput(s.feePerSide)) }
    var extraMajors by remember(s.extraMajors) { mutableStateOf(percentInput(s.extraMajors)) }
    var extraOthers by remember(s.extraOthers) { mutableStateOf(percentInput(s.extraOthers)) }
    var costMessage by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("settings")) {
        ScreenTitle("Settings")

        SectionLabel("Costs")
        Text(
            "Every paper trade is charged what a real one would cost. Binance's own fee on its entry tier is 0.10% each way, so 0.20% to open and close a trade. " +
                "Lower it if you pay less, for example with BNB.",
            style = Type.Small, modifier = Modifier.padding(horizontal = 16.dp),
        )
        PercentField(fee, { fee = it; saved = false }, "Exchange fee each way, in %")
        Text(
            "Extra cost for thin coins is the price moving against you before an order fills. It is off by default; turn it on by entering a number. It is a round-trip cost.",
            style = Type.Small, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        PercentField(extraMajors, { extraMajors = it; saved = false }, "Extra cost, BTC and ETH, in %")
        PercentField(extraOthers, { extraOthers = it; saved = false }, "Extra cost, other coins, in %")
        costMessage?.let { ProblemState(it) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextAction("Save costs", {
                scope.launch {
                    val f = Fmt.parsePercent(fee)
                    val m = Fmt.parsePercent(extraMajors)
                    val o = Fmt.parsePercent(extraOthers)
                    if (f == null || m == null || o == null) {
                        costMessage = "Enter each cost as a number, such as 0.1."
                        return@launch
                    }
                    val r1 = model.setFee(f)
                    val r2 = if (r1 == Outcome.Done) model.setExtraCosts(m, o) else r1
                    costMessage = (r2 as? Outcome.Refused)?.message
                    saved = r2 == Outcome.Done
                }
            }, modifier = Modifier.testTag("save-costs"))
            if (saved) Text("Saved. Trades already open keep the cost they opened with.", style = Type.Small.copy(color = Palette.Up))
        }
        HRule()

        SectionLabel("Scanning")
        SwitchRow(
            "Scan in the background", "Lets Signal Lab scan each candle as it closes, even with the app closed. Off: nothing is scanned at all, so no new paper trades open and open ones are not followed until you switch it back on.",
            s.backgroundScanning, { scope.launch { model.setBackgroundScanning(it) } },
        )
        SwitchRow(
            "Follow charts under an hour", "Stays awake for 1, 5, 15 and 30 minute charts. This uses more battery, so it suits a phone on a charger. Off: those charts are only checked when the hourly alarm wakes the phone, and most of their signals are missed.",
            s.followFastCharts, { scope.launch { model.setFollowFastCharts(it) } },
        )
        HRule()

        SectionLabel("Data source")
        SwitchRow(
            "Use Binance.US", "Choose this if Binance.com is not available from your network (it answers with error 451).",
            s.binanceUs, { scope.launch { model.setBinanceUs(it) } },
        )
        HRule()

        SectionLabel("What Android allows")
        PermissionRow("Alerts", "Notifications when a paper trade opens or closes.", s.permissions.alerts) { model.openSystemScreen(PermissionPrompt.NOTIFICATIONS) }
        PermissionRow("Exact alarms", "Wakes the phone right at each hourly close. Without it a scan can run minutes late.", s.permissions.exactAlarms) { model.openSystemScreen(PermissionPrompt.EXACT_ALARMS) }
        PermissionRow("Battery exemption", "Stops Android putting Signal Lab to sleep overnight.", s.permissions.batteryExempt) { model.openSystemScreen(PermissionPrompt.BATTERY) }
        HRule()

        SectionLabel("About")
        Text(
            "Version ${s.version}",
            style = Type.Body,
            modifier = Modifier.fillMaxWidth().heightIn(min = MinTouch).combinedClickable(onClick = {}, onLongClick = { if (hasDebug) onOpenDebug() }).padding(horizontal = 16.dp, vertical = 12.dp).testTag("version"),
        )
        Text(
            "Signal Lab records what rules would have done and keeps score of whether they beat random entries after costs. It is research, not financial advice, and no real money is ever traded.",
            style = Type.Small, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        Text(s.dataNote, style = Type.Small, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        Text(
            "Built with KLineChart (Apache-2.0), marked and Mermaid (MIT), Roboto (Apache-2.0) and OkHttp (Apache-2.0).",
            style = Type.Small, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).padding(bottom = 24.dp),
        )
    }
}

@Composable
private fun PercentField(value: String, onChange: (String) -> Unit, label: String) {
    Column {
        Text(label, style = Type.Label, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
        PlainField(value, onChange, label, keyboard = KeyboardOptions(keyboardType = KeyboardType.Decimal))
    }
}

@Composable
private fun PermissionRow(title: String, why: String, allowed: Boolean, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(title, style = Type.BodyStrong)
            Text(why, style = Type.Small)
            Text(if (allowed) "Allowed" else "Not allowed", style = Type.Small.copy(color = if (allowed) Palette.Up else Palette.Warn))
        }
        TextAction(if (allowed) "Review" else "Allow", onOpen)
    }
}
