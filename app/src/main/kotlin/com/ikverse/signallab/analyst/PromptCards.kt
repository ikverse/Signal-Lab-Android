package com.ikverse.signallab.analyst

/** One question the Analyst screen offers. [needsTrade] means the user picks a closed trade for it first. */
class PromptCard(val id: String, val title: String, val description: String, val task: String, val needsTrade: Boolean = false)

/**
 * The questions handed to the Claude app, each with the same standing rules: use only the numbers in the data file, respect the app's own
 * verdicts and its forward-only rule, give no instructions for real money, and start the answer with a line that names the question, so the
 * answer can be recognised when it is shared back.
 */
object PromptCards {
    /** The first line every answer is asked to start with, followed by the card's title. */
    const val MARKER = "Signal Lab report:"

    val all: List<PromptCard> = listOf(
        PromptCard(
            "weekly-review", "Weekly review", "What helped and hurt this week, and what to watch next.",
            "Review the last 7 days against everything before them. Which patterns, chart sizes and coins helped and which hurt? Is anything moving " +
                "towards a verdict, in either direction? Point out anything in how trades ended (stops, targets, time limits, how much of the best rise " +
                "was given back) that suggests an exit is set badly.",
        ),
        PromptCard(
            "fading", "What's fading?", "Patterns whose results are getting worse.",
            "Find patterns whose results are getting worse. Compare each pattern's recent closed trades with its earlier ones (vs random, win rate, how " +
                "trades ended). Say which look like they are fading, which only look that way because there are too few trades, and which are holding up.",
        ),
        PromptCard(
            "regime", "BTC regime check", "Which patterns depend on Bitcoin's trend.",
            "Using the BTC regime tables, say which patterns do better while Bitcoin is above its long average and which while it is below, and " +
                "whether each difference is large enough, given the number of trades behind it, to mean anything. Say which regime the latest trades " +
                "were in.",
        ),
        PromptCard(
            "explain-trade", "Explain a trade", "Why one paper trade won or lost.",
            "Explain the trade in the section \"The trade to explain\": what the pattern saw, what the price did after the entry (use the candles), why " +
                "it ended the way it did, and how it compares with this pattern's other trades. Say whether the result looks like the pattern working, " +
                "the pattern failing, or noise.",
            needsTrade = true,
        ),
        PromptCard(
            "charts", "1h vs 4h", "Each pattern on each chart size you use, not only these two.",
            "Compare the chart sizes in use. For each pattern that runs on more than one chart size, which size gives the best and which the worst " +
                "results vs random, with how many trades behind each? Which chart sizes produce mostly noise: many trades, and nothing beating random?",
        ),
        PromptCard(
            "suggest-patterns", "Suggest 3 patterns", "New ideas for the lab, written so the app can test them.",
            "Suggest up to 3 new patterns worth forward-testing, from what the data shows: for example a pattern that does well in only one BTC " +
                "regime or on one chart size, made stricter, or a filter that would have avoided the worst trades. Prefer simple ideas of one to " +
                "three conditions, and do not repeat a pattern already in the scorecard or the lab. For each, say in a sentence what in the data " +
                "suggests it and how many trades stand behind that. Each idea counts as one more pattern tested, which raises the bar for every " +
                "verdict, so suggest only ideas you would defend.\n\n" + LabFormat.guide,
        ),
    )

    fun byId(id: String): PromptCard? = all.firstOrNull { it.id == id }

    /** The question, as it stands above the data. [patternsTested] is the count the scorecard's bar rises with. */
    fun prompt(card: PromptCard, patternsTested: Int): String = buildString {
        append("You are a skeptical quantitative analyst reviewing the record of Signal Lab, my crypto paper-trading research app. ")
        append("The data below, from the heading \"Signal Lab data file\" on, is a snapshot of that record: every trade in it is a paper trade, ")
        append("after costs, compared with random entries on the same coin.\n\n")
        append("Task: ").append(card.task).append("\n\n")
        append("Rules:\n")
        append("- Begin your answer with this exact line: ").append(MARKER).append(' ').append(card.title).append('\n')
        append("- Use only numbers from the data. If it does not hold what you need, say so instead of estimating.\n")
        append("- Name the number of trades behind every claim. Under 30 closed trades is an anecdote, not evidence.\n")
        append("- The bar for an edge rises with every pattern tested ($patternsTested so far). Do not call anything an edge that the app's verdict does not.\n")
        append("- Forward-only patterns can only be judged on live trades.\n")
        append("- Give no instructions for trading real money. Describe the evidence; the decisions are mine.\n")
        append("- Write for someone new to trading: short plain sentences, and say what any technical term means the first time you use it.\n")
        append("- Answer in Markdown, in under 600 words, with tables where they help.\n")
        append("- End with a section \"What to watch next\": up to three things the coming weeks of data could confirm or refute.\n")
    }

    /** The whole draft handed to Claude: the question, then [data] (see [Snapshot]). */
    fun draft(card: PromptCard, patternsTested: Int, data: String): String = prompt(card, patternsTested) + "\n" + data
}

/** A text shared into the app, read as a report: the question it answered (when it says), a title, and the body to show. */
class SharedReport(val card: String?, val title: String, val body: String)

object ReportText {
    /**
     * True for text that is only a link (what the Claude app's Share sends: the address of the chat, not its words), possibly with a short
     * title line. A real answer is far longer than the little that is left once the addresses are taken out.
     */
    fun isLink(text: String): Boolean {
        val link = Regex("""https?://\S+""")
        return link.containsMatchIn(text) && link.replace(text, "").trim().length < LINK_ONLY_CHARS
    }

    private const val LINK_ONLY_CHARS = 120

    /** The longest text kept as a report. Far beyond any answer; it only stops a stray paste of a whole book. */
    const val MAX_CHARS = 300_000
    private const val MAX_TITLE = 80

    /** Markdown decoration around a line ("# ", "**", "_") that is not part of its words. */
    private val decoration = Regex("""^[#>*_\s]+|[*_\s]+$""")

    /**
     * Reads [text] as a report. An answer that starts (within its first few lines) with the marker line gets the card that line names and
     * loses the line; any other text is kept whole, titled by its first line. Null when there is nothing to keep.
     */
    fun read(text: String): SharedReport? {
        val body = text.replace("\r\n", "\n").trim()
        if (body.isEmpty()) return null
        val lines = body.lines()
        val markerAt = lines.take(5).indexOfFirst { decoration.replace(it, "").startsWith(PromptCards.MARKER, ignoreCase = true) }
        if (markerAt >= 0) {
            val title = decoration.replace(lines[markerAt], "").substring(PromptCards.MARKER.length).trim().ifEmpty { "Report" }
            val card = PromptCards.all.firstOrNull { it.title.equals(title, ignoreCase = true) }?.id
            val rest = lines.filterIndexed { i, _ -> i != markerAt }.joinToString("\n").trim()
            return SharedReport(card, title.take(MAX_TITLE), rest.ifEmpty { title })
        }
        val first = decoration.replace(lines.first { it.isNotBlank() }, "").ifEmpty { "Shared text" }
        return SharedReport(null, if (first.length > MAX_TITLE) first.take(MAX_TITLE - 1) + "…" else first, body)
    }

    /**
     * [markdown] made safe to show: shared text can come from any app, and the viewer would run any HTML in it. Every "<" outside code is
     * written as an entity (code is escaped by the viewer itself), and a link to a script becomes a link to nowhere.
     */
    fun safe(markdown: String): String {
        val out = StringBuilder(markdown.length + 64)
        var fence: String? = null
        for ((i, line) in markdown.lines().withIndex()) {
            if (i > 0) out.append('\n')
            val trimmed = line.trimStart()
            val opens = Regex("""^(`{3,}|~{3,})""").find(trimmed)?.value
            when {
                fence != null -> {
                    out.append(line)
                    if (opens != null && opens.first() == fence.first() && opens.length >= fence.length && trimmed.trimEnd() == opens) fence = null
                }
                opens != null -> {
                    out.append(line)
                    fence = opens
                }
                else -> out.append(escapeOutsideCode(line))
            }
        }
        return out.toString().replace(Regex("""\]\(\s*javascript:""", RegexOption.IGNORE_CASE), "](#")
    }

    /** Escapes "<" in [line] except inside a backtick code span that is closed on the same line. */
    private fun escapeOutsideCode(line: String): String {
        val sb = StringBuilder(line.length + 16)
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            if (ch == '`') {
                var run = 0
                while (i + run < line.length && line[i + run] == '`') run++
                val ticks = "`".repeat(run)
                val close = line.indexOf(ticks, i + run)
                if (close >= 0) {
                    sb.append(line, i, close + run)
                    i = close + run
                    continue
                }
                sb.append(ticks)
                i += run
                continue
            }
            sb.append(if (ch == '<') "&lt;" else ch.toString())
            i++
        }
        return sb.toString()
    }
}
