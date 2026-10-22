package com.ikverse.signallab.view.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ikverse.signallab.engine.strategy.Tier
import com.ikverse.signallab.view.theme.Ink
import com.ikverse.signallab.view.theme.Type

/** A raised block on the ground. [onClick] makes the whole block one button. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    outlined: Boolean = false,
    ground: Color = Ink.Surface,
    padding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    var m = modifier.fillMaxWidth().clip(shape).background(ground)
    if (outlined) m = m.border(BorderStroke(1.dp, Ink.Outline), shape)
    if (onClick != null) m = m.clickable(role = Role.Button, onClick = onClick)
    Column(m.padding(padding), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
}

/** A list of rows inside one panel, with a rule between them. */
@Composable
fun RowsPanel(modifier: Modifier = Modifier, rows: List<@Composable () -> Unit>) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Ink.Surface)) {
        rows.forEachIndexed { i, row ->
            if (i > 0) HorizontalDivider(color = Ink.Rule, thickness = 1.dp)
            row()
        }
    }
}

/** A tappable row with room for a finger. */
@Composable
fun TapRow(onClick: (() -> Unit)?, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    var m = modifier.fillMaxWidth().heightIn(min = 48.dp)
    if (onClick != null) m = m.clickable(role = Role.Button, onClick = onClick)
    Row(m.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), content = content)
}

/** A screen's title. [onSettings] puts the settings button on its right, so it scrolls away with the title. */
@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier, onSettings: (() -> Unit)? = null) {
    if (onSettings == null) {
        Text(text, style = Type.Title, modifier = modifier.semantics { heading() })
        return
    }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = Type.Title, modifier = Modifier.weight(1f).semantics { heading() })
        IconButton(onSettings, Modifier.testTag("to-settings")) { Icon(Icons.Settings, "Settings", tint = Ink.Body) }
    }
}

/** A section's heading, with an optional count and an optional thing on the right. */
@Composable
fun Section(text: String, count: Int? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp, start = 2.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text, style = Type.Heading, modifier = Modifier.semantics { heading() })
        if (count != null) Text("$count", style = Type.Number.copy(color = Ink.Muted, fontSize = 14.sp))
        Box(Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
fun Overline(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = Type.Overline, modifier = modifier)
}

/** A number in the monospaced face. */
@Composable
fun Num(text: String, modifier: Modifier = Modifier, color: Color = Ink.Text, size: Int = 15, bold: Boolean = false) {
    Text(text, modifier = modifier, style = Type.Number.copy(color = color, fontSize = size.sp, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal))
}

/** A signed result in its colour: mint up, coral down, plain at zero. */
@Composable
fun Signed(fraction: Double?, modifier: Modifier = Modifier, size: Int = 15, bold: Boolean = false, suffix: String = "") {
    if (fraction == null || fraction.isNaN()) {
        Num("–", modifier, Ink.Muted, size)
        return
    }
    val color = when {
        fraction > 0.0005 -> Ink.Gain
        fraction < -0.0005 -> Ink.Loss
        else -> Ink.Body
    }
    Num(Words.pct(fraction) + suffix, modifier, color, size, bold)
}

/** A small rounded label. */
@Composable
fun Pill(text: String, fg: Color, bg: Color = Color.Transparent, line: Color? = null, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(999.dp)
    var m = modifier.clip(shape).background(bg)
    if (line != null) m = m.border(1.dp, line, shape)
    Text(text, style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = fg), modifier = m.padding(horizontal = 9.dp, vertical = 3.dp))
}

@Composable
fun LongPill(modifier: Modifier = Modifier) = Pill("Long", Ink.Gain, Ink.GainGround, modifier = modifier)

/** How sure the app is about a strategy, as a label. */
@Composable
fun TierPill(tier: Tier, modifier: Modifier = Modifier) = when (tier) {
    Tier.PROVEN -> Pill(tier.label, Ink.Gain, line = Ink.Gain.copy(alpha = 0.5f), modifier = modifier)
    Tier.PROMISING -> Pill(tier.label, Ink.Info, line = Ink.InfoLine, modifier = modifier)
    Tier.WATCH -> Pill(tier.label, Ink.Watch, line = Ink.WatchLine, modifier = modifier)
    Tier.TESTING -> Pill(tier.label, Ink.Muted, line = Ink.Outline, modifier = modifier)
    Tier.FAILING -> Pill(tier.label, Ink.Loss, line = Ink.Loss.copy(alpha = 0.5f), modifier = modifier)
}

@Composable
fun Dot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(8.dp).clip(CircleShape).background(color))
}

/** The one strong button on a screen. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick, modifier.height(48.dp), enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = Ink.Accent, contentColor = Ink.OnAccent),
    ) { Text(text, fontWeight = FontWeight.Bold, fontSize = 15.sp) }
}

@Composable
fun QuietButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(
        onClick, modifier.height(44.dp), enabled = enabled, border = BorderStroke(1.dp, Ink.Outline),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink.Text),
    ) { Text(text, fontSize = 14.sp) }
}

/** An on/off switch in the app's colours, with its label as the row. */
@Composable
fun SwitchRow(title: String, detail: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    TapRow({ onChange(!checked) }) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = Type.Body.copy(color = Ink.Text))
            if (detail != null) Text(detail, style = Type.Caption)
        }
        Switch(
            checked, onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Ink.OnAccent, checkedTrackColor = Ink.Accent,
                uncheckedThumbColor = Ink.Faint, uncheckedTrackColor = Ink.Raised, uncheckedBorderColor = Ink.Outline,
            ),
        )
    }
}

/** A label and its value on one row. */
@Composable
fun FactRow(label: String, value: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Type.Body.copy(color = Ink.Body), modifier = Modifier.weight(1f))
        value()
    }
}

/** A labelled figure in a grid cell. */
@Composable
fun Figure(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = Ink.Text, note: String? = null) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = Type.Caption)
        Num(value, color = valueColor, size = 16, bold = true)
        if (note != null) Text(note, style = Type.Caption.copy(fontSize = 11.sp))
    }
}

/** What a screen says while it has nothing to show. */
@Composable
fun Empty(title: String, text: String) {
    Panel {
        Text(title, style = Type.Subhead)
        Text(text, style = Type.Body.copy(color = Ink.Muted))
    }
}
