package com.ikverse.signallab.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** The groups the alerts inbox filters by, and which alert kinds each holds. */
enum class AlertGroup(val label: String, val kinds: Set<String>?) {
    All("All", null),
    Opened("Opened", setOf("signal")),
    Closed("Closed", setOf("exit")),
    Warnings("Warnings", setOf("warning")),
    Missed("Missed", setOf("missed")),
    Problems("Problems", setOf("problem")),
}

fun filterAlerts(alerts: List<AlertUi>, group: AlertGroup): List<AlertUi> = alerts.filter { group.kinds == null || it.kind in group.kinds }

private fun kindColor(kind: String): Color = when (kind) {
    "warning" -> Palette.Warn
    "problem" -> Palette.Down
    "missed" -> Palette.Muted
    else -> Palette.Strong
}

/** Where touching an alert leads: the address it carries, or for one that has none the coin it is about. Null for one that goes nowhere. */
fun alertLink(a: AlertUi): Link? = parseLink(a.link) ?: a.symbol?.let { Link(it, a.timeframe) }

/**
 * Every alert the app has raised, including the ones that never became a notification ("missed" signals). Touching one goes where its
 * notification would. The group chosen lives in [nav], so a link can set it and it is kept while another tab is on show.
 */
@Composable
fun AlertsScreen(model: AlertsModel, nav: NavState, onOpen: (Link) -> Unit, modifier: Modifier = Modifier) {
    val all by model.alerts.collectAsStateWithLifecycle()
    val group = nav.alertsGroup
    val shown = filterAlerts(all, group)
    Column(modifier.fillMaxSize().testTag("alerts")) {
        ScreenTitle("Alerts")
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
            // Only the groups that have something in them, plus All.
            for (g in AlertGroup.entries) if (g == AlertGroup.All || g == group || all.any { g.kinds != null && it.kind in g.kinds }) ChoiceText(g.label, group == g, { nav.alertsGroup = g })
        }
        HRule()
        when {
            all.isEmpty() -> EmptyState("No alerts yet", "Alerts appear here when a pattern opens or closes a paper trade, when something needs your attention, and when a signal was too late to trade.")
            shown.isEmpty() -> EmptyState("Nothing in ${group.label}", "Choose another group.")
            else -> LazyColumn(Modifier.weight(1f)) {
                items(shown, key = { it.id }) { a ->
                    val link = alertLink(a)
                    TouchRow({ if (link != null) onOpen(link) }, modifier = Modifier.testTag("alert-${a.id}")) {
                        Column(Modifier.weight(1f)) {
                            Text(a.title, style = Type.BodyStrong.copy(color = kindColor(a.kind)))
                            Text(a.body, style = Type.Body.copy(color = Palette.Muted))
                            Text(Fmt.dateTime(a.time), style = Type.Small)
                        }
                    }
                    HRule()
                }
            }
        }
    }
}
