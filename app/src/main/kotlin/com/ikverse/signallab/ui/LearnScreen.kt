package com.ikverse.signallab.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** The Learn section: a page for every pattern and warning, and for how the app scores them. A wide screen shows the list beside the page. */
@Composable
fun LearnScreen(model: LearnModel, selected: String?, onSelect: (String?) -> Unit, wide: Boolean, modifier: Modifier = Modifier) {
    val page = model.pages.firstOrNull { it.id == selected }
    val current = page ?: if (wide) model.pages.firstOrNull() else null
    Row(modifier.fillMaxSize().testTag("learn-screen")) {
        if (wide || current == null) {
            Column(Modifier.then(if (wide) Modifier.width(300.dp) else Modifier.weight(1f))) {
                ScreenTitle("Learn")
                LazyColumn(Modifier.weight(1f)) {
                    var lastGroup: String? = null
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
            if (wide) VRule()
        }
        if (current != null) {
            Column(Modifier.weight(1f)) {
                if (!wide) TextAction("‹ Learn", { onSelect(null) }, color = Palette.Muted)
                LearnView(current, onOpenPage = { onSelect(it) }, modifier = Modifier.weight(1f))
            }
        }
    }
}
