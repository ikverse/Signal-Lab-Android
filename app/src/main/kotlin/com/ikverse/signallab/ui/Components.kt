package com.ikverse.signallab.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** The smallest a touch target gets: 48 dp, as Android's own guidelines ask. */
val MinTouch = 48.dp

@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = Type.Title, modifier = modifier.padding(horizontal = 16.dp, vertical = 12.dp))
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = Type.Label, modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp))
}

/** Plain coloured text that does something when touched. No box, no outline. */
@Composable
fun TextAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, color: Color = Palette.Accent) {
    Box(
        modifier.heightIn(min = MinTouch).clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Type.BodyStrong.copy(color = if (enabled) color else Palette.Muted))
    }
}

/** One of a few choices side by side: the chosen one is bright and underlined, the others are muted. */
@Composable
fun ChoiceText(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.heightIn(min = MinTouch).clickable(role = Role.Tab, onClick = onClick).padding(horizontal = 10.dp)
            .semantics { contentDescription = if (selected) "$text, chosen" else text },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = (if (selected) Type.BodyStrong else Type.Body.copy(color = Palette.Muted)).copy(textDecoration = if (selected) TextDecoration.Underline else null),
            maxLines = 1,
        )
    }
}

/** A row you can touch. The chosen one has a thin accent line down its left edge. */
@Composable
fun TouchRow(onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.fillMaxWidth().heightIn(min = MinTouch).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(2.dp).heightIn(min = MinTouch).background(if (selected) Palette.Accent else Color.Transparent))
        Row(Modifier.weight(1f).padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, content = content)
    }
}

@Composable
fun CheckRow(checked: Boolean, title: String, subtitle: String?, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().heightIn(min = MinTouch).clickable(role = Role.Checkbox, onClick = onToggle)
            .semantics { contentDescription = "$title, ${if (checked) "ticked" else "not ticked"}" }.padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (checked) "[x]" else "[ ]", style = Type.Number.copy(color = if (checked) Palette.Accent else Palette.Muted))
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(title, style = Type.BodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = Type.Small, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun LabelValue(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = Palette.Text) {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = Type.Small)
        Text(value, style = Type.Number.copy(color = valueColor))
    }
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = Type.Heading)
        Text(body, style = Type.Body.copy(color = Palette.Muted))
        action?.invoke()
    }
}

/** Something went wrong: said in a sentence, in warm colour, with nothing hidden. */
@Composable
fun ProblemState(text: String, modifier: Modifier = Modifier) {
    Text(text, style = Type.Body.copy(color = Palette.Warn), modifier = modifier.fillMaxWidth().padding(16.dp).testTag("problem"))
}

/** A line of text to type into, with a thin rule under it. */
@Composable
fun PlainField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboard: KeyboardOptions = KeyboardOptions.Default,
    singleLine: Boolean = true,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        // The whole 48 dp line is the field, so touching anywhere on it starts typing.
        BasicTextField(
            value = value, onValueChange = onChange, textStyle = Type.Body.copy(color = Palette.Strong), singleLine = singleLine,
            cursorBrush = SolidColor(Palette.Accent), keyboardOptions = keyboard,
            modifier = Modifier.fillMaxWidth().heightIn(min = MinTouch).semantics { contentDescription = placeholder },
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth().heightIn(min = MinTouch), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(placeholder, style = Type.Body.copy(color = Palette.Muted))
                    inner()
                }
            },
        )
        HRule()
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    // The whole row is the control, so the touch target is the full row and never just the small switch.
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .semantics { contentDescription = "$title, ${if (checked) "on" else "off"}" }
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = Type.BodyStrong)
            if (subtitle != null) Text(subtitle, style = Type.Small)
        }
        Switch(
            checked = checked, onCheckedChange = null,
            colors = SwitchDefaults.colors(checkedTrackColor = Palette.Accent, checkedThumbColor = Palette.Strong, uncheckedTrackColor = Palette.Rule, uncheckedThumbColor = Palette.Muted, uncheckedBorderColor = Palette.Rule),
        )
    }
}
