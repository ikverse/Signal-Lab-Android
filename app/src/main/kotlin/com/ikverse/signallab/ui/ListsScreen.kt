package com.ikverse.signallab.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

/**
 * Your lists: make them, switch them on and off, choose the charts and the coins. On a wide screen the list of lists sits beside the
 * one being edited, and the divider between them can be dragged or the list hidden.
 */
@Composable
fun ListsScreen(model: ListsModel, panels: PanelPrefs, wide: Boolean, modifier: Modifier = Modifier) {
    val lists by model.lists.collectAsStateWithLifecycle()
    val download by model.download.collectAsStateWithLifecycle()
    var selected by rememberSaveable { mutableStateOf<Long?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var adding by rememberSaveable { mutableStateOf(false) }
    val current = lists.firstOrNull { it.id == selected } ?: if (wide) lists.firstOrNull() else null

    BackHandler(enabled = creating || adding || (!wide && selected != null)) {
        when {
            creating -> creating = false
            adding -> adding = false
            else -> selected = null
        }
    }
    if (creating) {
        SetupScreen(model, modifier, title = "New list", intro = false, onDone = { creating = false }, onCancel = { creating = false })
        return
    }
    val listOfLists = @Composable {
        Column(Modifier.fillMaxSize()) {
            ScreenTitle("Lists")
            LazyColumn(Modifier.weight(1f)) {
                items(lists, key = { it.id }) { l ->
                    TouchRow({ selected = l.id; adding = false }, selected = current?.id == l.id) {
                        Column(Modifier.weight(1f)) {
                            Text(l.name, style = Type.BodyStrong)
                            Text("${l.coins.size} ${if (l.coins.size == 1) "coin" else "coins"} · ${l.timeframes.joinToString(" ")}", style = Type.Small)
                        }
                        Text(if (l.active) "watching" else "off", style = Type.Small.copy(color = if (l.active) Palette.Up else Palette.Muted))
                    }
                    HRule()
                }
            }
            TextAction("New list", { creating = true }, Modifier.fillMaxWidth().testTag("new-list"))
        }
    }
    val detail = @Composable {
        if (current != null && (wide || selected != null)) {
            ListDetail(
                model, current, adding, { adding = it },
                onBack = if (wide) null else ({ selected = null; adding = false }), modifier = Modifier.fillMaxSize(),
            )
        } else if (wide) {
            EmptyState("No list chosen", "Choose a list on the left, or make a new one.", Modifier.fillMaxSize())
        }
    }
    Column(modifier.fillMaxSize().testTag("lists")) {
        DownloadBanner(download)
        when {
            wide -> SplitPane(panels, "lists", "lists", 320f, listOfLists, detail, Modifier.weight(1f))
            selected == null -> Column(Modifier.weight(1f)) { listOfLists() }
            else -> Column(Modifier.weight(1f)) { detail() }
        }
    }
}

@Composable
private fun ListDetail(model: ListsModel, list: ListUi, adding: Boolean, setAdding: (Boolean) -> Unit, onBack: (() -> Unit)?, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var message by remember(list.id) { mutableStateOf<String?>(null) }
    var renaming by rememberSaveable(list.id) { mutableStateOf(false) }
    var newName by rememberSaveable(list.id) { mutableStateOf(list.name) }
    var confirmDelete by remember(list.id) { mutableStateOf(false) }
    LaunchedEffect(list.name) { newName = list.name }

    fun run(block: suspend () -> Outcome) {
        scope.launch {
            message = when (val r = block()) {
                Outcome.Done -> null
                is Outcome.Refused -> r.message
            }
        }
    }

    if (adding) {
        // The picker gets the whole pane to itself: it is a list that scrolls, and it cannot live inside the page that scrolls.
        Column(modifier.fillMaxSize().testTag("add-coins-pane")) {
            ScreenTitle("Add coins to “${list.name}”")
            message?.let { ProblemState(it) }
            CoinPicker(
                model, emptySet(), MAX_COINS_PER_LIST, { run { model.addCoin(list.id, it) } }, Modifier.weight(1f),
                alreadyIn = list.coins.toSet(), countText = "${list.coins.size} of $MAX_COINS_PER_LIST in this list",
            )
            HRule()
            TextAction("Done", { setAdding(false) }, Modifier.fillMaxWidth().testTag("add-done"))
        }
        return
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("list-detail")) {
        if (onBack != null) TextAction("‹ Lists", onBack, color = Palette.Muted)
        if (renaming) {
            PlainField(newName, { newName = it }, "List name")
            Row {
                TextAction("Save", { run { model.rename(list.id, newName).also { if (it == Outcome.Done) renaming = false } } })
                TextAction("Cancel", { renaming = false; newName = list.name }, color = Palette.Muted)
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(end = 8.dp)) {
                Text(list.name, style = Type.Title, modifier = Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 12.dp))
                TextAction("Rename", { renaming = true }, color = Palette.Muted)
            }
        }
        message?.let { ProblemState(it) }
        SwitchRow(
            "Watching", if (list.active) "Signals on these coins are being scanned." else "Off: nothing on this list is scanned.", list.active,
            { run { model.setActive(list.id, it) } },
        )
        HRule()
        ChartPicker(model.allTimeframes, list.timeframes.toSet(), { run { model.setTimeframes(list.id, it) } })
        HRule()
        SectionLabel("Coins (${list.coins.size} of $MAX_COINS_PER_LIST)")
        for (c in list.coins) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(c.removeSuffix("USDT"), style = Type.BodyStrong, modifier = Modifier.weight(1f))
                TextAction("Remove", { run { model.removeCoin(list.id, c) } }, color = Palette.Muted)
            }
            HRule()
        }
        TextAction("Add coins", { setAdding(true) }, Modifier.testTag("add-coins"))
        HRule()
        TextAction(
            if (confirmDelete) "Tap again to delete “${list.name}”" else "Delete this list",
            { if (confirmDelete) run { model.delete(list.id) } else confirmDelete = true },
            color = Palette.Down, modifier = Modifier.testTag("delete"),
        )
        Text("Deleting a list never touches paper trades already open on its coins.", style = Type.Small, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
    }
}
