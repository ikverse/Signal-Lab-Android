package com.ikverse.signallab.view.markets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.ikverse.signallab.model.CoinPageUi
import com.ikverse.signallab.model.CoinUi
import com.ikverse.signallab.model.MarketLine
import com.ikverse.signallab.model.MarketsUi
import com.ikverse.signallab.view.chart.Chart
import com.ikverse.signallab.view.chart.ChartData
import com.ikverse.signallab.view.common.Empty
import com.ikverse.signallab.view.common.Figure
import com.ikverse.signallab.view.common.Num
import com.ikverse.signallab.view.common.Panel
import com.ikverse.signallab.view.common.Pill
import com.ikverse.signallab.view.common.QuietButton
import com.ikverse.signallab.view.common.RowsPanel
import com.ikverse.signallab.view.common.ScreenTitle
import com.ikverse.signallab.view.common.Section
import com.ikverse.signallab.view.common.Signed
import com.ikverse.signallab.view.common.TapRow
import com.ikverse.signallab.view.common.Words
import com.ikverse.signallab.view.theme.Ink
import com.ikverse.signallab.view.theme.Type

@Composable
fun MarketsScreen(state: MarketsUi, onSearch: (String) -> Unit, onCoin: (String) -> Unit, modifier: Modifier = Modifier) {
    if (!state.loaded) {
        Box(modifier.fillMaxSize().testTag("markets-loading"))
        return
    }
    LazyColumn(modifier.fillMaxSize().testTag("markets"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ScreenTitle("Markets") }
        item { StateCards(state.market) }
        item {
            OutlinedTextField(
                state.query, onSearch, label = { Text("Find a coin") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("coin-search"),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Ink.Accent, unfocusedBorderColor = Ink.Outline, focusedLabelColor = Ink.Accent),
            )
        }
        if (state.found.isNotEmpty()) {
            item { Section("Found", state.found.size) }
            item { RowsPanel(rows = state.found.map { c -> @Composable { CoinRow(c, onCoin) } }) }
        }
        item {
            Text("Watching the ${state.watched.size} busiest coins · ${state.pins} pinned · ${state.excludes} excluded", style = Type.Small)
        }
        if (state.watched.isEmpty()) {
            item { Empty("No coins yet", "The coins are chosen at the first daily pass, after their history has downloaded.") }
        } else {
            item { RowsPanel(rows = state.watched.map { c -> @Composable { CoinRow(c, onCoin) } }) }
        }
        item { Text("Calls come only from daily closes. Prices here are the last daily close.", style = Type.Caption.copy(color = Ink.Faint)) }
    }
}

@Composable
private fun StateCards(m: MarketLine) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Panel(Modifier.weight(1f), padding = PaddingValues(12.dp)) {
                Figure("BTC vs 200-day", m.vsAverage?.let { Words.pct(it) + if (it > 0) " above" else " below" } ?: "–",
                    valueColor = if (m.above == false) Ink.Loss else Ink.Gain)
            }
            Panel(Modifier.weight(1f), padding = PaddingValues(12.dp)) { Figure("Swings", m.swings?.replaceFirstChar { it.uppercase() } ?: "–") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Panel(Modifier.weight(1f), padding = PaddingValues(12.dp)) { Figure("Funding, 8h", m.funding?.let { Words.pct(it, 3) } ?: "–") }
            Panel(Modifier.weight(1f), padding = PaddingValues(12.dp)) { Figure("Strategies allowed", "${m.allowed} of ${m.total}") }
        }
    }
}

@Composable
private fun CoinRow(c: CoinUi, onCoin: (String) -> Unit) {
    TapRow({ onCoin(c.symbol) }, Modifier.testTag("coin-${c.symbol}")) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(Words.base(c.symbol), style = Type.Subhead)
                when {
                    c.newCall -> Pill("new call", Ink.Gain, Ink.GainGround)
                    c.open -> Pill("open", Ink.Body, line = Ink.Outline)
                }
                if (c.pinned) Pill("pinned", Ink.Muted, line = Ink.Outline)
            }
            Text(c.note, style = Type.Caption)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Num(Words.price(c.price))
            Signed(c.change, size = 12)
        }
    }
}

/** One coin: its chart, what it is doing, its calls, and pinning or excluding it. */
@Composable
fun CoinScreen(page: CoinPageUi?, onCall: (Long) -> Unit, onPin: (Boolean) -> Unit, onExclude: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    if (page == null) {
        Box(modifier.fillMaxSize().testTag("coin-loading"))
        return
    }
    val c = page.coin
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp).testTag("coin-page"),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(Words.base(c.symbol), style = Type.Display, modifier = Modifier.weight(1f))
            Num(Words.price(c.price), size = 18, bold = true)
            Signed(c.change, size = 14)
        }
        Text(if (c.watched) c.note else "Not among the watched coins.", style = Type.Small)
        Box(Modifier.fillMaxWidth().height(260.dp)) {
            Chart(page.candles?.let { all -> ChartData(c.symbol, "1d", all.slice(maxOf(0, all.size - 180), all.size)) }, c.price)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuietButton(if (c.pinned) "Unpin" else "Always watch", { onPin(!c.pinned) }, Modifier.weight(1f).testTag("pin"))
            QuietButton(if (c.excluded) "Stop excluding" else "Never watch", { onExclude(!c.excluded) }, Modifier.weight(1f).testTag("exclude"))
        }
        if (page.calls.isNotEmpty()) {
            Section("Calls on ${Words.base(c.symbol)}", page.calls.size)
            RowsPanel(rows = page.calls.map { call ->
                @Composable {
                    TapRow({ onCall(call.id) }) {
                        Text(call.strategyName, style = Type.Subhead, modifier = Modifier.weight(1f))
                        Text(Words.day(call.signalTime), style = Type.Caption)
                        Num(Words.price(call.entry), size = 14)
                    }
                }
            })
        }
    }
}
