package com.ikverse.signallab.view.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ikverse.signallab.model.CallPageUi
import com.ikverse.signallab.model.CallUi
import com.ikverse.signallab.view.chart.Chart
import com.ikverse.signallab.view.common.LongPill
import com.ikverse.signallab.view.common.Overline
import com.ikverse.signallab.view.common.Panel
import com.ikverse.signallab.view.common.QuietButton
import com.ikverse.signallab.view.common.RowsPanel
import com.ikverse.signallab.view.common.Signed
import com.ikverse.signallab.view.common.TierPill
import com.ikverse.signallab.view.common.Words
import com.ikverse.signallab.view.theme.Ink
import com.ikverse.signallab.view.theme.Type

/** One call: the chart with its lines, the plan, why it fired, and its strategy's evidence one tap away. */
@Composable
fun CallScreen(
    page: CallPageUi?,
    onEvidence: (String) -> Unit,
    modifier: Modifier = Modifier,
    onAsk: ((CallUi) -> Unit)? = null,
    /** The owner's copy on Binance: bought at a price, sold at a price, or not taken after all; null hides the section. */
    copyActions: CopyActions? = null,
) {
    if (page == null) {
        Box(modifier.fillMaxSize().testTag("call-loading"))
        return
    }
    val c = page.call
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp).testTag("call-page"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(c.base, style = Type.Display)
                LongPill()
                Spacer(Modifier.weight(1f))
                TierPill(c.tier)
            }
            Text("${c.strategyName} on the daily chart · fired ${Words.day(c.signalTime)}", style = Type.Small)
        }

        Box(Modifier.fillMaxWidth().height(240.dp)) { Chart(page.chart, page.price) }

        page.ended?.let { e ->
            Panel(ground = if (e.net >= 0) Ink.GainGround else Ink.UrgentGround) {
                Overline("Ended ${Words.day(e.exitTime)}")
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Left at ${Words.price(e.exitPrice)}: ${e.reasonWords}.", style = Type.Body.copy(color = Ink.Text), modifier = Modifier.weight(1f))
                    Signed(e.net, bold = true)
                }
                Text("${Words.usd(e.netUsd, signed = true)} on the paper account, after fees.", style = Type.Small)
            }
        } ?: page.price?.let { price ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Now ${Words.price(price)}", style = Type.Body.copy(color = Ink.Text))
                Signed(price / c.entry - 1, size = 14)
                Text("since the entry", style = Type.Small)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Overline("The plan", Modifier.padding(start = 4.dp))
            RowsPanel(rows = page.plan.map { (label, value) ->
                @Composable {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(label, style = Type.Body.copy(color = Ink.Body), modifier = Modifier.weight(0.4f))
                        Text(value, style = Type.Number.copy(fontSize = 14.sp), textAlign = TextAlign.End, modifier = Modifier.weight(0.6f))
                    }
                }
            })
        }

        if (copyActions != null && c.veto == null) {
            CopyPanel(page, { copyActions.took(c.id, it) }, { copyActions.sold(c.id, it) }, { copyActions.forget(c.id) })
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Why now", style = Type.Heading)
            Text(c.why, style = Type.Body)
            if (page.earlier > 0) Text("Earlier ${c.strategyName} calls on ${c.base}: ${page.earlier}, ${page.earlierUp} ended up.", style = Type.Small)
        }

        QuietButton("Does ${c.strategyName} work?", { onEvidence(c.strategyId) }, Modifier.fillMaxWidth().testTag("to-evidence"))
        if (onAsk != null) QuietButton("Ask the analyst about this call", { onAsk(c) }, Modifier.fillMaxWidth().testTag("ask-analyst"))
    }
}

/** What the owner did with the call on Binance: "I took it" with the price paid, then "I sold" with the price sold at. */
@Composable
fun CopyPanel(page: CallPageUi, onTook: (Double) -> Unit, onSold: (Double) -> Unit, onForget: () -> Unit) {
    val copy = page.copy
    var editing by rememberSaveable(page.call.id, copy) { mutableStateOf<String?>(null) }
    Panel(Modifier.testTag("copy-panel")) {
        Overline("Your copy on Binance")
        if (copy == null) {
            Text("Took this call on Binance? Say at what price, and the ledger tracks your real result beside the paper one.", style = Type.Small)
        } else {
            Text(copy.boughtLine, style = Type.Body.copy(color = Ink.Text), modifier = Modifier.testTag("copy-bought"))
            copy.soldLine?.let { Text(it, style = Type.Body.copy(color = Ink.Text), modifier = Modifier.testTag("copy-sold")) }
        }
        val asking = editing
        if (asking != null) {
            PriceField(asking, { editing = it }, Modifier.testTag("copy-price"))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                com.ikverse.signallab.view.common.PrimaryButton("Save", {
                    asking.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }?.let { p -> if (copy == null) onTook(p) else onSold(p) }
                    editing = null
                }, Modifier.testTag("copy-save"))
                QuietButton("Cancel", { editing = null })
            }
        } else if (copy == null) {
            com.ikverse.signallab.view.common.PrimaryButton("I took it", { editing = Words.plain(page.call.entry) }, Modifier.testTag("copy-took"))
        } else if (copy.soldPrice == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                com.ikverse.signallab.view.common.PrimaryButton("I sold", { editing = page.sellPrice?.let(Words::plain) ?: "" }, Modifier.testTag("copy-sold-button"))
                QuietButton("I did not take it", onForget, Modifier.testTag("copy-forget"))
            }
        }
    }
}

@Composable
private fun PriceField(text: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    androidx.compose.material3.OutlinedTextField(
        text, onChange, singleLine = true, modifier = modifier.fillMaxWidth(), label = { Text("Price on Binance") },
        textStyle = Type.Number,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(focusedBorderColor = Ink.Accent, unfocusedBorderColor = Ink.Outline),
    )
}

/** What the call page can record about the owner's own copy of a call. */
class CopyActions(val took: (Long, Double) -> Unit, val sold: (Long, Double) -> Unit, val forget: (Long) -> Unit)
