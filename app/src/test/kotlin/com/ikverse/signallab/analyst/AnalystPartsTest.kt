package com.ikverse.signallab.analyst

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ikverse.signallab.engine.strategy.AnalystCall
import com.ikverse.signallab.ledger.AnalystNotes
import com.ikverse.signallab.ledger.LedgerDatabase
import com.ikverse.signallab.ledger.PreferenceStore
import com.ikverse.signallab.ledger.SettingRow
import com.ikverse.signallab.ledger.Suggestion
import com.ikverse.signallab.ledger.SuggestionState
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class AnalystPartsTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var ledger: LedgerDatabase
    private lateinit var dir: File

    @Before
    fun setUp() {
        ledger = LedgerDatabase.open(context, name = null)
        dir = Files.createTempDirectory("keys").toFile()
    }

    @After
    fun tearDown() {
        ledger.close()
        dir.deleteRecursively()
    }

    /** Stands in for the Keystore, which Robolectric does not have: reversible, and not the plain text. */
    private object Flip : SecretCipher {
        override fun encrypt(plain: ByteArray) = ByteArray(plain.size) { (plain[it].toInt() xor 0x5A).toByte() }
        override fun decrypt(sealed: ByteArray) = encrypt(sealed)
    }

    @Test
    fun theKeyIsCheckedSealedAndForgotten() {
        val file = File(dir, ApiKeyStore.FILE_NAME)
        val store = ApiKeyStore(file, Flip)
        assertFalse(store.isSet)
        assertEquals("A Claude API key starts with sk-ant-.", store.save("abc-123-456-789-000-000-000-000-000"))
        assertEquals("A key has no spaces in it.", store.save("sk-ant-api03 xyz"))
        val key = "sk-ant-api03-" + "x".repeat(40) + "abcd"
        assertNull(store.save("  $key \n"))
        assertTrue(store.isSet)
        assertFalse(String(file.readBytes()).contains("sk-ant"), "the file does not hold the key in plain text")
        assertEquals(key, ApiKeyStore(file, Flip).load(), "a fresh store reads it back")
        assertEquals("abcd", store.hint())
        store.clear()
        assertFalse(file.exists())
        assertNull(store.load())
    }

    @Test
    fun pricesFollowTheModelAndTheMonthResets() = runTest {
        val u = Usage(input = 1_000_000, output = 100_000, cacheRead = 1_000_000, cacheWrite = 100_000, searches = 3)
        assertEquals(4.0 + 2.0 + 0.2 + 0.5 + 0.03, ClaudeModel.OPUS.cost(u), 1e-9)
        assertEquals(2.0 + 1.0 + 0.2 + 0.25 + 0.03, ClaudeModel.SONNET.cost(u), 1e-9)
        assertEquals(ClaudeModel.OPUS, ClaudeModel.of("unknown"))
        var t = 1_760_000_000_000L // October 2025
        val meter = SpendMeter(ledger.dao()) { t }
        meter.add(0.25)
        meter.add(0.5)
        meter.add(-1.0)
        assertEquals(0.75, meter.month(), 1e-9)
        t += 40L * 86_400_000L
        assertEquals(0.0, meter.month(), "a new month starts at nothing")
    }

    @Test
    fun aRecordedSuggestionWaitsForTheRunnerAndAnOldOneExpires() = runTest {
        var t = 1_000_000_000_000L
        val prefs = PreferenceStore(ledger.dao())
        val s = Suggestions(ledger.dao(), prefs) { t }
        fun put(id: String, at: Long) = Suggestion(id, "ETHUSDT", 0.05, 0.1, 10, "why", at)
        for (x in listOf(put("a", t), put("b", t - 4 * 86_400_000L))) {
            ledger.dao().putSetting(SettingRow(AnalystNotes.SUGGESTION_PREFIX + x.id, AnalystNotes.encode(x), t))
        }
        assertTrue(s.record("a"))
        assertFalse(s.record("a"), "recorded once")
        assertEquals(true, prefs.current().switches[AnalystCall.id], "the owner's own calls show on Today")
        val pending = s.accepted()
        assertEquals(listOf("a"), pending.map { it.id })
        assertEquals(t, pending.single().acceptedAt)
        assertEquals(SuggestionState.EXPIRED, prefs.current().suggestions.first { it.id == "b" }.state)
        t += 1000
        s.opened("a", 42, t)
        val a = assertNotNull(prefs.current().suggestions.first { it.id == "a" })
        assertEquals(SuggestionState.OPENED, a.state)
        assertEquals(42L, a.callId)
        assertTrue(s.accepted().isEmpty())
        assertFalse(s.dismiss("a"), "an opened call cannot be taken back")
    }

    @Test
    fun theHandoffCarriesTheInstructionsTheSnapshotAndTheQuestion() {
        val text = AnalystPrompt.handoff(mapOf("market_state" to "{\"btc\":1}"), "Is Breakout working?")
        assertTrue(text.contains("You are the research analyst inside Signal Lab"))
        assertFalse(text.contains("(strategy"), "the rule language is left out: the Claude app cannot test rules")
        assertTrue(text.contains("market_state:\n{\"btc\":1}"))
        assertTrue(text.endsWith("My question: Is Breakout working?"))
    }
}
