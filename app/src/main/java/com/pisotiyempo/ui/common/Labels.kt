package com.pisotiyempo.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.pisotiyempo.R
import com.pisotiyempo.data.repo.AlertKind
import com.pisotiyempo.domain.analysis.NarrativeSource
import com.pisotiyempo.domain.analysis.PatternFilter
import com.pisotiyempo.domain.analysis.ScreenPreset
import com.pisotiyempo.domain.analysis.ScreenSort
import com.pisotiyempo.domain.journal.JournalTags
import com.pisotiyempo.domain.model.Direction
import com.pisotiyempo.domain.model.PatternCategory
import com.pisotiyempo.domain.model.Stance

// Localized names for domain values. The domain keeps English identifiers (they're stored in the database
// and sent to the backend); these functions only change what the user sees.

@Composable
fun stanceLabel(s: Stance): String = stringResource(
    when (s) {
        Stance.STRONG_BUY -> R.string.stance_strong_buy
        Stance.BUY -> R.string.stance_buy
        Stance.HOLD -> R.string.stance_hold
        Stance.REDUCE -> R.string.stance_reduce
        Stance.SELL -> R.string.stance_sell
    },
)

@Composable
fun sourceLabel(s: NarrativeSource): String = stringResource(
    when (s) {
        NarrativeSource.CLAUDE_HAIKU -> R.string.source_claude_haiku
        NarrativeSource.CLAUDE_OPUS -> R.string.source_claude_opus
        NarrativeSource.RULES -> R.string.source_rules
    },
)

@Composable
fun categoryLabel(c: PatternCategory): String = stringResource(
    when (c) {
        PatternCategory.CANDLESTICK -> R.string.category_candlestick
        PatternCategory.CHART -> R.string.category_chart
        PatternCategory.INDICATOR -> R.string.category_indicator
        PatternCategory.ADVANCED -> R.string.category_advanced
    },
)

@Composable
fun directionLabel(d: Direction): String = stringResource(
    when (d) {
        Direction.BULLISH -> R.string.direction_bullish
        Direction.BEARISH -> R.string.direction_bearish
        Direction.NEUTRAL -> R.string.direction_neutral
    },
)

/** BULLISH / BEARISH / NEUTRAL market mood from the daily brief. */
@Composable
fun moodLabel(mood: String): String = stringResource(
    when (mood) {
        "BULLISH" -> R.string.mood_bullish
        "BEARISH" -> R.string.mood_bearish
        else -> R.string.mood_neutral
    },
)

@Composable
fun alertKindLabel(k: AlertKind): String = stringResource(
    when (k) {
        AlertKind.PRICE_ABOVE -> R.string.alert_kind_price_above
        AlertKind.PRICE_BELOW -> R.string.alert_kind_price_below
        AlertKind.BUY_SIGNAL -> R.string.alert_kind_buy_signal
        AlertKind.SELL_SIGNAL -> R.string.alert_kind_sell_signal
        AlertKind.PATTERN -> R.string.alert_kind_pattern
    },
)

@Composable
fun presetLabel(p: ScreenPreset): String = stringResource(
    when (p) {
        ScreenPreset.STRONG_UPTREND -> R.string.preset_strong_uptrend
        ScreenPreset.OVERSOLD -> R.string.preset_oversold
        ScreenPreset.BREAKOUTS -> R.string.preset_breakouts
        ScreenPreset.VOLUME_SURGE -> R.string.preset_volume_surge
        ScreenPreset.WEAKEST -> R.string.preset_weakest
    },
)

@Composable
fun presetDescription(p: ScreenPreset): String = stringResource(
    when (p) {
        ScreenPreset.STRONG_UPTREND -> R.string.preset_strong_uptrend_desc
        ScreenPreset.OVERSOLD -> R.string.preset_oversold_desc
        ScreenPreset.BREAKOUTS -> R.string.preset_breakouts_desc
        ScreenPreset.VOLUME_SURGE -> R.string.preset_volume_surge_desc
        ScreenPreset.WEAKEST -> R.string.preset_weakest_desc
    },
)

@Composable
fun sortLabel(s: ScreenSort): String = stringResource(
    when (s) {
        ScreenSort.SCORE -> R.string.sort_score
        ScreenSort.CHANGE -> R.string.sort_change
        ScreenSort.VALUE -> R.string.sort_value
        ScreenSort.RETURN_3M -> R.string.sort_return_3m
        ScreenSort.RSI -> R.string.sort_rsi
    },
)

@Composable
fun patternFilterLabel(f: PatternFilter): String = stringResource(
    when (f) {
        PatternFilter.ANY -> R.string.pattern_filter_any
        PatternFilter.BULLISH -> R.string.pattern_filter_bullish
        PatternFilter.BEARISH -> R.string.pattern_filter_bearish
    },
)

private val JOURNAL_TAG_RES: Map<String, Int> = mapOf(
    JournalTags.SETUPS[0] to R.string.tag_setup_breakout,
    JournalTags.SETUPS[1] to R.string.tag_setup_pullback,
    JournalTags.SETUPS[2] to R.string.tag_setup_reversal,
    JournalTags.SETUPS[3] to R.string.tag_setup_dividend,
    JournalTags.SETUPS[4] to R.string.tag_setup_long_term,
    JournalTags.SETUPS[5] to R.string.tag_setup_news,
    JournalTags.SETUPS[6] to R.string.tag_setup_tip,
    JournalTags.SETUPS[7] to R.string.tag_setup_other,
    JournalTags.EMOTIONS[0] to R.string.tag_emotion_calm,
    JournalTags.EMOTIONS[1] to R.string.tag_emotion_confident,
    JournalTags.EMOTIONS[2] to R.string.tag_emotion_excited,
    JournalTags.EMOTIONS[3] to R.string.tag_emotion_fomo,
    JournalTags.EMOTIONS[4] to R.string.tag_emotion_fearful,
    JournalTags.EMOTIONS[5] to R.string.tag_emotion_greedy,
    JournalTags.EMOTIONS[6] to R.string.tag_emotion_impatient,
    JournalTags.EMOTIONS[7] to R.string.tag_emotion_unsure,
    JournalTags.MISTAKES[0] to R.string.tag_mistake_chased,
    JournalTags.MISTAKES[1] to R.string.tag_mistake_no_stop,
    JournalTags.MISTAKES[2] to R.string.tag_mistake_too_big,
    JournalTags.MISTAKES[3] to R.string.tag_mistake_ignored_plan,
    JournalTags.MISTAKES[4] to R.string.tag_mistake_sold_early,
    JournalTags.MISTAKES[5] to R.string.tag_mistake_held_loser,
    JournalTags.MISTAKES[6] to R.string.tag_mistake_averaged_down,
    JournalTags.MISTAKES[7] to R.string.tag_mistake_tip,
)

/** Journal setups, emotions and mistakes are stored in English; show them in the app's language. */
@Composable
fun journalTagLabel(tag: String): String = JOURNAL_TAG_RES[tag]?.let { stringResource(it) } ?: tag
