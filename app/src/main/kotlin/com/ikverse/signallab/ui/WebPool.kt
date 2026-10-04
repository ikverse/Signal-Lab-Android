package com.ikverse.signallab.ui

import android.content.Context
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/**
 * Views made once and kept, so going back to a screen does not build one again. Each key has at most one view; [use] says a screen has
 * it on show and [stopUsing] that it has let go (counted, so a screen that is rebuilt can take the view before the old one lets go).
 * Only views nobody is using are ever released by [releaseIdle]. Plain Kotlin, so it is tested without Android.
 */
class KeptViews<K : Any, V : Any>(private val create: (K) -> V, private val destroy: (V) -> Unit) {
    private val views = LinkedHashMap<K, V>()
    private val users = HashMap<K, Int>()

    /** The view for [key], made now if there is none. Not yet marked as in use. */
    fun keep(key: K): V = views.getOrPut(key) { create(key) }

    fun has(key: K): Boolean = key in views

    fun inUse(key: K): Boolean = (users[key] ?: 0) > 0

    /** The view for [key] (made if need be), marked as in use once more. */
    fun use(key: K): V = keep(key).also { users[key] = (users[key] ?: 0) + 1 }

    /** One user lets go of [key]. True when nobody is using it any more. */
    fun stopUsing(key: K): Boolean {
        val left = (users[key] ?: 0) - 1
        if (left > 0) users[key] = left else users.remove(key)
        return left <= 0
    }

    /** Destroys every view nobody is using, and says how many that was. The ones on show stay. */
    fun releaseIdle(): Int {
        val idle = views.keys.filter { !inUse(it) }
        idle.forEach { destroy(views.remove(it)!!) }
        return idle.size
    }

    /** Destroys every view, used or not: the screen that held them is gone. */
    fun releaseAll() {
        views.values.forEach(destroy)
        views.clear()
        users.clear()
    }
}

/** The bundled pages that live in a web view. */
enum class WebPage(val url: String) {
    Chart("file:///android_asset/chart/chart.html"),
    Learn("file:///android_asset/learn/page.html"),
}

/**
 * A web view the app keeps between visits. [loaded] turns true once its page has finished loading and stays true, which is how a screen
 * that comes back to a view that is already loaded knows it can use it at once. What the screen currently wants done with a message from
 * the page (a link, a change of indicators) is set by the screen on show, because the page's bridge can only be added before it loads.
 */
class KeptWeb(context: Context, val page: WebPage) {
    var loaded by mutableStateOf(false)

    @Volatile
    var onLink: (String) -> Unit = {}

    @Volatile
    var onIndicators: (List<String>) -> Unit = {}

    @Volatile
    var memory: ChartMemory = ChartMemory.Shared

    /** What the Learn page was last told to show, so coming back to the same page leaves it, and where it was scrolled to, alone. */
    var showing: String? = null

    val view: WebView = safeWebView(context, LocalOnlyClient(onLoaded = { loaded = true }, onLink = { onLink(it) })).also { v ->
        if (page == WebPage.Chart) {
            v.addJavascriptInterface(
                object {
                    @JavascriptInterface
                    fun indicatorsChanged(csv: String) = onIndicators(csv.split(',').filter { it.isNotBlank() })

                    @JavascriptInterface
                    fun drawingsChanged(json: String) = memory.rememberReport(json)
                },
                "Android",
            )
        }
        v.loadUrl(page.url)
    }

    fun destroy() = view.destroy()
}

/**
 * The app's web views. Building one freezes the screen for a moment (the first one in a process much longer, while Android's web engine
 * starts), and the pages were built again on every visit and thrown away on every leave, so a tap on Learn or Markets showed as the
 * whole app stalling. Here each is built once, put back on screen when asked for, and released only when the app has left the screen
 * (what is not on show then) or is destroyed. [make] is a hook for tests.
 */
class WebPool(private val context: Context, make: (Context, WebPage) -> KeptWeb = { c, p -> KeptWeb(c, p) }) {
    private val kept = KeptViews<WebPage, KeptWeb>({ make(context, it) }, { it.destroy() })

    /** The kept view for [page], made now if there is none. */
    fun keep(page: WebPage): KeptWeb = kept.keep(page)

    fun has(page: WebPage): Boolean = kept.has(page)

    fun inUse(page: WebPage): Boolean = kept.inUse(page)

    /** The web view for [page], free of whatever held it before and running again, ready to be put on screen. */
    fun attach(page: WebPage): WebView {
        val k = kept.use(page)
        (k.view.parent as? ViewGroup)?.removeView(k.view)
        k.view.onResume()
        return k.view
    }

    /** A screen is done with [page]'s view. When nobody is left on it, it is taken out of its parent and paused, and kept for next time. */
    fun detach(page: WebPage) {
        if (!kept.has(page)) return
        if (kept.stopUsing(page)) {
            val k = kept.keep(page)
            (k.view.parent as? ViewGroup)?.removeView(k.view)
            k.view.onPause()
        }
    }

    /** The app has left the screen: gives back the memory of the views that are not on show. */
    fun releaseIdle(): Int = kept.releaseIdle()

    /** The screen that held the views is being destroyed. */
    fun releaseAll() = kept.releaseAll()

    /**
     * Starts Android's web engine once, ahead of need, with a view that is thrown away at once. The cost of the first web view in a process
     * is mostly this, and paid here it is paid while nobody is touching the screen. Quietly nothing on a phone with no web engine.
     */
    fun warmUp() {
        if (warmed) return
        warmed = true
        try {
            WebView(context).destroy()
        } catch (_: Exception) {
            // No web engine, or it is being updated: the first real view will say so.
        }
    }

    private companion object {
        @Volatile
        var warmed = false
    }
}

/** The pool the activity made, for the web views on screen. Absent in a test, where a screen uses a pool of its own. */
val LocalWebPool = staticCompositionLocalOf<WebPool?> { null }

/** The pool the screens share, or a private one (released with the screen) where none was provided. */
@Composable
internal fun rememberWebPool(): WebPool {
    val provided = LocalWebPool.current
    val context = LocalContext.current
    val own = remember(provided) { if (provided == null) WebPool(context) else null }
    DisposableEffect(own) { onDispose { own?.releaseAll() } }
    return provided ?: own!!
}
