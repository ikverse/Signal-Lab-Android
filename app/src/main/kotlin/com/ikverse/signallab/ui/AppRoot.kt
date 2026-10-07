package com.ikverse.signallab.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The whole app: one frame that picks its shape from the width it is given. [debug] is true in debug builds, which adds the
 * debug page behind a long press on the version in Settings.
 */
@Composable
fun SignalLabApp(model: AppModel, debug: Boolean, webViews: Boolean = true, nav: NavState = rememberNavState(), webPool: WebPool? = null) {
    CompositionLocalProvider(LocalWebPool provides webPool) {
        SignalLabTheme(webViews) {
            PermissionDialogs(model.prompts)
            // Clear of the system bars and of the notch or punch hole, whatever size and side the phone reports them on.
            BoxWithConstraints(Modifier.fillMaxSize().background(Palette.Background).windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout)).imePadding()) {
                val layout = LayoutClass.of(maxWidth.value)
                Frame(model, debug, layout, nav)
            }
        }
    }
}

@Composable
private fun Frame(model: AppModel, debug: Boolean, layout: LayoutClass, nav: NavState) {
    val lists by model.lists.lists.collectAsStateWithLifecycle()
    val loaded by model.lists.loaded.collectAsStateWithLifecycle()
    val download by model.lists.download.collectAsStateWithLifecycle()
    val link by model.pendingLink.collectAsStateWithLifecycle()
    val railOnRight = model.settings.settings.collectAsStateWithLifecycle().value.railOnRight

    // What a phone held upright shows that a wide screen does not, so Back knows which of its steps are real.
    SideEffect { nav.narrow = layout == LayoutClass.Compact }

    // A notification (or link) opened the app: go where it leads, once.
    LaunchedEffect(link) {
        link?.let {
            nav.openLink(it, fromApp = false)
            model.linkUsed()
        }
    }
    BackHandler(enabled = nav.canBack) { nav.back() }

    when {
        !loaded -> Column(Modifier.fillMaxSize().testTag("loading")) {}
        lists.isEmpty() -> SetupScreen(model.lists)
        nav.showDebug && debug && model.debug != null -> DebugScreen(model.debug!!, onBack = { nav.showDebug = false })
        else -> Column(Modifier.fillMaxSize()) {
            DownloadBanner(download)
            if (layout == LayoutClass.Compact) {
                Column(Modifier.weight(1f)) { Content(model, debug, layout, nav) }
                HRule()
                BottomBar(nav)
            } else {
                Row(Modifier.weight(1f)) {
                    if (!railOnRight) {
                        SideRail(nav)
                        VRule()
                    }
                    Column(Modifier.weight(1f)) { Content(model, debug, layout, nav) }
                    if (railOnRight) {
                        VRule()
                        SideRail(nav)
                    }
                }
            }
        }
    }
}

@Composable
private fun Content(model: AppModel, debug: Boolean, layout: LayoutClass, nav: NavState) {
    val openVariant = { variant: String -> nav.openLearn(model.learn.pageForVariant(variant)) }
    val lists by model.lists.lists.collectAsStateWithLifecycle()
    val scanning = model.settings.settings.collectAsStateWithLifecycle().value.backgroundScanning
    // Each place keeps what the user had set in it (a filter, a half-filled form) while another place is on show.
    val holder = rememberSaveableStateHolder()
    holder.SaveableStateProvider(nav.dest.name) {
        when (nav.dest) {
            Dest.Markets -> MarketsScreen(
                model.markets, model.trades, model.alerts, model.panels, layout, nav, onOpenLearn = openVariant, onOpenLists = { nav.go(Dest.Lists) },
                lists = lists, scanning = scanning, onOpenAlerts = { nav.openAlerts(AlertGroup.All, fromApp = true) },
            )
            Dest.Trades -> TradesScreen(
                model.trades, nav, onOpenCoin = { s, tf, id -> nav.openCoin(s, tf, fromApp = true, trade = id) }, onOpenLearn = openVariant,
                wide = layout != LayoutClass.Compact, prices = latestPrices(model),
            )
            Dest.Scorecard -> ScorecardScreen(model.scorecard, onOpenLearn = openVariant, wide = layout != LayoutClass.Compact, onOpenPage = { nav.openLearn(it) })
            Dest.Analyst -> AnalystScreen(
                model.analyst, model.trades, model.panels, nav.analystReport, { nav.analystReport = it }, wide = layout != LayoutClass.Compact,
                onOpenPage = { nav.openLearn(it) }, onGo = { nav.openPlace(it) },
            )
            Dest.Alerts -> AlertsScreen(
                model.alerts, nav, onOpen = { nav.openLink(it, fromApp = true) }, wide = layout != LayoutClass.Compact,
                trades = model.trades.trades.collectAsStateWithLifecycle().value, prices = latestPrices(model),
                onOpenCoin = { s, tf, id -> nav.openCoin(s, tf, fromApp = true, trade = id) }, onOpenLearn = openVariant,
            )
            Dest.Learn -> LearnScreen(model.learn, model.panels, nav.learnPage, { nav.learnPage = it }, wide = layout != LayoutClass.Compact, onGo = { nav.openPlace(it) })
            Dest.Lists -> ListsScreen(model.lists, model.panels, wide = layout != LayoutClass.Compact)
            Dest.Settings -> SettingsScreen(
                model.settings, hasDebug = debug && model.debug != null, onOpenDebug = { nav.showDebug = true },
                wide = layout != LayoutClass.Compact, onOpenPage = { nav.openLearn(it) },
            )
            Dest.More -> MoreScreen(nav)
        }
    }
}

/** Each watched coin's newest price: the live one while it streams, else the last stored close. For where an open trade stands now. */
@Composable
private fun latestPrices(model: AppModel): Map<String, Double> {
    val coins by model.markets.coins.collectAsStateWithLifecycle()
    val live by model.markets.prices.collectAsStateWithLifecycle()
    return coins.mapNotNull { c -> c.price?.let { c.symbol to it } }.toMap() + live
}

/** The bar's five places in a narrow column, centred, that scrolls if the screen is ever too short for them. */
@Composable
private fun SideRail(nav: NavState) {
    Box(Modifier.width(scaledWithText(72.dp)).fillMaxHeight().testTag("rail"), contentAlignment = Alignment.Center) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            for (d in BarPlaces) BarItem(d, barPlaceOf(nav.dest) == d, Modifier.fillMaxWidth().height(62.dp)) { nav.go(d) }
        }
    }
}

/** The bar's five places, side by side across the bottom. */
@Composable
private fun BottomBar(nav: NavState) {
    Row(Modifier.fillMaxWidth().height(64.dp).testTag("bottom-bar")) {
        for (d in BarPlaces) BarItem(d, barPlaceOf(nav.dest) == d, Modifier.weight(1f).fillMaxHeight()) { nav.go(d) }
    }
}

/** A place in a bar: its icon over its name. The chosen one's icon sits on a tinted pill and its name is bright. */
@Composable
private fun BarItem(d: Dest, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val pill by animateColorAsState(if (selected) Palette.AccentTint else Color.Transparent, tween(Motion.FADE_MS), label = "bar")
    Column(
        modifier.selectable(selected, role = Role.Tab, onClick = onClick).testTag("nav-${d.name}"),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(52.dp, 30.dp).clip(RoundedCornerShape(15.dp)).background(pill), contentAlignment = Alignment.Center) {
            Icon(d.icon, contentDescription = null, tint = if (selected) Palette.OnAccentTint else Palette.Muted, modifier = Modifier.size(22.dp))
        }
        Text(
            d.label, style = Type.Label.copy(color = if (selected) Palette.Strong else Palette.Muted), maxLines = 1,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}
