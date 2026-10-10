package com.ikverse.signallab.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The page's own name, the same as its row on the Settings page. */
private val PAGE_TITLE = Dest.Lists.label

/** A name for a new list that no list has yet: "New list", else "New list 2", "New list 3"… */
internal fun freeListName(taken: List<String>): String =
    generateSequence(1) { it + 1 }.map { if (it == 1) "New list" else "New list $it" }.first { name -> taken.none { it.equals(name, ignoreCase = true) } }

/** How long "Tap again to delete" stays armed. */
private const val CONFIRM_WINDOW_MS = 4_000L

/** The line under a list's name: how many coins, and the first four of them. */
internal fun listCoinsLine(l: ListUi): String {
    val n = l.coins.size
    val names = l.coins.take(4).joinToString(", ") { it.removeSuffix("USDT") }
    return "$n ${if (n == 1) "coin" else "coins"}" + (if (n > 0) " · $names" else "") + (if (n > 4) " +${n - 4}" else "")
}

/** Whether a list is scanned: a green dot and "Watching", or a hollow one and "Off". */
@Composable
private fun WatchState(active: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp)) {
        Canvas(Modifier.size(8.dp)) {
            if (active) drawCircle(Palette.Up) else drawCircle(Palette.Faint, style = Stroke(1.5.dp.toPx()))
        }
        Text(if (active) "Watching" else "Off", style = Type.Small.copy(color = if (active) Palette.Up else Palette.Muted), modifier = Modifier.padding(start = 5.dp))
    }
}

/**
 * Your lists: make them, switch them on and off, choose the charts and the coins. On a wide screen the list of lists sits beside the
 * one being edited, and the divider between them can be dragged or the list hidden.
 */
@Composable
fun ListsScreen(
    model: ListsModel, panels: PanelPrefs, wide: Boolean, modifier: Modifier = Modifier, onBack: (() -> Unit)? = null, backLabel: String = Dest.More.label,
) {
    val lists by model.lists.collectAsStateWithLifecycle()
    val download by model.download.collectAsStateWithLifecycle()
    var selected by rememberSaveable { mutableStateOf<Long?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var adding by rememberSaveable { mutableStateOf(false) }
    // The open list is looked up by its id every time. A list that has gone (deleted here, or any other way) is simply not open: the screen
    // never waits on a list that is not there, which left a narrow phone showing neither the lists nor a list.
    val open = lists.firstOrNull { it.id == selected }
    val current = open ?: if (wide) lists.firstOrNull() else null
    val editing = adding && current != null
    LaunchedEffect(open == null, selected) {
        if (selected != null && open == null) {
            selected = null
            adding = false
        }
    }

    BackHandler(enabled = creating || editing || (!wide && open != null)) {
        when {
            creating -> creating = false
            adding -> adding = false
            else -> selected = null
        }
    }
    if (creating) {
        SetupScreen(
            model, panels, wide, modifier, title = "New list", intro = false, onDone = { creating = false }, onCancel = { creating = false },
            defaultName = freeListName(lists.map { it.name }), backLabel = PAGE_TITLE,
        )
        return
    }
    val listOfLists = @Composable {
        Column(Modifier.fillMaxSize()) {
            onBack?.let { BackRow(backLabel, it) }
            Row(Modifier.fillMaxWidth().padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                ScreenTitle(PAGE_TITLE, Modifier.weight(1f))
                TonalButton("New list", { creating = true }, Modifier.padding(end = 8.dp).testTag("new-list"), icon = Glyphs.Plus)
            }
            LazyColumn(Modifier.weight(1f)) {
                items(lists, key = { it.id }) { l ->
                    TouchRow({ selected = l.id; adding = false }, selected = current?.id == l.id, minHeight = 76.dp) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(l.name, style = Type.BodyStrong, modifier = Modifier.weight(1f))
                                WatchState(l.active)
                            }
                            Text(listCoinsLine(l), style = Type.Small, modifier = Modifier.padding(top = 2.dp))
                            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                for (tf in l.timeframes) ChartTag(tf)
                            }
                        }
                    }
                    HRule()
                }
                item(key = "end") { EndSpace() }
            }
        }
    }
    val detail = @Composable {
        if (current != null && (wide || open != null)) {
            ListDetail(
                model, current, editing, { adding = it },
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
            open == null -> Column(Modifier.weight(1f)) { listOfLists() }
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
    // "Tap again" does not wait forever: a tap long after the first is not a confirmation.
    LaunchedEffect(confirmDelete) {
        if (confirmDelete) {
            delay(CONFIRM_WINDOW_MS)
            confirmDelete = false
        }
    }
    val deleteColor by animateColorAsState(
        if (confirmDelete) Palette.Down else Palette.Down.copy(alpha = 0.75f), tween(Motion.FADE_MS, easing = Motion.EaseOut), label = "delete",
    )
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
            BackRow(list.name, { setAdding(false) })
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
        if (onBack != null) BackRow(PAGE_TITLE, onBack)
        if (renaming) {
            PlainField(newName, { newName = it }, "List name")
            Row(Modifier.padding(start = 4.dp)) {
                TextAction("Save", { run { model.rename(list.id, newName).also { if (it == Outcome.Done) renaming = false } } })
                TextAction("Cancel", { renaming = false; newName = list.name }, color = Palette.Muted)
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(list.name, style = Type.Title, modifier = Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 12.dp))
                TextAction("Rename", { renaming = true })
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
            val base = c.removeSuffix("USDT")
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(base, style = Type.BodyStrong, modifier = Modifier.weight(1f))
                IconAction(Glyphs.Close, "Remove $base", { run { model.removeCoin(list.id, c) } }, tint = Palette.Muted)
            }
            HRule()
        }
        TextAction("Add coins", { setAdding(true) }, Modifier.padding(start = 4.dp).testTag("add-coins"))
        HRule()
        TextAction(
            if (confirmDelete) "Tap again to delete “${list.name}”" else "Delete this list",
            { if (confirmDelete) run { model.delete(list.id) } else confirmDelete = true },
            color = deleteColor, modifier = Modifier.padding(start = 4.dp).testTag("delete"),
        )
        Text("Deleting a list never touches paper trades already open on its coins.", style = Type.Small, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        EndSpace()
    }
}
