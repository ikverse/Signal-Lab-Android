package com.ikverse.signallab.view

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.ikverse.signallab.model.CallModel
import com.ikverse.signallab.model.CallPageUi
import com.ikverse.signallab.model.CoinPageUi
import com.ikverse.signallab.model.LedgerModel
import com.ikverse.signallab.model.MarketsModel
import com.ikverse.signallab.model.SettingsModel
import com.ikverse.signallab.model.StrategiesModel
import com.ikverse.signallab.model.TodayModel
import com.ikverse.signallab.ledger.Preferences
import com.ikverse.signallab.model.AnalystModel
import com.ikverse.signallab.view.analyst.AnalystActions
import com.ikverse.signallab.view.analyst.AnalystScreen
import com.ikverse.signallab.view.analyst.RunsScreen
import com.ikverse.signallab.view.common.Icons
import com.ikverse.signallab.view.ledger.LedgerScreen
import com.ikverse.signallab.view.markets.CoinScreen
import com.ikverse.signallab.view.markets.MarketsScreen
import com.ikverse.signallab.view.settings.SettingsActions
import com.ikverse.signallab.view.settings.SettingsScreen
import com.ikverse.signallab.view.strategies.EvidenceScreen
import com.ikverse.signallab.view.strategies.StrategiesScreen
import com.ikverse.signallab.view.theme.Ink
import com.ikverse.signallab.view.theme.Type
import com.ikverse.signallab.view.today.CallScreen
import com.ikverse.signallab.view.today.TodayScreen
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/** The places in the app. */
object Routes {
    @Serializable data object Today
    @Serializable data object Ledger
    @Serializable data object Strategies
    @Serializable data object Markets
    @Serializable data object Analyst
    @Serializable data object AnalystRuns
    @Serializable data object Settings
    @Serializable data class Call(val id: Long)
    @Serializable data class Evidence(val id: String)
    @Serializable data class Coin(val symbol: String)
}

/** Everything the screens read and the few things they ask the activity to do. */
class AppModels(
    val today: TodayModel,
    val ledger: LedgerModel,
    val strategies: StrategiesModel,
    val markets: MarketsModel,
    val calls: CallModel,
    val settings: SettingsModel,
    val preferences: StateFlow<Preferences>,
    val settingsActions: SettingsActions,
    val exportCsv: () -> Unit,
    val analyst: AnalystModel? = null,
    val analystActions: AnalystActions? = null,
)

private class Tab(val route: Any, val label: String, val icon: ImageVector, val matches: (androidx.navigation.NavDestination) -> Boolean)

private val tabs = listOf(
    Tab(Routes.Today, "Today", Icons.Today) { it.hasRoute(Routes.Today::class) || it.hasRoute(Routes.Call::class) },
    Tab(Routes.Ledger, "Ledger", Icons.Ledger) { it.hasRoute(Routes.Ledger::class) },
    Tab(Routes.Strategies, "Strategies", Icons.Strategies) { it.hasRoute(Routes.Strategies::class) || it.hasRoute(Routes.Evidence::class) },
    Tab(Routes.Markets, "Markets", Icons.Markets) { it.hasRoute(Routes.Markets::class) || it.hasRoute(Routes.Coin::class) },
    Tab(Routes.Analyst, "Analyst", Icons.Analyst) { it.hasRoute(Routes.Analyst::class) || it.hasRoute(Routes.AnalystRuns::class) },
)

/** Width from which the app shows a side rail instead of a bottom bar, and Today puts the chosen call beside the list. */
const val RAIL_DP = 600
const val TWO_PANE_DP = 700

/**
 * The app's frame: a bottom bar on a phone, a side rail on a wider screen (the Fold7's inner screen, a phone held
 * sideways), and the screens. [openCall] opens a call from outside, as a notification does.
 */
@Composable
fun SignalLabRoot(models: AppModels, openCall: Long? = null) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val here = entry?.destination
    LaunchedEffect(openCall) { if (openCall != null) nav.navigate(Routes.Call(openCall)) }
    fun go(route: Any) = nav.navigate(route) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(Ink.Ground)) {
        val rail = maxWidth >= RAIL_DP.dp
        val twoPane = maxWidth >= TWO_PANE_DP.dp
        val top = here?.let { d -> tabs.none { it.route::class.let { r -> d.hasRoute(r) } } } == true
        val content = @Composable {
            Column(Modifier.fillMaxSize()) {
                if (top) BackBar { nav.popBackStack() }
                Box(Modifier.weight(1f)) { Screens(models, nav, twoPane) }
            }
        }
        if (rail) {
            Row(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                NavigationRail(containerColor = Ink.Bar, modifier = Modifier.testTag("rail")) {
                    for (t in tabs) {
                        NavigationRailItem(
                            selected = here?.let(t.matches) == true, onClick = { go(t.route) },
                            icon = { Icon(t.icon, null) }, label = { Text(t.label) },
                            colors = NavigationRailItemDefaults.colors(selectedIconColor = Ink.Accent, selectedTextColor = Ink.Text, indicatorColor = Ink.AccentSoft, unselectedIconColor = Ink.Muted, unselectedTextColor = Ink.Muted),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton({ go(Routes.Settings) }, Modifier.testTag("to-settings")) { Icon(Icons.Settings, "Settings", tint = Ink.Muted) }
                }
                VerticalDivider(color = Ink.Rule)
                Box(Modifier.weight(1f)) { content() }
            }
        } else {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Box(Modifier.weight(1f)) {
                    content()
                    if (!top) {
                        IconButton({ go(Routes.Settings) }, Modifier.align(Alignment.TopEnd).padding(8.dp).testTag("to-settings")) {
                            Icon(Icons.Settings, "Settings", tint = Ink.Body)
                        }
                    }
                }
                HorizontalDivider(color = Ink.Rule)
                NavigationBar(containerColor = Ink.Bar, modifier = Modifier.testTag("bar")) {
                    for (t in tabs) {
                        NavigationBarItem(
                            selected = here?.let(t.matches) == true, onClick = { go(t.route) },
                            icon = { Icon(t.icon, null) }, label = { Text(t.label) },
                            colors = NavigationBarItemDefaults.colors(selectedIconColor = Ink.Accent, selectedTextColor = Ink.Text, indicatorColor = Ink.AccentSoft, unselectedIconColor = Ink.Muted, unselectedTextColor = Ink.Muted),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BackBar(onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onBack, Modifier.testTag("back")) { Icon(Icons.Back, "Back", tint = Ink.Body) }
    }
}

/** "Ask the analyst" on a call's page: the question goes into the conversation and the Analyst tab opens. */
private fun ask(m: AppModels, nav: androidx.navigation.NavHostController): ((com.ikverse.signallab.model.CallUi) -> Unit)? {
    val model = m.analyst ?: return null
    return { c ->
        model.explain(c.symbol, c.strategyName, com.ikverse.signallab.view.common.Words.day(c.entryTime))
        nav.navigate(Routes.Analyst)
    }
}

@Composable
private fun Screens(m: AppModels, nav: androidx.navigation.NavHostController, twoPane: Boolean) {
    val prefs by m.preferences.collectAsStateWithLifecycle()
    NavHost(nav, startDestination = Routes.Today, modifier = Modifier.fillMaxSize()) {
        composable<Routes.Today> {
            val state by m.today.state.collectAsStateWithLifecycle()
            if (!twoPane) {
                TodayScreen(state, onOpenCall = { nav.navigate(Routes.Call(it)) }, onLedger = { nav.navigate(Routes.Ledger) },
                    onRecord = m.today::record, onDismiss = m.today::dismiss)
            } else {
                var picked by rememberSaveable { mutableStateOf<Long?>(null) }
                val first = state.newCalls.firstOrNull()?.id ?: state.open.firstOrNull()?.call?.id
                val chosen = picked ?: first
                Row(Modifier.fillMaxSize()) {
                    TodayScreen(state, onOpenCall = { picked = it }, onLedger = { nav.navigate(Routes.Ledger) },
                        modifier = Modifier.width(360.dp).fillMaxHeight(), selected = chosen, compact = false,
                        onRecord = m.today::record, onDismiss = m.today::dismiss)
                    VerticalDivider(color = Ink.Rule)
                    Box(Modifier.weight(1f)) {
                        if (chosen == null) {
                            Text("Calls show here.", style = Type.Small, modifier = Modifier.align(Alignment.Center))
                        } else {
                            val page by produceState<CallPageUi?>(null, chosen, prefs.accountSize, state) { value = m.calls.page(chosen, prefs.accountSize) }
                            CallScreen(page, onEvidence = { nav.navigate(Routes.Evidence(it)) }, onAsk = ask(m, nav))
                        }
                    }
                }
            }
        }
        composable<Routes.Call> { back ->
            val id = back.toRoute<Routes.Call>().id
            val today by m.today.state.collectAsStateWithLifecycle()
            val page by produceState<CallPageUi?>(null, id, prefs.accountSize, today) { value = m.calls.page(id, prefs.accountSize) }
            CallScreen(page, onEvidence = { nav.navigate(Routes.Evidence(it)) }, onAsk = ask(m, nav))
        }
        composable<Routes.Ledger> {
            val state by m.ledger.state.collectAsStateWithLifecycle()
            LedgerScreen(state, m.ledger::choose, onStrategy = { nav.navigate(Routes.Evidence(it)) }, onExport = m.exportCsv, wide = twoPane)
        }
        composable<Routes.Strategies> {
            val state by m.strategies.state.collectAsStateWithLifecycle()
            StrategiesScreen(state, onOpen = { nav.navigate(Routes.Evidence(it)) }, onSwitch = m.strategies::switch, onRun = m.strategies::run)
        }
        composable<Routes.Evidence> { back ->
            val id = back.toRoute<Routes.Evidence>().id
            val flow = androidx.compose.runtime.remember(id) { m.strategies.evidence(id) }
            val e by flow.collectAsStateWithLifecycle()
            EvidenceScreen(e, onRun = { m.strategies.run(id, it) })
        }
        composable<Routes.Markets> {
            val state by m.markets.state.collectAsStateWithLifecycle()
            MarketsScreen(state, m.markets::search, onCoin = { nav.navigate(Routes.Coin(it)) })
        }
        composable<Routes.Coin> { back ->
            val symbol = back.toRoute<Routes.Coin>().symbol
            val markets by m.markets.state.collectAsStateWithLifecycle()
            val page by produceState<CoinPageUi?>(null, symbol, markets) { value = m.markets.page(symbol, prefs.accountSize) }
            CoinScreen(page, onCall = { nav.navigate(Routes.Call(it)) }, onPin = { m.markets.pin(symbol, it) }, onExclude = { m.markets.exclude(symbol, it) })
        }
        composable<Routes.Analyst> {
            val model = m.analyst
            val actions = m.analystActions
            if (model == null || actions == null) {
                Text("The analyst is not set up in this build.", style = Type.Small, modifier = Modifier.padding(16.dp))
            } else {
                LaunchedEffect(Unit) { model.refresh() }
                val state by model.state.collectAsStateWithLifecycle()
                AnalystScreen(state, actions, onRuns = { nav.navigate(Routes.AnalystRuns) })
            }
        }
        composable<Routes.AnalystRuns> {
            val runs = androidx.compose.runtime.remember { m.analyst?.runs().orEmpty() }
            RunsScreen(runs)
        }
        composable<Routes.Settings> {
            val state by m.settings.state.collectAsStateWithLifecycle()
            SettingsScreen(state, m.settingsActions)
        }
    }
}
