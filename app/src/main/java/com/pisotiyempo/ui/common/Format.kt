package com.pisotiyempo.ui.common

import java.util.Locale
import kotlin.math.abs

object Fmt {
    private val PH = Locale.US

    /** ₱1,234.50, with more decimals for sub-peso stocks. */
    fun peso(v: Double?): String {
        if (v == null || v.isNaN()) return "—"
        val pattern = when {
            abs(v) >= 1 -> "%,.2f"
            abs(v) >= 0.01 -> "%.4f"
            else -> "%.6f"
        }
        return (if (v < 0) "-₱" else "₱") + String.format(PH, pattern, abs(v))
    }

    /** ₱1.2B, ₱345.6M, ₱12.3K */
    fun compactPeso(v: Double): String {
        val a = abs(v)
        val sign = if (v < 0) "-" else ""
        return sign + when {
            a >= 1e9 -> "₱" + String.format(PH, "%.2fB", a / 1e9)
            a >= 1e6 -> "₱" + String.format(PH, "%.1fM", a / 1e6)
            a >= 1e3 -> "₱" + String.format(PH, "%.1fK", a / 1e3)
            else -> "₱" + String.format(PH, "%.0f", a)
        }
    }

    fun pct(v: Double?, signed: Boolean = true): String {
        if (v == null || v.isNaN()) return "—"
        return String.format(PH, if (signed) "%+.2f%%" else "%.2f%%", v)
    }

    fun num(v: Double?, decimals: Int = 2): String =
        if (v == null || v.isNaN()) "—" else String.format(PH, "%,.${decimals}f", v)

    fun shares(v: Long): String = String.format(PH, "%,d", v)

    fun signedPeso(v: Double): String = (if (v >= 0) "+" else "") + peso(v)
}
