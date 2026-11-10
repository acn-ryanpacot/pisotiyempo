package com.pisotiyempo.domain

import com.pisotiyempo.domain.indicators.Indicators
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IndicatorsTest {

    @Test
    fun smaAveragesTheWindow() {
        val sma = Indicators.sma(doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0), 3)
        assertTrue(sma[1].isNaN())
        assertEquals(2.0, sma[2], 1e-9)
        assertEquals(4.0, sma[4], 1e-9)
    }

    @Test
    fun emaIsSeededWithSmaAndWeightsRecentValues() {
        val ema = Indicators.ema(doubleArrayOf(1.0, 2.0, 3.0, 10.0), 3)
        assertEquals(2.0, ema[2], 1e-9)
        assertEquals(10.0 * 0.5 + 2.0 * 0.5, ema[3], 1e-9)
    }

    @Test
    fun rsiMatchesWilderReference() {
        // Classic reference series from Wilder's "New Concepts in Technical Trading Systems" (via StockCharts).
        val closes = doubleArrayOf(
            44.3389, 44.0902, 44.1497, 43.6124, 44.2779, 44.8264, 45.0955, 45.4245, 45.8433, 46.0826,
            45.8931, 46.0328, 45.6140, 46.2820, 46.2820, 46.0028, 46.0328, 46.4116, 46.2222, 45.6439,
        )
        val rsi = Indicators.rsi(closes, 14)
        assertEquals(70.53, rsi[14], 0.05)
        assertEquals(66.32, rsi[15], 0.05)
        assertEquals(57.97, rsi[19], 0.1)
    }

    @Test
    fun rsiIsHundredWhenPriceOnlyRises() {
        val rsi = Indicators.rsi(DoubleArray(20) { it.toDouble() + 1 }, 14)
        assertEquals(100.0, rsi[19], 1e-9)
    }

    @Test
    fun macdHistogramIsPositiveInAnAcceleratingUptrend() {
        val closes = DoubleArray(60) { 100.0 + it * it * 0.05 }
        val macd = Indicators.macd(closes)
        assertTrue(macd.histogram[59] > 0)
        assertTrue(macd.macd[59] > 0)
    }

    @Test
    fun bollingerBandsCollapseOnFlatPrices() {
        val b = Indicators.bollinger(DoubleArray(30) { 10.0 })
        assertEquals(10.0, b.upper[29], 1e-9)
        assertEquals(10.0, b.lower[29], 1e-9)
    }

    @Test
    fun atrEqualsConstantRange() {
        val bars = TestBars.fromCloses(List(40) { 100.0 }, spread = 0.0).map { it.copy(high = 101.0, low = 99.0) }
        val atr = Indicators.atr(bars, 14)
        assertEquals(2.0, atr[39], 1e-9)
    }

    @Test
    fun adxRisesInAStrongTrend() {
        val bars = TestBars.fromCloses(TestBars.ramp(100.0, 200.0, 80))
        val adx = Indicators.adx(bars)
        assertTrue("ADX ${adx.adx[79]}", adx.adx[79] > 25)
        assertTrue(adx.plusDi[79] > adx.minusDi[79])
    }

    @Test
    fun obvAccumulatesSignedVolume() {
        val bars = TestBars.fromCloses(listOf(10.0, 11.0, 10.5, 10.5, 12.0), volume = 100)
        assertEquals(listOf(0.0, 100.0, 0.0, 0.0, 100.0), Indicators.obv(bars).toList())
    }
}
