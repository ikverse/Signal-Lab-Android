package com.ikverse.signallab.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.remember
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
    // Each place keeps what the user had set in it (a filter, a half-filled form) while another place is on show.
    val holder = rememberSaveableStateHolder()
    holder.SaveableStateProvider(nav.dest.name) {
        when (nav.dest) {
            Dest.Markets -> MarketsScreen(model.markets, model.trades, model.alerts, model.panels, layout, nav, onOpenLearn = openVariant, onOpenLists = { nav.go(Dest.Lists) })
            Dest.Trades -> TradesScreen(model.trades, nav, onOpenCoin = { s, tf -> nav.openCoin(s, tf, fromApp = true) }, onOpenLearn = openVariant)
            Dest.Scorecard -> ScorecardScreen(model.scorecard, onOpenLearn = openVariant, wide = layout != LayoutClass.Compact)
            Dest.Alerts -> AlertsScreen(model.alerts, nav, onOpen = { nav.openLink(it, fromApp = true) })
            Dest.Learn -> LearnScreen(model.learn, model.panels, nav.learnPage, { nav.learnPage = it }, wide = layout != LayoutClass.Compact, onGo = { nav.openPlace(it) })
            Dest.Lists -> ListsScreen(model.lists, model.panels, wide = layout != LayoutClass.Compact)
            Dest.Settings -> SettingsScreen(model.settings, hasDebug = debug && model.debug != null, onOpenDebug = { nav.showDebug = true })
        }
    }
}

@Composable
private fun SideRail(nav: NavState) {
    Column(Modifier.width(scaledWithText(112.dp)).fillMaxHeight().testTag("rail")) {
        for (d in Dest.entries) {
            TouchRow({ nav.go(d) }, selected = nav.dest == d, modifier = Modifier.testTag("nav-${d.name}")) {
                Text(d.label, style = if (nav.dest == d) Type.BodyStrong else Type.Body.copy(color = Palette.Muted))
            }
        }
    }
}

/** Every place in one row that scrolls sideways; the place on show is kept in view, so a link or Back that lands on a far one reveals it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BottomBar(nav: NavState) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("bottom-bar")) {
        for (d in Dest.entries) {
            val inView = remember { BringIntoViewRequester() }
            val selected = nav.dest == d
            LaunchedEffect(selected) { if (selected) inView.bringIntoView() }
            BarItem(d.label, selected, "nav-${d.name}", Modifier.bringIntoViewRequester(inView)) { nav.go(d) }
        }
    }
}

@Composable
private fun BarItem(label: String, selected: Boolean, tag: String, modifier: Modifier, onClick: () -> Unit) {
    TouchRow(onClick, modifier = modifier.width(scaledWithText(96.dp)).testTag(tag)) {
        Text(
            label, style = if (selected) Type.BodyStrong.copy(color = Palette.Accent) else Type.Small.copy(color = Palette.Muted),
            modifier = Modifier.weight(1f), maxLines = 1, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}
