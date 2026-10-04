package com.ikverse.signallab.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ikverse.signallab.engine.Refusal
import com.ikverse.signallab.engine.Timeframe
import com.ikverse.signallab.engine.Watchlist
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private val ctx: Context get() = ApplicationProvider.getApplicationContext()

/**
 * Making a list with its coins in one step. The bug this guards: the first list was made empty and then filled coin by coin, and the
 * screen that asked was replaced the moment the empty list appeared, which cancelled the filling. The user was left with an empty
 * list that was switched off, and could not add to it either.
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class WatchlistCreateWithTest {
    private val names = (1..200).map { "C$it" }

    private fun setup(dbName: String? = null, clock: () -> Long = { 1L }): WatchlistRepository = runBlocking {
        val m = FakeMarket(EPOCH_START + 10 * DAY).also { fm ->
            val pairs = names.mapIndexed { i, n -> pair(n, 1000.0 - i, 10.0, 5.0) }
            fm.symbols = pairs.map { it.first }
            fm.tickers = pairs.map { it.second }
        }
        val st = CandleStore(ctx, name = null, io = Dispatchers.Unconfined)
        val u = UniverseRepository(m, st, clock = { m.now })
        u.refreshIfStale()
        WatchlistRepository(RecordDatabase(ctx, dbName), u, clock = clock, io = Dispatchers.Unconfined).also { it.load() }
    }

    private fun sym(i: Int) = "C${i}USDT"

    private fun ok(r: WatchlistResult<Watchlist>): Watchlist = assertIs<WatchlistResult.Ok<Watchlist>>(r).value

    private fun refused(r: WatchlistResult<Watchlist>): Refusal = assertIs<WatchlistResult.Refused>(r).reason

    @Test
    fun aListIsMadeWithItsCoinsChartsAndSwitchAllInOneStepAndTheScreenNeverSeesItHalfMade() = runTest {
        val w = setup()
        val seen = mutableListOf<List<Watchlist>>()
        val watcher = launch(UnconfinedTestDispatcher(testScheduler)) { w.lists.collect { seen += it } }
        val made = ok(w.createWith("  My   coins ", listOf(sym(3), sym(1), sym(2), sym(3)), setOf(Timeframe.H1, Timeframe.H4), activate = true))
        watcher.cancel()
        assertEquals("My coins", made.name)
        assertEquals(listOf(sym(3), sym(1), sym(2)), made.symbols, "the coins keep the order they were chosen in, and the repeat is dropped")
        assertTrue(made.active)
        assertEquals(setOf(Timeframe.H1, Timeframe.H4), made.timeframes)
        assertEquals(made, w.lists.value.single())
        // Every list the screens were ever shown was either no list or the finished one.
        assertEquals(2, seen.size)
        assertTrue(seen.first().isEmpty())
        assertEquals(made, seen.last().single())
    }

    @Test
    fun theWholeListSurvivesARestart() = runTest {
        val name = "createwith-restart.db"
        ctx.deleteDatabase(name)
        val made = ok(setup(name).createWith("Mine", listOf(sym(1), sym(2)), setOf(Timeframe.M15), activate = true))
        val reopened = setup(name)
        assertEquals(made, reopened.lists.value.single())
    }

    @Test
    fun aListNotSwitchedOnIsLeftOff() = runTest {
        val made = ok(setup().createWith("Mine", listOf(sym(1)), setOf(Timeframe.H1), activate = false))
        assertFalse(made.active)
    }

    @Test
    fun aCallerWhoGoesAwayWhileTheListIsBeingWrittenStillGetsAWholeListAndTheRepositoryKnowsIt() = runTest {
        lateinit var caller: Job
        // The clock is read inside the write: cancelling there is the screen being replaced at the worst moment.
        val w = setup(clock = { caller.cancel(); 1L })
        caller = launch(start = CoroutineStart.LAZY) {
            w.createWith("Mine", listOf(sym(1), sym(2)), setOf(Timeframe.H1), activate = true)
        }
        caller.start()
        caller.join()
        val list = w.lists.value.single()
        assertEquals(listOf(sym(1), sym(2)), list.symbols)
        assertTrue(list.active)
    }

    @Test
    fun aCallerWhoGoesAwayBeforeAnythingIsWrittenLeavesNothing() = runTest {
        val w = setup()
        val caller = launch(start = CoroutineStart.LAZY) { w.createWith("Mine", listOf(sym(1)), setOf(Timeframe.H1), activate = true) }
        caller.cancel()
        caller.join()
        assertTrue(w.lists.value.isEmpty())
    }

    @Test
    fun everyRefusalLeavesNothingBehind() = runTest {
        val w = setup()
        assertEquals(Refusal.NAME_BLANK, refused(w.createWith("  ", listOf(sym(1)), setOf(Timeframe.H1), true)))
        assertEquals(Refusal.NAME_TOO_LONG, refused(w.createWith("x".repeat(41), listOf(sym(1)), setOf(Timeframe.H1), true)))
        assertEquals(Refusal.NO_TIMEFRAME, refused(w.createWith("A", listOf(sym(1)), emptySet(), true)))
        assertEquals(Refusal.UNKNOWN_COIN, refused(w.createWith("A", listOf(sym(1), "NOTACOINUSDT"), setOf(Timeframe.H1), true)))
        assertEquals(Refusal.LIST_FULL, refused(w.createWith("A", (1..31).map(::sym), setOf(Timeframe.H1), true)))
        assertTrue(w.lists.value.isEmpty())
        // A name already taken is refused too, whatever its case, and the first list is untouched.
        ok(w.createWith("Mine", listOf(sym(1)), setOf(Timeframe.H1), true))
        assertEquals(Refusal.NAME_TAKEN, refused(w.createWith("MINE", listOf(sym(2)), setOf(Timeframe.H1), true)))
        assertEquals(listOf(sym(1)), w.lists.value.single().symbols)
    }

    @Test
    fun theCapsOnWhatIsSwitchedOnApplyToANewListToo() = runTest {
        val w = setup()
        for (l in 0 until 5) ok(w.createWith("L$l", (1..30).map { l * 30 + it }.map(::sym), setOf(Timeframe.H1), true)) // 150 coins on
        assertEquals(Refusal.OVER_ACTIVE_CAP, refused(w.createWith("Extra", listOf(sym(151)), setOf(Timeframe.H1), true)))
        assertEquals(5, w.lists.value.size, "the refused list was not left behind")
        assertFalse(ok(w.createWith("Extra", listOf(sym(151)), setOf(Timeframe.H1), activate = false)).active, "switched off, it is allowed")
        val fast = setup()
        assertEquals(Refusal.OVER_FAST_CAP, refused(fast.createWith("Fast", (1..11).map(::sym), setOf(Timeframe.M1), true)))
        assertTrue(fast.lists.value.isEmpty())
    }
}
