package com.ikverse.signallab.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The smallest a touch target gets: 48 dp, as Android's own guidelines ask. */
val MinTouch = 48.dp

/** How tall a row of a list is at least, so two lines of text sit in it with room around them. */
val RowHeight = 60.dp

/** A width that grows with the phone's text-size setting (never below [base]), so a label in a fixed slot is not clipped at large sizes. */
@Composable
fun scaledWithText(base: Dp): Dp = base * LocalDensity.current.fontScale.coerceAtLeast(1f)

@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = Type.Title, modifier = modifier.padding(horizontal = 16.dp, vertical = 12.dp))
}

/** A section's name in sentence case, with how many things are in it when that helps. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, count: Int? = null) {
    Row(
        modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text, style = Type.Section)
        if (count != null) CountBadge(count)
    }
}

/** A small number in a rounded tag, beside a section's name. */
@Composable
fun CountBadge(count: Int, modifier: Modifier = Modifier) {
    Text(
        "$count", style = Type.Label.copy(color = Palette.Text, fontFeatureSettings = "tnum"),
        modifier = modifier.clip(RoundedCornerShape(9.dp)).background(Palette.TagFill).padding(horizontal = 7.dp, vertical = 1.dp),
    )
}

/** Press feedback for a small control: it shrinks a touch while held and comes back the moment it is let go. */
@Composable
fun Modifier.pressScale(source: MutableInteractionSource): Modifier {
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) Motion.PRESSED_SCALE else 1f, tween(Motion.PRESS_MS, easing = Motion.EaseOut), label = "press")
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

/** Plain coloured text that does something when touched. No box, no outline. */
@Composable
fun TextAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, color: Color = Palette.Accent) {
    val source = remember { MutableInteractionSource() }
    Box(
        modifier.pressScale(source).heightIn(min = MinTouch)
            .clickable(interactionSource = source, indication = LocalIndication.current, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Type.BodyStrong.copy(color = if (enabled) color else Palette.Muted))
    }
}

/** A filled button for the one thing a place is mostly for: "Show on chart", "Paste an answer", "Save". */
@Composable
fun TonalButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true, stretch: Boolean = false) {
    ButtonShape(text, onClick, modifier, icon, enabled, fill = Palette.AccentTint, edge = null, color = Palette.OnAccentTint, stretch = stretch)
}

/**
 * A button beside a [TonalButton] that matters as much but is not the first thing to do. A [danger] one, that undoes or switches something
 * off ("Turn off"), is red.
 */
@Composable
fun LineButton(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true, stretch: Boolean = false,
    danger: Boolean = false,
) {
    ButtonShape(
        text, onClick, modifier, icon, enabled, fill = Color.Transparent, edge = if (danger) Palette.Down.copy(alpha = 0.6f) else Palette.ChipEdge,
        color = if (danger) Palette.Down else Palette.Strong, stretch = stretch,
    )
}

@Composable
private fun ButtonShape(text: String, onClick: () -> Unit, modifier: Modifier, icon: ImageVector?, enabled: Boolean, fill: Color, edge: Color?, color: Color, stretch: Boolean = false) {
    val source = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier.pressScale(source).heightIn(min = MinTouch)
            .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // At least 40 dp tall, and taller when a long label (or a large text size) needs a second line: the label is never cut.
        Row(
            Modifier.then(if (stretch) Modifier.fillMaxWidth() else Modifier).heightIn(min = 40.dp).graphicsLayer { alpha = if (enabled) 1f else 0.45f }.clip(shape).background(fill)
                .then(if (edge != null) Modifier.border(1.dp, edge, shape) else Modifier).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        ) {
            if (icon != null) Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
            Text(text, style = Type.BodyStrong.copy(color = color), textAlign = TextAlign.Center)
        }
    }
}

/** An icon that does something when touched: a full 48 dp to touch, and named for anyone who cannot see it. */
@Composable
fun IconAction(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = Palette.Text, enabled: Boolean = true) {
    val source = remember { MutableInteractionSource() }
    Box(
        modifier.size(MinTouch).pressScale(source).clip(CircleShape)
            .clickable(interactionSource = source, indication = LocalIndication.current, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (enabled) tint else Palette.Faint, modifier = Modifier.size(22.dp))
    }
}

/** One of a few choices side by side, as a chip: the chosen one is a filled pill, the others an outline. */
@Composable
fun ChoiceText(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(17.dp)
    val fill by animateColorAsState(if (selected) Palette.ChipFill else Color.Transparent, tween(Motion.FADE_MS), label = "chip")
    Box(
        modifier.heightIn(min = MinTouch).clickable(role = Role.Tab, onClick = onClick).padding(horizontal = 3.dp)
            .semantics { contentDescription = if (selected) "$text, chosen" else text },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.heightIn(min = 34.dp).clip(shape).background(fill).border(1.dp, if (selected) Palette.ChipEdge else Palette.Rule, shape)
                .padding(horizontal = 14.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, style = Type.BodyStrong.copy(fontSize = 14.sp, color = if (selected) Palette.Strong else Palette.Muted), textAlign = TextAlign.Center)
        }
    }
}

/** One of several choices that can each be on or off, such as the chart sizes a list watches: a tick shows the ones that are on. */
@Composable
fun TickChip(text: String, checked: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier.heightIn(min = MinTouch).toggleable(checked, role = Role.Checkbox, onValueChange = { onClick() }).padding(horizontal = 3.dp)
            .semantics { contentDescription = "$text, ${if (checked) "ticked" else "not ticked"}" },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.heightIn(min = 32.dp).clip(shape).background(if (checked) Palette.AccentTint else Color.Transparent)
                .border(1.dp, if (checked) Palette.AccentTint else Palette.Rule, shape).padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (checked) Icon(Glyphs.Check, contentDescription = null, tint = Palette.OnAccentTint, modifier = Modifier.size(15.dp))
            Text(text, style = Type.BodyStrong.copy(fontSize = 14.sp, color = if (checked) Palette.OnAccentTint else Palette.Muted))
        }
    }
}

/**
 * Tabs of equal width across the screen. The chosen one is bright, with a short bar under it that slides to whichever tab is chosen
 * next. [tag] names a tab for the tests.
 */
@Composable
fun <T> Tabs(items: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier, tag: (T) -> String? = { null }) {
    // At least 48 dp tall; a label too long for its share of the width takes a second line and the whole strip grows with it.
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val each = maxWidth / items.size.coerceAtLeast(1)
        val index = items.indexOf(selected).coerceAtLeast(0)
        val x by animateDpAsState(each * index, tween(Motion.ENTER_MS, easing = Motion.EaseOut), label = "tab")
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).heightIn(min = MinTouch)) {
            for (item in items) {
                val chosen = item == selected
                val text = label(item)
                Box(
                    Modifier.weight(1f).fillMaxHeight().selectable(chosen, role = Role.Tab, onClick = { onSelect(item) })
                        .semantics { contentDescription = if (chosen) "$text, chosen" else text }
                        .then(tag(item)?.let { Modifier.testTag(it) } ?: Modifier).padding(horizontal = 6.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text, style = Type.BodyStrong.copy(color = if (chosen) Palette.Strong else Palette.Muted), textAlign = TextAlign.Center)
                }
            }
        }
        HRule(Modifier.align(Alignment.BottomStart))
        Box(
            Modifier.align(Alignment.BottomStart).offset(x = x + each * 0.24f).width(each * 0.52f).height(3.dp)
                .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)).background(Palette.Accent),
        )
    }
}

/**
 * A few short choices in one rounded strip, the chosen one lifted: the chart sizes over a chart. Each choice answers to a full 48 dp
 * though the strip is drawn slimmer.
 */
@Composable
fun Segmented(items: List<String>, selected: String?, onSelect: (String) -> Unit, modifier: Modifier = Modifier, minItem: Dp = 44.dp) {
    Box(modifier.heightIn(min = MinTouch), contentAlignment = Alignment.CenterStart) {
        Box(Modifier.matchParentSize().padding(vertical = 5.dp).clip(RoundedCornerShape(10.dp)).background(Palette.Raised))
        Row(Modifier.padding(horizontal = 3.dp)) {
            for (item in items) {
                val chosen = item == selected
                Box(
                    Modifier.heightIn(min = MinTouch).widthIn(min = minItem).selectable(chosen, role = Role.Tab, onClick = { onSelect(item) })
                        .semantics { contentDescription = if (chosen) "$item, chosen" else item },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier.heightIn(min = 32.dp).clip(RoundedCornerShape(7.dp)).background(if (chosen) Palette.ChipFill else Color.Transparent)
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(item, style = Type.BodyStrong.copy(fontSize = 14.sp, color = if (chosen) Palette.Strong else Palette.Muted))
                    }
                }
            }
        }
    }
}

/** The way back from a page to its list, on a phone held upright: an arrow, and the name of where it leads. */
@Composable
fun BackRow(to: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconAction(Glyphs.Back, "Back to $to", onBack)
        Text(to, style = Type.Small)
    }
}

/** A row you can touch. The chosen one is raised, with a thin accent line down its left edge. */
@Composable
fun TouchRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    minHeight: Dp = RowHeight,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier.fillMaxWidth().height(IntrinsicSize.Min).heightIn(min = minHeight).background(if (selected) Palette.Raised else Color.Transparent)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.width(3.dp).fillMaxHeight().padding(vertical = 10.dp)
                .clip(RoundedCornerShape(topEnd = 3.dp, bottomEnd = 3.dp)).background(if (selected) Palette.Accent else Color.Transparent),
        )
        Row(Modifier.weight(1f).padding(start = 13.dp, end = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, content = content)
    }
}

@Composable
fun CheckRow(checked: Boolean, title: String, subtitle: String?, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().heightIn(min = MinTouch).clickable(role = Role.Checkbox, onClick = onToggle)
            .semantics { contentDescription = "$title, ${if (checked) "ticked" else "not ticked"}" }.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TickBox(checked)
        Column(Modifier.padding(start = 14.dp).weight(1f)) {
            Text(title, style = Type.BodyStrong)
            if (subtitle != null) Text(subtitle, style = Type.Small, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** A tick box: an outlined square, filled with the accent and a tick when it is on. Always the same width, so the rows beside it line up. */
@Composable
fun TickBox(checked: Boolean, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(5.dp)
    Box(
        modifier.size(22.dp).clip(shape).background(if (checked) Palette.Accent else Color.Transparent)
            .border(1.5.dp, if (checked) Palette.Accent else Palette.Muted, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) Icon(Glyphs.Check, contentDescription = null, tint = Palette.Background, modifier = Modifier.size(16.dp))
    }
}

@Composable
fun LabelValue(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = Palette.Text) {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = Type.Small)
        Text(value, style = Type.Number.copy(color = valueColor))
    }
}

/** A caption over a number, the way a stat sits in a strip or a grid. [align] lines up the pair at the start, centre or end. */
@Composable
fun Stat(caption: String, value: String, modifier: Modifier = Modifier, valueColor: Color = Palette.Text, big: Boolean = false, align: Alignment.Horizontal = Alignment.Start) {
    Column(modifier, horizontalAlignment = align) {
        Text(caption, style = Type.Small)
        Text(value, style = (if (big) Type.Kpi else Type.Number).copy(color = valueColor))
    }
}

/** A change as a coloured pill: green on faint green when up, red on faint red when down. All pills in a column share a width. */
@Composable
fun ChangePill(fraction: Double?, modifier: Modifier = Modifier, small: Boolean = false) {
    val color = Fmt.changeColor(fraction)
    val fill = when (color) {
        Palette.Up -> Palette.UpTint
        Palette.Down -> Palette.DownTint
        else -> Palette.TagFill
    }
    Text(
        Fmt.signedPercent(fraction), textAlign = TextAlign.Center, maxLines = 1,
        style = Type.NumberStrong.copy(color = color, fontSize = if (small) 12.5.sp else 14.sp),
        modifier = modifier.widthIn(min = scaledWithText(if (small) 62.dp else 74.dp)).clip(RoundedCornerShape(6.dp)).background(fill)
            .padding(horizontal = 6.dp, vertical = if (small) 2.dp else 3.dp),
    )
}

/** A chart size ("15m", "4h") as a small tag. */
@Composable
fun ChartTag(label: String, modifier: Modifier = Modifier) {
    Text(
        label, maxLines = 1, style = Type.Label.copy(color = Palette.TagText, fontFeatureSettings = "tnum"),
        modifier = modifier.clip(RoundedCornerShape(5.dp)).background(Palette.TagFill).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** Things that belong together, on a raised surface with rounded corners and a thin outline; with an [edge] colour, outlined in that instead. */
@Composable
fun RaisedGroup(modifier: Modifier = Modifier, edge: Color? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(shape).background(Palette.Raised)
            .then(if (edge != null) Modifier.border(1.5.dp, edge, shape) else Modifier.border(1.dp, Palette.Edge, shape)),
        content = content,
    )
}

/** A thin line between two rows of a raised group, starting where the rows' text starts. */
@Composable
fun RowDivider(modifier: Modifier = Modifier) {
    HRule(modifier.padding(start = 16.dp))
}

/** The space left at the end of a page that scrolls, so its last line never sits against the bar under it. */
@Composable
fun EndSpace() {
    Spacer(Modifier.height(32.dp))
}

/**
 * The buttons of a group or a card, on a row of their own: 12 dp below what comes before, 16 dp in from the edges, and 8 dp apart. When they
 * do not fit side by side they move onto the next line instead of being squeezed. A row that starts with a [TextAction] passes a smaller
 * [start], since that action brings its own inset and its words should line up with the text above.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ActionRow(modifier: Modifier = Modifier, start: Dp = 16.dp, content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(
        modifier.fillMaxWidth().padding(start = start, end = 16.dp, top = 12.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically, content = content,
    )
}

/**
 * A row of chips (filters, chart sizes, choices) that wraps onto another line when they do not all fit, so none is ever off screen. Its
 * chips line up with the 16 dp edge of the text around them.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChipRow(modifier: Modifier = Modifier, content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(modifier.fillMaxWidth().padding(horizontal = 13.dp), itemVerticalAlignment = Alignment.CenterVertically, content = content)
}

/** The least a [FitText] shrinks its text to, as a share of its size: past this it takes another line instead. */
const val MIN_TEXT_SCALE = 0.85f

/**
 * Text in a space of its own that it should fill on one line, such as a price in a box: when it does not fit it shrinks, a step at a time,
 * down to [minScale] of its size, and if it still does not fit it goes onto more lines at that size. It is never cut off.
 */
@Composable
fun FitText(text: String, style: TextStyle, modifier: Modifier = Modifier, textAlign: TextAlign? = null, minScale: Float = MIN_TEXT_SCALE) {
    var scale by remember(text, style) { mutableFloatStateOf(1f) }
    var wrap by remember(text, style) { mutableStateOf(false) }
    var settled by remember(text, style) { mutableStateOf(false) }
    Text(
        text,
        // Drawn once it has found its size, so a reader never sees it shrink.
        modifier.drawWithContent { if (settled) drawContent() },
        style = style.copy(
            fontSize = style.fontSize * scale,
            lineHeight = if (style.lineHeight.isSpecified) style.lineHeight * scale else style.lineHeight,
        ),
        textAlign = textAlign, maxLines = if (wrap) Int.MAX_VALUE else 1, softWrap = wrap,
        onTextLayout = { r ->
            when {
                wrap || !r.hasVisualOverflow -> settled = true
                scale > minScale + 0.001f -> scale = maxOf(minScale, scale - 0.05f)
                else -> wrap = true
            }
        },
    )
}

/**
 * Three prices side by side, each value under its own name, in three equal columns that start at the same edge: a trade's loss limit, entry
 * and profit goal. A value too long for its column shrinks a little and then takes another line, with a gap kept between the columns.
 */
@Composable
fun LevelTrio(a: String, av: String, b: String, bv: String, c: String, cv: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for ((name, value) in listOf(a to av, b to bv, c to cv)) {
            Column(Modifier.weight(1f)) {
                Text(name, style = Type.Small)
                FitText(value, Type.Number)
            }
        }
    }
}

/** Where [value] falls between [low] and [high], from 0 to 1, or null when the range is empty or a number is missing. */
fun rangeFraction(low: Double?, high: Double?, value: Double?): Float? {
    if (low == null || high == null || value == null || !(high > low)) return null
    return ((value - low) / (high - low)).coerceIn(0.0, 1.0).toFloat()
}

/**
 * A bar from a loss on the left to a gain on the right: faint red up to [split] (where the trade was entered, a tick marks it) and
 * faint green after it, which fades out when [openEnded] (a stop that follows the price has no fixed target). A dot marks [marker]
 * (the price now, or where the trade ended) in [markerColor]. All positions run from 0 to 1. [ring] is the surface it sits on.
 */
@Composable
fun RangeBar(split: Float, marker: Float?, markerColor: Color, modifier: Modifier = Modifier, openEnded: Boolean = false, ring: Color = Palette.Raised) {
    Canvas(modifier.fillMaxWidth().height(16.dp)) {
        val bar = 6.dp.toPx()
        val top = (size.height - bar) / 2
        val at = size.width * split.coerceIn(0f, 1f)
        val radius = CornerRadius(bar / 2, bar / 2)
        drawRoundRect(Palette.Rule, Offset(0f, top), Size(size.width, bar), radius)
        drawRoundRect(Palette.Down.copy(alpha = 0.32f), Offset(0f, top), Size(at, bar), radius)
        if (openEnded) {
            val steps = 12
            val w = (size.width - at) / steps
            for (i in 0 until steps) drawRect(Palette.Up.copy(alpha = 0.42f * (1f - i / steps.toFloat())), Offset(at + i * w, top), Size(w + 0.5f, bar))
        } else {
            drawRoundRect(Palette.Up.copy(alpha = 0.42f), Offset(at, top), Size(size.width - at, bar), radius)
        }
        drawRect(Palette.Text, Offset(at - 1.dp.toPx(), 0f), Size(2.dp.toPx(), size.height))
        if (marker != null) {
            val c = Offset(size.width * marker.coerceIn(0f, 1f), size.height / 2)
            drawCircle(ring, 7.dp.toPx(), c)
            drawCircle(markerColor, 4.5.dp.toPx(), c)
        }
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

/** A search box: a filled, rounded field with a magnifier, so it reads as a place to type at a glance. [description] names it. */
@Composable
fun SearchField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, description: String = placeholder) {
    BasicTextField(
        value = value, onValueChange = onChange, textStyle = Type.Body.copy(color = Palette.Strong), singleLine = true,
        cursorBrush = SolidColor(Palette.Accent),
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(min = MinTouch).semantics { contentDescription = description },
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp)).background(Palette.Raised).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Glyphs.Search, contentDescription = null, tint = Palette.Muted, modifier = Modifier.size(18.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(placeholder, style = Type.Body.copy(color = Palette.Muted), maxLines = 1)
                    inner()
                }
            }
        },
    )
}

/** A short number to type in, such as a fee: a filled box with the figure on the right and [suffix] after it. [description] names it. */
@Composable
fun NumberField(value: String, onChange: (String) -> Unit, description: String, modifier: Modifier = Modifier, suffix: String = "%", keyboard: KeyboardOptions = KeyboardOptions.Default) {
    BasicTextField(
        value = value, onValueChange = onChange, singleLine = true, keyboardOptions = keyboard,
        textStyle = Type.Number.copy(color = Palette.Strong, textAlign = TextAlign.End), cursorBrush = SolidColor(Palette.Accent),
        modifier = modifier.width(scaledWithText(84.dp)).heightIn(min = MinTouch).semantics { contentDescription = description },
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 42.dp).clip(RoundedCornerShape(10.dp)).background(Palette.TagFill)
                    .border(1.dp, Palette.Rule, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { inner() }
                Spacer(Modifier.width(4.dp))
                Text(suffix, style = Type.Small)
            }
        },
    )
}

/**
 * A switch with its name and a line on what it does. [more], when given, is the full explanation: an info button beside the name opens
 * it in place under the row, in a box of its own, and closes it again. The name and its line get the full width up to the switch.
 */
@Composable
fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, more: String? = null) {
    var open by rememberSaveable(title) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        // The whole row is the control, so the touch target is the full row and never just the small switch.
        Row(
            modifier.fillMaxWidth().heightIn(min = 56.dp)
                .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
                .semantics { contentDescription = "$title, ${if (checked) "on" else "off"}" }
                .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = Type.BodyStrong, modifier = Modifier.weight(1f, fill = false))
                    if (more != null) InlineInfo("About $title", open) { open = !open }
                }
                if (subtitle != null) Text(subtitle, style = Type.Small, modifier = Modifier.padding(top = 4.dp))
            }
            Spacer(Modifier.width(16.dp))
            Switch(
                checked = checked, onCheckedChange = null,
                colors = SwitchDefaults.colors(checkedTrackColor = Palette.Accent, checkedThumbColor = Palette.Strong, uncheckedTrackColor = Palette.Rule, uncheckedThumbColor = Palette.Muted, uncheckedBorderColor = Palette.Rule),
            )
        }
        if (open && more != null) {
            Text(
                more, style = Type.Small.copy(color = Palette.Text),
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(Palette.TagFill).padding(12.dp),
            )
        }
    }
}

/**
 * The small info button that sits right after a name. It takes only a short line's height, so the line under the name stays close to it,
 * while what it answers to is a full 48 dp touch that reaches a little above and below that line.
 */
@Composable
private fun InlineInfo(description: String, open: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Box(Modifier.height(28.dp).wrapContentHeight(unbounded = true), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(width = 44.dp, height = MinTouch).pressScale(source)
                .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
                .semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Glyphs.Info, contentDescription = null, tint = if (open) Palette.Accent else Palette.Muted, modifier = Modifier.size(18.dp))
        }
    }
}
