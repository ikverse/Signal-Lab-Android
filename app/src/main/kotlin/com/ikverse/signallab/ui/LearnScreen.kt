package com.ikverse.signallab.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** A page's title as a list row shows it: "Trend: price crosses above its average" as the name and, under it, what it is. */
internal fun splitTitle(title: String): Pair<String, String?> {
    val at = title.indexOf(": ")
    if (at <= 0) return title to null
    return title.substring(0, at) to title.substring(at + 2).replaceFirstChar { it.uppercase() }
}

/**
 * The Learn section: a page for every pattern and warning, and for how the app scores them. A wide screen shows the list beside
 * the page, with a divider that can be dragged or the list hidden.
 */
@Composable
fun LearnScreen(model: LearnModel, panels: PanelPrefs, selected: String?, onSelect: (String?) -> Unit, wide: Boolean, modifier: Modifier = Modifier, onGo: (String) -> Unit = {}) {
    val page = model.pages.firstOrNull { it.id == selected }
    val current = page ?: if (wide) model.pages.firstOrNull() else null
    val index = @Composable {
        Column(Modifier.fillMaxSize()) {
            ScreenTitle("Learn")
            LazyColumn(Modifier.weight(1f)) {
                val groups = model.pages.groupBy { it.group }
                for ((group, pages) in groups) {
                    item(key = "group-$group") { SectionLabel(group) }
                    items(pages, key = { it.id }) { p ->
                        val chosen = current?.id == p.id
                        val (name, what) = splitTitle(p.title)
                        TouchRow({ onSelect(p.id) }, selected = chosen, modifier = Modifier.testTag("learn-${p.id}"), minHeight = if (what == null) 52.dp else RowHeight) {
                            Column(Modifier.weight(1f)) {
                                Text(name, style = if (what != null || chosen) Type.BodyStrong else Type.Body)
                                if (what != null) Text(what, style = Type.Small)
                            }
                            Icon(Glyphs.ChevronRight, contentDescription = null, tint = Palette.Faint, modifier = Modifier.padding(start = 8.dp).size(18.dp))
                        }
                        HRule()
                    }
                }
            }
        }
    }
    val content = @Composable {
        if (current != null) {
            Column(Modifier.fillMaxSize()) {
                if (!wide) BackRow("Learn", { onSelect(null) })
                LearnView(current, onOpenPage = { onSelect(it) }, modifier = Modifier.weight(1f), onGo = onGo)
            }
        }
    }
    Column(modifier.fillMaxSize().testTag("learn-screen")) {
        when {
            wide -> SplitPane(panels, "learn", "pages", 300f, index, content, Modifier.weight(1f))
            current == null -> Column(Modifier.weight(1f)) { index() }
            else -> Column(Modifier.weight(1f)) { content() }
        }
    }
}
