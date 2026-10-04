package com.ikverse.signallab.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

/**
 * The Learn section: a page for every pattern and warning, and for how the app scores them. A wide screen shows the list beside
 * the page, with a divider that can be dragged or the list hidden.
 */
@Composable
fun LearnScreen(model: LearnModel, panels: PanelPrefs, selected: String?, onSelect: (String?) -> Unit, wide: Boolean, modifier: Modifier = Modifier) {
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
                        TouchRow({ onSelect(p.id) }, selected = current?.id == p.id, modifier = Modifier.testTag("learn-${p.id}")) {
                            Text(p.title, style = Type.Body.copy(color = if (current?.id == p.id) Palette.Strong else Palette.Text))
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
                if (!wide) TextAction("‹ Learn", { onSelect(null) }, color = Palette.Muted)
                LearnView(current, onOpenPage = { onSelect(it) }, modifier = Modifier.weight(1f))
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
