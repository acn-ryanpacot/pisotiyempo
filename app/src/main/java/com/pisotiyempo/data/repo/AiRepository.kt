package com.pisotiyempo.data.repo

import android.util.Log
import com.pisotiyempo.data.local.AppDatabase
import com.pisotiyempo.data.local.DisclosureEntity
import com.pisotiyempo.data.local.KvEntity
import com.pisotiyempo.data.remote.AiAnalysisDto
import com.pisotiyempo.data.remote.AnalyzeRequest
import com.pisotiyempo.data.remote.ApiException
import com.pisotiyempo.data.remote.ApiJson
import com.pisotiyempo.data.remote.ClosedTradeDto
import com.pisotiyempo.data.remote.JournalEntryDto
import com.pisotiyempo.data.remote.JournalReviewDto
import com.pisotiyempo.data.remote.JournalReviewRequest
import com.pisotiyempo.data.remote.BacktestFeatureDto
import com.pisotiyempo.data.remote.DisclosureRequest
import com.pisotiyempo.data.remote.LevelsDto
import com.pisotiyempo.data.remote.PatternFeatureDto
import com.pisotiyempo.data.remote.PisoApi
import com.pisotiyempo.data.settings.SettingsStore
import com.pisotiyempo.domain.analysis.MarketBrief
import com.pisotiyempo.domain.analysis.MarketStats
import com.pisotiyempo.domain.analysis.Narrative
import com.pisotiyempo.domain.analysis.NarrativeSource
import com.pisotiyempo.domain.analysis.RuleBasedAnalyst
import com.pisotiyempo.domain.model.Stance
import com.pisotiyempo.domain.model.StockAnalysis

/**
 * AI text with an automatic fallback: Claude (via the backend) when it's switched on and reachable,
 * otherwise the on-device rules engine. Every result carries its [NarrativeSource] so the UI can label it.
 */
class AiRepository(
    private val db: AppDatabase,
    private val api: PisoApi,
    private val settings: SettingsStore,
) {
    suspend fun stockNarrative(a: StockAnalysis): Narrative {
        val s = settings.current()
        if (!s.aiEnabled) return RuleBasedAnalyst.stock(a, s.aiLanguage)
        val key = "ai:stock:${a.symbol}:${a.asOf}:${s.aiLanguage}"
        db.kv().get(key)?.let { cached ->
            runCatching { ApiJson.decodeFromString(AiAnalysisDto.serializer(), cached.value) }.getOrNull()?.let { return it.toNarrative() }
        }
        return try {
            val res = api.analyze(a.toRequest().copy(lang = s.aiLanguage))
            db.kv().put(KvEntity(key, ApiJson.encodeToString(AiAnalysisDto.serializer(), res.analysis), System.currentTimeMillis()))
            res.analysis.toNarrative()
        } catch (e: Exception) {
            Log.i(TAG, "AI analysis unavailable for ${a.symbol}, using rules: ${e.message}")
            RuleBasedAnalyst.stock(a, s.aiLanguage)
        }
    }

    /** The day's brief is written once by Claude Opus on the backend; fall back to rules if it isn't for [stats]'s date. */
    suspend fun marketBrief(stats: MarketStats): MarketBrief {
        if (settings.current().aiEnabled) {
            try {
                val res = api.brief(settings.current().aiLanguage)
                if (res.date == stats.date) {
                    val b = res.brief
                    return MarketBrief(res.date, b.headline, b.mood, b.overview, b.highlights, b.risks, b.watchTomorrow, NarrativeSource.CLAUDE_OPUS)
                }
            } catch (e: Exception) {
                Log.i(TAG, "AI brief unavailable, using rules: ${e.message}")
            }
        }
        return RuleBasedAnalyst.market(stats, settings.current().aiLanguage)
    }

    /** Summarizes a disclosure with Claude and stores the result. Returns false when AI is unavailable. */
    suspend fun summarizeDisclosure(d: DisclosureEntity): Boolean {
        if (!settings.current().aiEnabled) return false
        return try {
            val res = api.summarizeDisclosure(DisclosureRequest(d.id, d.symbol, settings.current().aiLanguage)).summary
            db.disclosures().setSummary(d.id, res.summary, res.impact, res.keyPoints.joinToString("\n"))
            true
        } catch (e: Exception) {
            Log.i(TAG, "disclosure summary failed: ${e.message}")
            false
        }
    }

    /**
     * Claude coaching on the user's journal and closed trades. This sends the user's own notes to the backend
     * and on to Anthropic, so the UI only calls it after the user taps the button that says so.
     */
    suspend fun journalReview(entries: List<JournalEntryDto>, closed: List<ClosedTradeDto>): Result<JournalReviewDto> {
        if (!settings.current().aiEnabled) return Result.failure(IllegalStateException("AI analysis is turned off in Settings."))
        return runCatching {
            api.journalReview(JournalReviewRequest(entries.take(40), closed.takeLast(60), settings.current().aiLanguage)).review
        }.recoverCatching { e ->
            throw IllegalStateException(
                when ((e as? ApiException)?.code) {
                    "rate_limited" -> "You can get one AI review a day. Try again tomorrow."
                    "premium_required" -> "AI coaching is part of Premium."
                    else -> "AI coaching isn't available right now."
                },
            )
        }
    }

    companion object {
        private const val TAG = "AiRepository"
    }
}

private fun AiAnalysisDto.toNarrative() = Narrative(
    headline = headline,
    oneLiner = oneLiner,
    summary = summary,
    bullCase = bullCase,
    bearCase = bearCase,
    keyLevels = keyLevels,
    riskNote = riskNote,
    stance = runCatching { Stance.valueOf(stance) }.getOrDefault(Stance.HOLD),
    confidence = confidence,
    source = NarrativeSource.CLAUDE_HAIKU,
)

private fun StockAnalysis.toRequest() = AnalyzeRequest(
    symbol = symbol,
    name = name,
    asOf = asOf.toString(),
    close = close,
    changePct = changePct,
    score = score,
    indicators = indicators,
    patterns = patterns.take(12).map { PatternFeatureDto(it.name, it.direction.name, it.confidence, it.detail.take(200)) },
    levels = LevelsDto(supports.map { it.price }, resistances.map { it.price }),
    backtest = backtest.take(12).map { BacktestFeatureDto(it.signalName, it.winRate, it.samples, it.avgReturnPct) },
    signals = signals.take(15),
)
