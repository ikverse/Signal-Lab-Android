package com.ikverse.signallab.data.futures

import com.ikverse.signallab.data.DataConfig
import com.ikverse.signallab.data.binance.BinanceClient
import org.json.JSONObject
import java.time.OffsetDateTime

/** What an exchange announcement says about a coin, as far as Signal Lab acts on it. */
enum class NoticeKind { DELISTING, CAUTION }

/** One exchange announcement about one coin: where it came from, its id there, when it was published, and its headline. */
data class Notice(val source: String, val id: String, val time: Long, val kind: NoticeKind, val coin: String, val title: String)

/**
 * Binance's and Upbit's announcements, read from their public pages (no key). A delisting notice comes 3 to 14 days before
 * the coin stops trading and the price usually falls hard in between (docs/strategy-research.md §7), so a coin named in one
 * gets no new calls. Both pages are undocumented and may change or refuse a network: callers treat a failure as "no news",
 * never as a failed pass.
 */
class NoticesClient(
    private val binance: BinanceClient,
    private val binanceHost: String = DataConfig.BINANCE_WWW_HOST,
    private val upbitHost: String = DataConfig.UPBIT_HOST,
) {
    /** The newest Binance spot delisting notices (its catalog 161), one per coin named. */
    suspend fun binanceDelistings(count: Int = 30): List<Notice> =
        binance.fetchFrom(binanceHost, "/bapi/composite/v1/public/cms/article/list/query",
            mapOf("type" to "1", "pageNo" to "1", "pageSize" to count.toString(), "catalogId" to "161")) { text ->
            val articles = JSONObject(text).getJSONObject("data").getJSONArray("catalogs").getJSONObject(0).getJSONArray("articles")
            (0 until articles.length()).flatMap { i ->
                val a = articles.getJSONObject(i)
                val title = a.getString("title")
                binanceCoins(title).map { coin -> Notice("binance", a.get("id").toString(), a.getLong("releaseDate"), NoticeKind.DELISTING, coin, title) }
            }
        }

    /** The newest Upbit trade notices that end a coin's trading or mark it for caution. */
    suspend fun upbitNotices(count: Int = 30): List<Notice> =
        binance.fetchFrom(upbitHost, "/api/v1/announcements", mapOf("os" to "web", "page" to "1", "per_page" to count.toString(), "category" to "trade")) { text ->
            val notices = JSONObject(text).getJSONObject("data").getJSONArray("notices")
            (0 until notices.length()).mapNotNull { i ->
                val n = notices.getJSONObject(i)
                val title = n.getString("title")
                val kind = upbitKind(title) ?: return@mapNotNull null
                val coin = upbitCoin(title) ?: return@mapNotNull null
                Notice("upbit", n.get("id").toString(), OffsetDateTime.parse(n.getString("listed_at")).toInstant().toEpochMilli(), kind, coin, title)
            }
        }

    companion object {
        private val TICKER = Regex("""^[A-Z0-9]{2,12}$""")
        private val BINANCE_DELIST = Regex("""^Binance Will Delist (.+?)(?: on \d{4}-\d{2}-\d{2}.*)?$""")

        /**
         * The coins a Binance spot delisting headline names, such as EPIC and QUICK in "Binance Will Delist EPIC, QUICK on
         * 2026-10-28". Futures, margin, Alpha and Earn notices share the catalog and name no spot coin, so they give none.
         */
        fun binanceCoins(title: String): List<String> {
            val lower = title.lowercase()
            if (listOf("futures", "perpetual", "margin", "alpha", "earn", "pairs").any { it in lower }) return emptyList()
            val m = BINANCE_DELIST.find(title.trim()) ?: return emptyList()
            return m.groupValues[1].split(",", "&", " and ").map { it.trim() }.filter { TICKER.matches(it) }
        }

        /** Upbit's wording: "거래지원 종료" ends trading; "거래 유의" marks a coin for caution, often the step before. */
        fun upbitKind(title: String): NoticeKind? = when {
            "거래지원 종료" in title -> NoticeKind.DELISTING
            "거래 유의" in title && "해제" !in title -> NoticeKind.CAUTION
            else -> null
        }

        /** The ticker in an Upbit headline such as "질리카(ZIL) 거래지원 종료 안내 (11/16 15:00)": the capitals in the first brackets. */
        fun upbitCoin(title: String): String? = Regex("""\(([A-Z0-9]{2,12})\)""").find(title)?.groupValues?.get(1)
    }
}
