package com.ikverse.signallab.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

/** A fraction as the user types it in a percent box: 0.001 as "0.1", 0.00075 as "0.075", 0 as "0". */
fun percentInput(fraction: Double): String = "%.4f".format(java.util.Locale.ROOT, fraction * 100).trimEnd('0').trimEnd('.').ifEmpty { "0" }

/** The groups of Settings, in order. With room for two panels they are listed on the left and the chosen one is shown on the right. */
enum class SettingsSection(val label: String) {
    Costs("Costs"), Scanning("Scanning"), Screen("Screen"), DataSource("Data source"), Android("What Android allows"), Updates("Updates"), About("About"),
}

/**
 * Settings: costs, background scanning, the screen, the data source, what Android has allowed, updates and about, each a raised group.
 * Explanations are one line, with the rest an info button away. [onOpenPage] opens a Learn page ("Why these costs?").
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SettingsScreen(
    model: SettingsModel,
    hasDebug: Boolean,
    onOpenDebug: () -> Unit,
    modifier: Modifier = Modifier,
    wide: Boolean = false,
    onOpenPage: (String) -> Unit = {},
) {
    val s by model.settings.collectAsStateWithLifecycle()
    val u by model.update.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var fee by remember(s.feePerSide) { mutableStateOf(percentInput(s.feePerSide)) }
    var extraMajors by remember(s.extraMajors) { mutableStateOf(percentInput(s.extraMajors)) }
    var extraOthers by remember(s.extraOthers) { mutableStateOf(percentInput(s.extraOthers)) }
    var costMessage by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(false) }
    val costsChanged = fee != percentInput(s.feePerSide) || extraMajors != percentInput(s.extraMajors) || extraOthers != percentInput(s.extraOthers)
    var chosen by rememberSaveable { mutableStateOf(SettingsSection.Costs) }

    val section = @Composable { which: SettingsSection ->
        when (which) {
            SettingsSection.Costs -> Group("Costs") {
                CostRow("Exchange fee, each way", "Binance's entry tier is 0.10% each way. Lower it if you pay less.", fee, { fee = it; saved = false }, "Exchange fee each way, in %")
                CostRow("Extra cost · BTC and ETH", "Price slip before a fill, round trip. 0 is off.", extraMajors, { extraMajors = it; saved = false }, "Extra cost, BTC and ETH, in %")
                CostRow("Extra cost · other coins", "The same, for thinner coins.", extraOthers, { extraOthers = it; saved = false }, "Extra cost, other coins, in %")
                costMessage?.let { ProblemState(it) }
                if (saved) {
                    Text("Saved. Trades already open keep the cost they opened with.", style = Type.Small.copy(color = Palette.Up), modifier = Modifier.padding(horizontal = 14.dp))
                }
                Row(Modifier.fillMaxWidth().padding(start = 2.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextAction("Why these costs?", { onOpenPage("costs") }, Modifier.testTag("why-costs"))
                    Spacer(Modifier.weight(1f))
                    TonalButton("Save", {
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
                    }, Modifier.testTag("save-costs"), enabled = costsChanged)
                }
            }
            SettingsSection.Scanning -> Group("Scanning") {
                SwitchRow(
                    "Scan in the background", "Checks each candle as it closes, app open or not.", s.backgroundScanning,
                    { scope.launch { model.setBackgroundScanning(it) } },
                    more = "Lets Signal Lab scan each candle as it closes, even with the app closed. Off: nothing is scanned at all, so no new paper trades open and open ones are not followed until you switch it back on.",
                )
                SwitchRow(
                    "Follow charts under an hour", "Uses more battery; best on a charger.", s.followFastCharts,
                    { scope.launch { model.setFollowFastCharts(it) } },
                    more = "Stays awake for 1, 5, 15 and 30 minute charts. This uses more battery, so it suits a phone on a charger. Off: those charts are only checked when the hourly alarm wakes the phone, and most of their signals are missed.",
                )
            }
            SettingsSection.Screen -> Group("Screen") {
                SwitchRow(
                    "Keep the screen on and dim it", "For a phone left on a desk or a charger.", s.dimScreen,
                    { scope.launch { model.setDimScreen(it) } }, Modifier.testTag("dim-screen"),
                    more = "Stops the screen turning off while Signal Lab is showing, and turns its brightness down so the phone can sit on a desk or a charger. Your usual brightness comes back when you switch this off or leave the app. Scanning does not depend on it.",
                )
                if (s.dimScreen) {
                    Row(Modifier.padding(horizontal = 6.dp).testTag("dim-levels"), verticalAlignment = Alignment.CenterVertically) {
                        Text("How dim", style = Type.Small, modifier = Modifier.padding(horizontal = 10.dp))
                        for (level in DimLevel.entries) ChoiceText(level.label, level == s.dimLevel, { scope.launch { model.setDimLevel(level) } }, Modifier.testTag("dim-${level.name}"))
                    }
                }
                SwitchRow(
                    "Side bar on the right", "Held sideways, the bar of places moves to the right edge.", s.railOnRight,
                    { scope.launch { model.setRailOnRight(it) } }, Modifier.testTag("rail-right"),
                    more = "When the phone is held sideways, or the screen is wide, the side bar with Markets, Trades and the other places moves to the right edge, under your right thumb. Held upright, the bar along the bottom stays where it is.",
                )
            }
            SettingsSection.DataSource -> Group("Data source") {
                SwitchRow(
                    "Use Binance.US", "Choose this if Binance.com is not available from your network (it answers with error 451).",
                    s.binanceUs, { scope.launch { model.setBinanceUs(it) } },
                )
            }
            SettingsSection.Android -> Group("What Android allows") {
                PermissionRow("Alerts", "Notifications when a paper trade opens or closes.", s.permissions.alerts) { model.openSystemScreen(PermissionPrompt.NOTIFICATIONS) }
                PermissionRow("Exact alarms", "Wakes the phone right at each hourly close. Without it a scan can run minutes late.", s.permissions.exactAlarms) { model.openSystemScreen(PermissionPrompt.EXACT_ALARMS) }
                PermissionRow("Battery exemption", "Stops Android putting Signal Lab to sleep overnight.", s.permissions.batteryExempt) { model.openSystemScreen(PermissionPrompt.BATTERY) }
            }
            SettingsSection.Updates -> Group("Updates") {
                UpdateSection(u, onCheck = { scope.launch { model.checkForUpdate() } }, onInstall = { scope.launch { model.installUpdate() } })
            }
            SettingsSection.About -> Group("About") {
                Text(
                    "Version ${s.version}",
                    style = Type.Body,
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTouch).combinedClickable(onClick = {}, onLongClick = { if (hasDebug) onOpenDebug() }).padding(horizontal = 14.dp, vertical = 12.dp).testTag("version"),
                )
                Text(
                    "Signal Lab records what rules would have done and keeps score of whether they beat random entries after costs. It is research, not financial advice, and no real money is ever traded.",
                    style = Type.Small, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                )
                Text(s.dataNote, style = Type.Small, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
                Text(
                    "Built with KLineChart (Apache-2.0), marked and Mermaid (MIT), Roboto (Apache-2.0) and OkHttp (Apache-2.0).",
                    style = Type.Small, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp).padding(bottom = 6.dp),
                )
            }
        }
    }

    if (!wide) {
        Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("settings")) {
            ScreenTitle("Settings")
            for (which in SettingsSection.entries) section(which)
            Spacer(Modifier.height(24.dp))
        }
        return
    }
    Row(modifier.fillMaxSize().testTag("settings")) {
        Column(Modifier.width(scaledWithText(230.dp)).fillMaxHeight().verticalScroll(rememberScrollState())) {
            ScreenTitle("Settings")
            for (which in SettingsSection.entries) {
                TouchRow({ chosen = which }, selected = chosen == which, minHeight = 48.dp, modifier = Modifier.testTag("settings-section-${which.name}")) {
                    Text(which.label, style = if (chosen == which) Type.BodyStrong else Type.Body, maxLines = 1)
                }
            }
        }
        VRule()
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(top = 6.dp)) {
            Box(Modifier.widthIn(max = 560.dp)) { section(chosen) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** A group of settings: its name, then the rows on a raised surface. */
@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Column {
        SectionLabel(title)
        RaisedGroup {
            Spacer(Modifier.height(4.dp))
            content()
            Spacer(Modifier.height(4.dp))
        }
    }
}

/** A cost to type in: its name and a line on what it is, with the figure in a box on the right. */
@Composable
private fun CostRow(title: String, line: String, value: String, onChange: (String) -> Unit, description: String) {
    Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = Type.BodyStrong)
            Text(line, style = Type.Small)
        }
        NumberField(value, onChange, description, keyboard = KeyboardOptions(keyboardType = KeyboardType.Decimal))
    }
}

/** What the updater says it is doing, in one line. */
fun updateHeadline(u: UpdateUi): String = when (u.status) {
    UpdateStatus.IDLE -> if (u.checkedAt == null) "Not checked yet." else "Last checked ${Fmt.dateTime(u.checkedAt)}."
    UpdateStatus.CHECKING -> "Checking GitHub…"
    UpdateStatus.UP_TO_DATE -> "You have the newest version."
    UpdateStatus.AVAILABLE -> "Version ${u.version} is available."
    UpdateStatus.DOWNLOADING -> "Downloading version ${u.version}: ${((u.progress ?: 0f) * 100).toInt()}%"
    UpdateStatus.NEEDS_PERMISSION -> "Version ${u.version} is ready to install."
    UpdateStatus.INSTALLER_OPEN -> "Version ${u.version} is ready to install."
    UpdateStatus.FAILED -> if (u.version != null) "Version ${u.version} could not be installed." else "The update check did not finish."
}

@Composable
private fun UpdateSection(u: UpdateUi, onCheck: () -> Unit, onInstall: () -> Unit) {
    val busy = u.status == UpdateStatus.CHECKING || u.status == UpdateStatus.DOWNLOADING
    Column(Modifier.testTag("updates")) {
        Text(updateHeadline(u), style = Type.BodyStrong, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp).testTag("update-status"))
        u.message?.let { Text(it, style = Type.Small.copy(color = if (u.status == UpdateStatus.FAILED) Palette.Warn else Palette.Text), modifier = Modifier.padding(horizontal = 14.dp).testTag("update-message")) }
        if (u.notes.isNotBlank()) {
            Text("What changed", style = Type.Label, modifier = Modifier.padding(start = 14.dp, top = 8.dp))
            Text(u.notes, style = Type.Small, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp).testTag("update-notes"))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextAction("Check now", onCheck, enabled = !busy, modifier = Modifier.testTag("update-check"))
            if (u.canInstall) {
                TextAction(
                    if (u.status == UpdateStatus.AVAILABLE) "Download and install" else "Install", onInstall,
                    enabled = !busy, modifier = Modifier.testTag("update-install"),
                )
            }
        }
        Text(
            "Updates come from this app's releases on GitHub. Signal Lab downloads the file, checks it is signed with the same key as this app, and then opens Android's installer, which asks you before it installs anything.",
            style = Type.Small, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun PermissionRow(title: String, why: String, allowed: Boolean, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(title, style = Type.BodyStrong)
            Text(why, style = Type.Small)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                Canvas(Modifier.size(8.dp)) {
                    if (allowed) drawCircle(Palette.Up) else drawCircle(Palette.Warn, style = Stroke(1.5.dp.toPx()))
                }
                Text(if (allowed) "Allowed" else "Not allowed", style = Type.Small.copy(color = if (allowed) Palette.Up else Palette.Warn), modifier = Modifier.padding(start = 6.dp))
            }
        }
        TextAction(if (allowed) "Review" else "Allow", onOpen)
    }
}
