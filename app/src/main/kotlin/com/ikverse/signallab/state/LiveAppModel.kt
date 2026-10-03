package com.ikverse.signallab.state

import android.content.Context
import com.ikverse.signallab.BuildConfig
import com.ikverse.signallab.ui.AlertsModel
import com.ikverse.signallab.ui.AppModel
import com.ikverse.signallab.ui.DebugState
import com.ikverse.signallab.ui.LearnModel
import com.ikverse.signallab.ui.Link
import com.ikverse.signallab.ui.ListsModel
import com.ikverse.signallab.ui.MarketsModel
import com.ikverse.signallab.ui.PanelPrefs
import com.ikverse.signallab.ui.PermissionPrompt
import com.ikverse.signallab.ui.PermissionPrompts
import com.ikverse.signallab.ui.ScorecardModel
import com.ikverse.signallab.ui.SettingsModel
import com.ikverse.signallab.ui.TradesModel
import com.ikverse.signallab.ui.parseLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** The screens' whole view of the app, built on the real data layer. Made once, by the application. */
class LiveAppModel(graph: AppGraph, context: Context, scope: CoroutineScope, debugState: DebugState) : AppModel {
    private val systemScreens = MutableSharedFlow<PermissionPrompt>(extraBufferCapacity = 4)

    /** Asks the activity to open one of Android's own screens (from Settings). */
    val openSystemScreen: SharedFlow<PermissionPrompt> = systemScreens

    override val lists: ListsModel = LiveListsModel(graph, scope)
    override val markets: MarketsModel = LiveMarketsModel(graph, scope)
    override val trades: TradesModel = LiveTradesModel(graph, scope)
    override val scorecard: ScorecardModel = LiveScorecardModel(graph, scope)
    override val alerts: AlertsModel = LiveAlertsModel(graph, scope)
    override val learn: LearnModel = LearnCatalog(context)
    private val liveSettings = LiveSettingsModel(graph, context, scope, systemScreens)
    override val settings: SettingsModel = liveSettings
    override val prompts: PermissionPrompts = graph.permissions
    override val panels: PanelPrefs = LivePanelPrefs(graph, scope)
    override val debug: DebugState? = if (BuildConfig.DEBUG) debugState else null

    private val link = MutableStateFlow<Link?>(null)
    override val pendingLink: StateFlow<Link?> = link

    override fun linkUsed() {
        link.value = null
    }

    /** A notification or link opened the app: the screens move to that coin once they see it. */
    fun open(text: String?) {
        parseLink(text)?.let { link.value = it }
    }

    /** The app came back from one of Android's own screens, so what it allows may have changed. */
    suspend fun refreshSettings() = liveSettings.refresh()
}
