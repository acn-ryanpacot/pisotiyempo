package com.pisotiyempo.domain.journal

import com.pisotiyempo.domain.portfolio.ClosedTrade
import java.time.LocalDate
import java.util.Locale

/** Fixed choices so the insights can group entries; free text goes in reason and lesson. */
object JournalTags {
    val SETUPS = listOf("Breakout", "Pullback / dip buy", "Reversal pattern", "Dividend play", "Long-term hold", "News / disclosure", "Tip from someone", "Other")
    val EMOTIONS = listOf("Calm", "Confident", "Excited", "FOMO", "Fearful", "Greedy", "Impatient", "Unsure")
    val MISTAKES = listOf("Chased the price", "No stop loss", "Position too big", "Ignored my plan", "Sold too early", "Held a loser too long", "Averaged down", "Traded on a tip")
}

data class JournalNote(
    val symbol: String,
    val date: LocalDate,
    val setup: String,
    val emotion: String,
    val mistakes: List<String>,
    val rating: Int?,
)

data class GroupStat(val label: String, val trades: Int, val winRate: Double, val avgReturnPct: Double)

data class Insights(
    val closedTrades: Int,
    val winRate: Double?,
    val avgWinPct: Double?,
    val avgLossPct: Double?,
    val avgHoldingDaysWin: Double?,
    val avgHoldingDaysLoss: Double?,
    val topMistakes: List<Pair<String, Int>>,
    val bySetup: List<GroupStat>,
    val byEmotion: List<GroupStat>,
    val observations: List<String>,
)

/**
 * On-device journal analysis. Each closed trade is matched to the latest journal note for that stock
 * written on or before the sell date, which is usually the note for that position's entry.
 */
object JournalInsights {

    fun compute(notes: List<JournalNote>, closed: List<ClosedTrade>): Insights {
        val bySymbol = notes.groupBy { it.symbol }.mapValues { (_, n) -> n.sortedBy { it.date } }
        val matched = closed.map { t -> t to bySymbol[t.symbol]?.lastOrNull { !it.date.isAfter(t.date) } }
        val wins = closed.filter { it.pnl > 0 }
        val losses = closed.filter { it.pnl <= 0 }

        fun group(key: (JournalNote) -> String): List<GroupStat> = matched
            .mapNotNull { (t, n) -> n?.let { key(it) to t } }
            .groupBy({ it.first }, { it.second })
            .map { (label, ts) -> GroupStat(label, ts.size, ts.count { it.pnl > 0 }.toDouble() / ts.size, ts.map { it.pnlPct }.average()) }
            .sortedByDescending { it.trades }

        val topMistakes = notes.flatMap { it.mistakes }.groupingBy { it }.eachCount().entries
            .sortedByDescending { it.value }.take(3).map { it.key to it.value }
        val bySetup = group { it.setup }
        val byEmotion = group { it.emotion }
        val overall = if (closed.isEmpty()) null else wins.size.toDouble() / closed.size

        val observations = buildList {
            if (closed.size < 5) add("Fewer than 5 closed trades so far. Patterns become meaningful after 10 or more.")
            if (overall != null) {
                (bySetup + byEmotion).filter { it.trades >= 3 }.forEach { g ->
                    val diff = g.winRate - overall
                    if (diff <= -0.2) add("Trades tagged \"${g.label}\" won ${pct(g.winRate)} of the time, below your ${pct(overall)} overall.")
                    if (diff >= 0.2) add("Trades tagged \"${g.label}\" won ${pct(g.winRate)} of the time, above your ${pct(overall)} overall. Do more of these.")
                }
            }
            val winDays = wins.map { it.holdingDays.toDouble() }.averageOrNull()
            val lossDays = losses.map { it.holdingDays.toDouble() }.averageOrNull()
            if (winDays != null && lossDays != null && lossDays > winDays * 1.5) {
                add("You hold losers about ${"%.0f".format(Locale.US, lossDays)} days but winners only ${"%.0f".format(Locale.US, winDays)}. Cutting losses sooner usually helps.")
            }
            val avgWin = wins.map { it.pnlPct }.averageOrNull()
            val avgLoss = losses.map { it.pnlPct }.averageOrNull()
            if (avgWin != null && avgLoss != null && kotlin.math.abs(avgLoss) > avgWin) {
                add("Your average loss (${"%.1f".format(Locale.US, avgLoss)}%) is bigger than your average win (+${"%.1f".format(Locale.US, avgWin)}%). Tighter stops or bigger targets would fix the math.")
            }
            topMistakes.firstOrNull()?.let { (m, n) -> if (n >= 2) add("Your most common mistake is \"$m\" ($n times).") }
        }

        return Insights(
            closedTrades = closed.size,
            winRate = overall,
            avgWinPct = wins.map { it.pnlPct }.averageOrNull(),
            avgLossPct = losses.map { it.pnlPct }.averageOrNull(),
            avgHoldingDaysWin = wins.map { it.holdingDays.toDouble() }.averageOrNull(),
            avgHoldingDaysLoss = losses.map { it.holdingDays.toDouble() }.averageOrNull(),
            topMistakes = topMistakes,
            bySetup = bySetup,
            byEmotion = byEmotion,
            observations = observations,
        )
    }

    private fun List<Double>.averageOrNull(): Double? = if (isEmpty()) null else average()

    private fun pct(v: Double) = "${"%.0f".format(Locale.US, v * 100)}%"
}
