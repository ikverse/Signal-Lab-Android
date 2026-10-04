package com.ikverse.signallab.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.drop
import java.util.Locale

/** The sums behind resizable panels, all in dp and all plain functions so a test can hold each to its word. */
object PaneMath {
    /** The narrowest a side panel (the coin list, the details) gets, and the narrowest the chart is left. */
    const val MIN_SIDE = 160f
    const val MIN_CHART = 240f

    /** The narrowest the page beside a list (a list's details, a Learn page) is left when the list is dragged wider. */
    const val MIN_PAGE = 240f

    /** For the details panel under the chart on a small phone held sideways: its least height, and the least left to the chart. */
    const val MIN_PANE_HEIGHT = 100f
    const val MIN_CHART_HEIGHT = 140f

    /** The strip a divider takes. */
    const val DIVIDER = 28f

    /**
     * The widths the coin list and the details are drawn at, in a row of coins, chart and details with a divider between each.
     * What was asked for is held between the minimum and what still leaves the chart its minimum; a hidden panel is nothing.
     */
    fun fit(total: Float, coins: Float, details: Float, coinsHidden: Boolean, detailsHidden: Boolean): Pair<Float, Float> {
        val room = total - 2 * DIVIDER - MIN_CHART
        val d = if (detailsHidden) 0f else details.coerceIn(MIN_SIDE, maxOf(MIN_SIDE, room - if (coinsHidden) 0f else MIN_SIDE))
        val c = if (coinsHidden) 0f else coins.coerceIn(MIN_SIDE, maxOf(MIN_SIDE, room - d))
        return c to d
    }

    /** The size of one panel next to a chart: [size] held between [min] and what leaves [minRest] for the rest, after [dividers]. */
    fun fitOne(total: Float, size: Float, hidden: Boolean, min: Float, minRest: Float, dividers: Float): Float =
        if (hidden) 0f else size.coerceIn(min, maxOf(min, total - dividers - minRest))

    /** A panel at [shown] dragged by [delta], held between [min] and [max]. */
    fun dragged(shown: Float, delta: Float, max: Float, min: Float = MIN_SIDE): Float = (shown + delta).coerceIn(min, maxOf(min, max))
}

/**
 * How big each panel was made and which are hidden, by name. Kept across rotation, and written out as text so it can be kept between
 * runs. A size nobody changed is not stored, so the screen's own default applies.
 */
class PaneLayout {
    private val sizes = mutableStateMapOf<String, Float>()
    private val hidden = mutableStateMapOf<String, Boolean>()

    fun size(key: String, default: Float): Float = sizes[key] ?: default

    fun set(key: String, value: Float) {
        sizes[key] = value
    }

    fun isHidden(key: String): Boolean = hidden[key] == true

    fun toggle(key: String) {
        if (isHidden(key)) hidden.remove(key) else hidden[key] = true
    }

    /** "coins=280.0;details=320.0;!coins" : sizes first, then the hidden ones marked with "!". */
    fun encode(): String =
        (sizes.entries.sortedBy { it.key }.map { "${it.key}=${String.format(Locale.ROOT, "%.1f", it.value)}" } + hidden.keys.sorted().map { "!$it" })
            .joinToString(";")

    /** Takes back what [encode] wrote. Anything unreadable is ignored; null leaves the layout as it is. */
    fun restore(text: String?) {
        if (text == null) return
        sizes.clear()
        hidden.clear()
        for (part in text.split(';')) {
            if (part.startsWith("!") && part.length > 1) hidden[part.substring(1)] = true
            else part.substringBefore('=', "").takeIf { it.isNotEmpty() }?.let { key ->
                part.substringAfter('=').toFloatOrNull()?.takeIf { it.isFinite() && it > 0f }?.let { sizes[key] = it }
            }
        }
    }

    companion object {
        val Saver: Saver<PaneLayout, String> = Saver(save = { it.encode() }, restore = { PaneLayout().also { l -> l.restore(it) } })
    }
}

/**
 * The layout for the screen called [key]: kept across rotation, filled from what was saved on an earlier run once that has been read,
 * and written back whenever the user changes it.
 */
@Composable
fun rememberPaneLayout(prefs: PanelPrefs, key: String): PaneLayout {
    val layout = rememberSaveable(key, saver = PaneLayout.Saver) { PaneLayout() }
    val stored by prefs.saved.collectAsStateWithLifecycle()
    val loaded = stored != null
    LaunchedEffect(loaded) {
        if (!loaded) return@LaunchedEffect
        layout.restore(stored?.get(key))
        // The first value is what was just restored; only the user's own changes after it are saved.
        snapshotFlow { layout.encode() }.drop(1).collect { prefs.save(key, it) }
    }
    return layout
}

/**
 * A thin line between two panels, with a grip to drag it and a small button that hides the panel beside it (or brings it back).
 * [vertical] is a line standing between panels that sit side by side. [onDrag] gets how far it moved, in dp, along the line's normal.
 */
@Composable
fun PaneDivider(
    vertical: Boolean,
    hidden: Boolean,
    label: String,
    arrow: String,
    onDrag: (Float) -> Unit,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current.density
    val drag = rememberDraggableState { px -> onDrag(px / density) }
    Box(
        modifier.then(if (vertical) Modifier.width(PaneMath.DIVIDER.dp).fillMaxHeight() else Modifier.height(PaneMath.DIVIDER.dp).fillMaxWidth())
            .background(Palette.Background)
            .draggable(drag, if (vertical) Orientation.Horizontal else Orientation.Vertical, enabled = !hidden),
        contentAlignment = Alignment.Center,
    ) {
        if (vertical) VRule() else HRule()
        // The button is the full 48 dp to touch even where the strip is thinner.
        Box(
            Modifier.requiredSize(if (vertical) PaneMath.DIVIDER.dp else 64.dp, MinTouch).background(Palette.Raised)
                .clickable(role = Role.Button, onClick = onToggle)
                .semantics { contentDescription = "${if (hidden) "Show" else "Hide"} $label" },
            contentAlignment = Alignment.Center,
        ) {
            Text(arrow, style = Type.BodyStrong.copy(color = Palette.Muted))
        }
    }
}

/**
 * A list on the left and the page it opens on the right, with a divider between them that drags and a button that hides the
 * list (or brings it back). The list's width is kept between runs under [key]. Used by Lists and Learn on a screen wide enough
 * for two panes.
 */
@Composable
fun SplitPane(
    panels: PanelPrefs,
    key: String,
    label: String,
    defaultWidth: Float,
    list: @Composable () -> Unit,
    page: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pane = rememberPaneLayout(panels, key)
    BoxWithConstraints(modifier.fillMaxSize().testTag("split-$key")) {
        val total = maxWidth.value
        val hidden = pane.isHidden("list")
        val w = PaneMath.fitOne(total, pane.size("list", defaultWidth), hidden, PaneMath.MIN_SIDE, PaneMath.MIN_PAGE, PaneMath.DIVIDER)
        Row(Modifier.fillMaxSize()) {
            if (!hidden) Column(Modifier.width(w.dp).pane("list")) { list() }
            PaneDivider(
                vertical = true, hidden = hidden, label = label, arrow = if (hidden) "›" else "‹",
                onDrag = { pane.set("list", PaneMath.dragged(w, it, max = total - PaneMath.DIVIDER - PaneMath.MIN_PAGE)) },
                onToggle = { pane.toggle("list") }, modifier = Modifier.testTag("divider-$key"),
            )
            Column(Modifier.weight(1f).pane("page")) { page() }
        }
    }
}

/** A wrapper so the screens can tag the panes they draw. */
fun Modifier.pane(name: String): Modifier = testTag("pane-$name")
