package com.ikverse.signallab.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

/** How a closed trade's alert ended: from its numbers, or from the words of one stored before alerts kept numbers. */
internal fun alertReason(a: AlertUi): String? = a.facts?.reason ?: when {
    a.kind != "exit" -> null
    a.title.endsWith("target hit") -> "target"
    a.title.endsWith("stopped out") -> "stop"
    a.title.endsWith("time limit") -> "time"
    else -> null
}

/** What happened, in a word or two: "Opened", "Target hit", "Stopped out", "Time limit". */
internal fun alertOutcome(a: AlertUi): String = when (a.kind) {
    "signal" -> "Opened"
    "exit" -> when (alertReason(a)) {
        "target" -> "Target hit"
        "stop" -> "Stopped out"
        "time" -> "Time limit"
        else -> "Closed"
    }
    else -> a.title
}

/**
 * The figures of an alert about a trade, on one line: where an opened trade stands ("Entry 2.126 → target 2.207 (+3.79%) · stop 2.036"),
 * or what random entries did for a closed one. Null when there is nothing to add.
 */
internal fun alertFigures(a: AlertUi): String? {
    val f = a.facts ?: return null
    return when (a.kind) {
        "signal" -> {
            val entry = f.entry ?: return null
            when {
                f.trails && f.stop != null -> "Entry ${Fmt.price(entry)} · safety stop ${Fmt.price(f.stop)}, then trails up"
                f.target != null && f.stop != null ->
                    "Entry ${Fmt.price(entry)} → target ${Fmt.price(f.target)} (${Fmt.signedPercent(f.target / entry - 1)}) · stop ${Fmt.price(f.stop)}"
                else -> "Entry ${Fmt.price(entry)}"
            }
        }
        "exit" -> f.random?.let { "Random entries averaged ${Fmt.signedPercent(it)}" }
        else -> null
    }
}

/** The trade an alert is about, when it is still in the record: by the id it carries, or the one in its link. */
internal fun alertTrade(a: AlertUi, trades: List<TradeUi>): TradeUi? {
    val id = a.facts?.tradeId ?: parseLink(a.link)?.tradeId ?: return null
    return trades.firstOrNull { it.id == id }
}

/** The mark at the start of an alert: what kind it is, in its colour, on a faint disc of the same. */
private fun kindMark(a: AlertUi): Pair<ImageVector, Color> = when (a.kind) {
    "signal" -> Glyphs.Opened to Palette.Accent
    "exit" -> when (alertReason(a)) {
        "target" -> Glyphs.Check to Palette.Up
        "stop" -> Glyphs.Close to Palette.Down
        "time" -> Glyphs.Clock to Palette.Muted
        else -> Glyphs.Dash to Palette.Muted
    }
    "warning" -> Glyphs.Warning to Palette.Warn
    "problem" -> Glyphs.Warning to Palette.Down
    else -> Glyphs.Dash to Palette.Muted
}

/**
 * Every alert the app has raised, including the ones that never became a notification ("missed" signals), by day. An alert about a trade
 * shows its coin, what happened and the figures; others show their words. On a phone held upright, touching one goes where its
 * notification would; with room for two panels it opens beside the list, with the trade it is about. The group chosen lives in [nav], so
 * a link can set it and it is kept while another tab is on show.
 */
@Composable
fun AlertsScreen(
    model: AlertsModel,
    nav: NavState,
    onOpen: (Link) -> Unit,
    modifier: Modifier = Modifier,
    wide: Boolean = false,
    trades: List<TradeUi> = emptyList(),
    prices: Map<String, Double> = emptyMap(),
    onOpenCoin: (String, String, Long) -> Unit = { _, _, _ -> },
    onOpenLearn: (String) -> Unit = {},
    now: Long = System.currentTimeMillis(),
) {
    val all by model.alerts.collectAsStateWithLifecycle()
    val group = nav.alertsGroup
    val shown = filterAlerts(all, group)
    var chosen by rememberSaveable { mutableStateOf<Long?>(null) }
    val listPane = @Composable { m: Modifier ->
        Column(m.testTag("alerts")) {
            ScreenTitle("Alerts")
            Row(Modifier.fillMaxWidth().padding(horizontal = 13.dp)) {
                // Only the groups that have something in them, plus All.
                for (g in AlertGroup.entries) if (g == AlertGroup.All || g == group || all.any { g.kinds != null && it.kind in g.kinds }) ChoiceText(g.label, group == g, { nav.alertsGroup = g })
            }
            HRule()
            when {
                all.isEmpty() -> EmptyState("No alerts yet", "Alerts appear here when a pattern opens or closes a paper trade, when something needs your attention, and when a signal was too late to trade.")
                shown.isEmpty() -> EmptyState("Nothing in ${group.label}", "Choose another group.")
                else -> LazyColumn(Modifier.weight(1f)) {
                    for ((day, alerts) in shown.groupBy { Fmt.day(it.time, now) }) {
                        item(key = "day-$day") { SectionLabel(day) }
                        items(alerts, key = { it.id }) { a ->
                            val link = alertLink(a)
                            AlertRow(a, selected = wide && chosen == a.id) { if (wide) chosen = a.id else if (link != null) onOpen(link) }
                            HRule()
                        }
                    }
                }
            }
        }
    }
    if (!wide) {
        listPane(modifier.fillMaxSize())
        return
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val listWidth = (maxWidth * 0.5f).coerceIn(300.dp, 440.dp)
        Row(Modifier.fillMaxSize()) {
            listPane(Modifier.width(listWidth).fillMaxHeight())
            VRule()
            val a = all.firstOrNull { it.id == chosen }
            Box(Modifier.weight(1f).fillMaxHeight().testTag("alert-pane")) {
                if (a == null) EmptyState("Choose an alert", "Touch an alert on the left to see what it is about.")
                else AlertDetail(a, alertTrade(a, trades), prices, onOpen, onOpenCoin, onOpenLearn)
            }
        }
    }
}

/** An alert in the list: its mark, then its coin, what happened and the figures (or its words, for one that is not about a trade). */
@Composable
private fun AlertRow(a: AlertUi, selected: Boolean, onClick: () -> Unit) {
    val (mark, color) = kindMark(a)
    val f = a.facts
    TouchRow(onClick, modifier = Modifier.testTag("alert-${a.id}"), selected = selected, minHeight = 64.dp) {
        Box(Modifier.align(Alignment.Top).padding(top = 2.dp).size(34.dp).clip(CircleShape).background(color.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(mark, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            if (f != null && a.symbol != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(a.symbol.removeSuffix("USDT"), style = Type.BodyStrong, maxLines = 1)
                    a.timeframe?.let {
                        Spacer(Modifier.width(8.dp))
                        ChartTag(it)
                    }
                    Spacer(Modifier.weight(1f))
                    Text(Fmt.time(a.time), style = Type.Small.copy(fontFeatureSettings = "tnum"))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${alertOutcome(a)} · ${f.pattern}", style = Type.Small, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (a.kind == "exit" && f.net != null) {
                        Text(Fmt.signedPercent(f.net), style = Type.NumberStrong.copy(color = Fmt.changeColor(f.net), fontSize = 16.sp), modifier = Modifier.padding(start = 8.dp))
                    }
                }
                alertFigures(a)?.let { Text(it, style = Type.Small.copy(color = Palette.TagText, fontFeatureSettings = "tnum"), maxLines = 2) }
            } else {
                Row {
                    Text(a.title, style = Type.BodyStrong.copy(color = kindColor(a.kind)), modifier = Modifier.weight(1f))
                    Text(Fmt.time(a.time), style = Type.Small.copy(fontFeatureSettings = "tnum"), modifier = Modifier.padding(start = 8.dp))
                }
                Text(a.body, style = Type.Small, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** The chosen alert, beside the list: the trade it is about with all its figures, or else its words and the way to where it leads. */
@Composable
private fun AlertDetail(
    a: AlertUi,
    trade: TradeUi?,
    prices: Map<String, Double>,
    onOpen: (Link) -> Unit,
    onOpenCoin: (String, String, Long) -> Unit,
    onOpenLearn: (String) -> Unit,
) {
    val link = alertLink(a)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 12.dp)) {
        if (trade != null) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(trade.symbol.removeSuffix("USDT"), style = Type.Title)
                Spacer(Modifier.width(8.dp))
                ChartTag(trade.timeframe)
                Spacer(Modifier.weight(1f))
                trade.closed?.let { Text(Fmt.signedPercent(it.net), style = Type.Big.copy(color = Fmt.changeColor(it.net))) }
            }
            Text("${alertOutcome(a)} · ${trade.short} · ${Fmt.dateTime(a.time)}", style = Type.Small, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 10.dp))
            TradeDetail(trade, prices[trade.symbol], { onOpenCoin(trade.symbol, trade.timeframe, trade.id) }, { onOpenLearn(trade.variant) })
            if (link != null) TextAction("Open in ${if (link.place == LinkPlace.TRADES) "Trades" else "Markets"}", { onOpen(link) }, Modifier.padding(start = 4.dp, top = 4.dp))
        } else {
            Text(a.title, style = Type.Heading.copy(color = kindColor(a.kind)), modifier = Modifier.padding(horizontal = 16.dp))
            Text(Fmt.dateTime(a.time), style = Type.Small, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp))
            Text(a.body, style = Type.Body, modifier = Modifier.padding(16.dp))
            if (link != null) TonalButton("Open", { onOpen(link) }, Modifier.padding(horizontal = 12.dp))
        }
    }
}
