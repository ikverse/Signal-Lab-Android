package com.ikverse.signallab.view.today

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ikverse.signallab.model.CallUi
import com.ikverse.signallab.model.ClosedGroup
import com.ikverse.signallab.model.MarketLine
import com.ikverse.signallab.model.OpenUi
import com.ikverse.signallab.model.TodayUi
import com.ikverse.signallab.view.common.Dot
import com.ikverse.signallab.view.common.Empty
import com.ikverse.signallab.view.common.Figure
import com.ikverse.signallab.view.common.LongPill
import com.ikverse.signallab.view.common.Num
import com.ikverse.signallab.view.common.Overline
import com.ikverse.signallab.view.common.Panel
import com.ikverse.signallab.view.common.ScreenTitle
import com.ikverse.signallab.view.common.Section
import com.ikverse.signallab.model.SuggestionUi
import com.ikverse.signallab.view.common.QuietButton
import com.ikverse.signallab.view.common.PrimaryButton
import com.ikverse.signallab.view.common.Signed
import com.ikverse.signallab.view.common.TierPill
import com.ikverse.signallab.view.common.Words
import com.ikverse.signallab.view.theme.Ink
import com.ikverse.signallab.view.theme.Type

/**
 * Today: the market in a sentence, the calls made at the last daily close, the open calls with what to do with each, and
 * what ended this week. [selected] marks the call shown beside the list on a wide screen.
 */
@Composable
fun TodayScreen(
    state: TodayUi,
    onOpenCall: (Long) -> Unit,
    onLedger: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Long? = null,
    compact: Boolean = true,
    onRecord: (String) -> Unit = {},
    onDismiss: (String) -> Unit = {},
) {
    if (!state.loaded) {
        Column(modifier.fillMaxSize().testTag("today-loading")) {}
        return
    }
    LazyColumn(
        modifier.fillMaxSize().testTag("today"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "title") {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                ScreenTitle("Today")
                Text(passLine(state), style = Type.Small, modifier = Modifier.testTag("pass-line"))
            }
        }
        item(key = "market") { MarketPanel(state.market, lines = state.contextLines) }
        if (state.suggestions.isNotEmpty()) {
            item(key = "suggested-head") { Section("From the analyst", state.suggestions.size) }
            items(state.suggestions, key = { "suggested-${it.id}" }) { s -> SuggestionCard(s, { onRecord(s.id) }, { onDismiss(s.id) }) }
        }

        if (state.newCalls.isEmpty() && state.open.isEmpty() && state.closedThisWeek.isEmpty()) {
            item(key = "empty") {
                Empty(
                    "No calls right now",
                    "When a strategy fires at a daily close, its call shows here with what to do, and a notification says so. Most days nothing does, and then there is nothing to do.",
                )
            }
        }
        if (state.newCalls.isNotEmpty()) {
            item(key = "new-head") { Section("New calls", state.newCalls.size) }
            items(state.newCalls, key = { "new-${it.id}" }) { c ->
                if (compact) NewCallCard(c, { onOpenCall(c.id) }) else CallRow(c, c.id == selected, { onOpenCall(c.id) })
            }
        }
        if (state.open.isNotEmpty()) {
            item(key = "open-head") {
                Section("Open calls", state.open.count { !it.leaveNow }) {
                    if (state.todayUsd != 0.0) Num(Words.usd(state.todayUsd, signed = true) + " today", color = if (state.todayUsd > 0) Ink.Gain else Ink.Loss, size = 14)
                }
            }
            item(key = "open") { OpenList(state.open, selected, onOpenCall) }
        }
        if (state.closedThisWeek.isNotEmpty()) {
            item(key = "closed-head") {
                Section("Closed this week") {
                    TextButton(onLedger) { Text("Ledger", color = Ink.Accent, fontSize = 14.sp) }
                }
            }
            item(key = "closed") { ClosedList(state.closedByStrategy, onOpenCall) }
        }
        item(key = "note") {
            Text("Paper trades only. Calls are research, not financial advice.", style = Type.Caption.copy(color = Ink.Faint), modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/** "Fri 16 Oct · checked 00:05 UTC · next 00:01 UTC". */
fun passLine(s: TodayUi): String {
    val last = s.lastPass?.let { "checked ${Words.utc(it)}" } ?: "not checked yet"
    return "${Words.dayLong(System.currentTimeMillis())} · $last · next ${Words.utc(s.nextPass)}"
}

@Composable
fun MarketPanel(m: MarketLine, modifier: Modifier = Modifier, lines: List<String> = emptyList()) {
    Panel(modifier.testTag("market")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Dot(if (m.above == false) Ink.Loss else Ink.GainDot)
            Overline("Market")
            Spacer(Modifier.weight(1f))
            m.btcPrice?.let { Num("BTC ${Words.price(it)}", color = Ink.Body, size = 13) }
        }
        Text(m.sentence + " ", style = Type.Body.copy(color = Ink.Text))
        m.futuresSentence?.let { Text(it, style = Type.Small.copy(color = Ink.Body), modifier = Modifier.testTag("market-futures")) }
        Text(m.allowedSentence, style = Type.Small)
        for (l in lines) Text("•  $l", style = Type.Small.copy(color = Ink.Body), modifier = Modifier.testTag("context-line"))
    }
}

/** A call the analyst suggested: the owner records it, and it enters at the next daily open, or dismisses it. */
@Composable
fun SuggestionCard(s: SuggestionUi, onRecord: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Panel(modifier.testTag("suggestion-${s.id}"), outlined = true) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(s.base, style = Type.Title.copy(fontSize = 22.sp))
            LongPill()
            Spacer(Modifier.weight(1f))
            s.price?.let { Num(Words.price(it), color = Ink.Body, size = 13) }
        }
        Text(s.thesis, style = Type.Body.copy(color = Ink.Text))
        Text(s.plan, style = Type.Small)
        if (s.recorded) {
            Text("Recorded: it enters at the next daily open, under the strategy Analyst.", style = Type.Small.copy(color = Ink.Gain), modifier = Modifier.testTag("recorded"))
            TextButton(onDismiss) { Text("Take it back", color = Ink.Muted, fontSize = 14.sp) }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton("Record it", onRecord, Modifier.testTag("record"))
                QuietButton("Dismiss", onDismiss, Modifier.testTag("dismiss"))
            }
            Text("A paper call of the analyst's own judgment, judged against random entries like every strategy.", style = Type.Caption)
        }
    }
}

/** A new call as a full card: coin, direction, confidence, the three numbers, and its strategy. */
@Composable
fun NewCallCard(c: CallUi, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Panel(modifier.testTag("call-${c.id}"), onClick = onClick, outlined = true) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(c.base, style = Type.Title.copy(fontSize = 22.sp))
            LongPill()
            Spacer(Modifier.weight(1f))
            TierPill(c.tier)
        }
        Text(c.why, style = Type.Body)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Figure("Enter at", Words.price(c.entry), Modifier.weight(1f), note = "next open")
            Figure("Safety stop", c.stop?.let(Words::price) ?: "none", Modifier.weight(1f), valueColor = Ink.Loss, note = c.stopFraction?.let(Words::pct))
            Figure("Size", if (c.sizeFraction > 0) Words.usd(c.sizeUsd) else if (c.veto != null) "skipped" else "tracked", Modifier.weight(1f),
                note = if (c.sizeFraction > 0) "${Words.share(c.sizeFraction)} of account" else if (c.veto != null) "see below" else "account full")
        }
        c.buyLine?.let {
            Text(it, style = Type.Body.copy(color = if (c.veto != null || c.belowMinimum) Ink.Muted else Ink.Text, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.testTag("buy-line"))
        }
        Text("${c.strategyName} · ${if (c.hourly) "hourly" else "daily"} · ${c.exitShort}", style = Type.Small)
    }
}

/** A call as one row, for the list beside the detail on a wide screen. */
@Composable
fun CallRow(c: CallUi, selected: Boolean, onClick: () -> Unit) {
    Panel(Modifier.testTag("call-${c.id}"), onClick = onClick, outlined = selected, ground = if (selected) Ink.Raised else Ink.Surface, padding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(c.base, style = Type.Subhead.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold))
            LongPill()
            Spacer(Modifier.weight(1f))
            Num(Words.price(c.entry), size = 14)
        }
        Text("${c.strategyName} · ${c.tier.label}", style = Type.Small)
    }
}

/** How many open calls show before "Show N more". */
const val OPEN_SHOWN = 8

@Composable
private fun OpenList(open: List<OpenUi>, selected: Long?, onOpenCall: (Long) -> Unit) {
    var all by rememberSaveable { mutableStateOf(false) }
    val shown = if (all) open else open.take(OPEN_SHOWN)
    Column(Modifier.fillMaxWidth().background(Ink.Surface, androidx.compose.foundation.shape.RoundedCornerShape(16.dp))) {
        shown.forEachIndexed { i, o ->
            if (i > 0) HorizontalDivider(color = Ink.Rule)
            val ground = when {
                o.leaveNow -> Ink.UrgentGround
                o.call.id == selected -> Ink.Raised
                else -> Ink.Surface
            }
            Column(
                Modifier.fillMaxWidth().background(ground).clickable(role = Role.Button) { onOpenCall(o.call.id) }
                    .padding(horizontal = 16.dp, vertical = 13.dp).testTag("open-${o.call.id}"),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(o.call.base, style = Type.Subhead.copy(fontWeight = FontWeight.Bold))
                    Text(if (o.leaveNow) o.call.strategyName else "${o.call.strategyName} · day ${o.daysHeld}", style = Type.Caption)
                    Spacer(Modifier.weight(1f))
                    if (o.leaveNow) Text("Exit now", color = Ink.Accent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    else Signed(o.change, size = 14, bold = true)
                }
                Text(o.instruction, style = Type.Small.copy(color = if (o.leaveNow) Ink.UrgentText else Ink.Body, fontSize = 14.sp))
            }
        }
        if (open.size > OPEN_SHOWN) {
            HorizontalDivider(color = Ink.Rule)
            MoreRow(if (all) "Show fewer" else "Show ${open.size - OPEN_SHOWN} more", Modifier.testTag("open-more")) { all = !all }
        }
    }
}

@Composable
private fun MoreRow(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        text, color = Ink.Accent, fontSize = 14.sp, fontWeight = FontWeight.Bold,
        modifier = modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 13.dp),
    )
}

/** What ended this week as one line per strategy; a line opens to its calls. */
@Composable
private fun ClosedList(groups: List<ClosedGroup>, onOpenCall: (Long) -> Unit) {
    var opened by rememberSaveable { mutableStateOf(emptyList<String>()) }
    Column(Modifier.fillMaxWidth().background(Ink.Surface, androidx.compose.foundation.shape.RoundedCornerShape(16.dp))) {
        groups.forEachIndexed { i, g ->
            if (i > 0) HorizontalDivider(color = Ink.Rule)
            val open = g.strategyId in opened
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.Button) { opened = if (open) opened - g.strategyId else opened + g.strategyId }
                    .padding(horizontal = 16.dp, vertical = 13.dp).testTag("closed-${g.strategyId}"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(g.strategyName, style = Type.Subhead.copy(fontWeight = FontWeight.Bold))
                    Text("${Words.count(g.calls.size, "call")} closed · ${g.won} won", style = Type.Caption)
                }
                Num(Words.usd(g.netUsd, signed = true), color = if (g.netUsd >= 0) Ink.Gain else Ink.Loss, size = 14)
                Text(if (open) "▴" else "▾", color = Ink.Muted, fontSize = 14.sp)
            }
            if (open) for (c in g.calls) {
                Row(
                    Modifier.fillMaxWidth().background(Ink.Raised).clickable(role = Role.Button) { onOpenCall(c.call.id) }
                        .padding(start = 28.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(c.call.base, style = Type.Subhead)
                    Text("${Words.day(c.exitTime)} · ${c.reasonWords}", style = Type.Caption)
                    Spacer(Modifier.weight(1f))
                    Signed(c.net, size = 14)
                    Spacer(Modifier.width(2.dp))
                    Num("· " + Words.usd(c.netUsd, signed = true), color = if (c.net >= 0) Ink.Gain else Ink.Loss, size = 14)
                }
            }
        }
    }
}
