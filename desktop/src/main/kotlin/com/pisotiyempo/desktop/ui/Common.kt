package com.pisotiyempo.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pisotiyempo.domain.analysis.NarrativeSource
import com.pisotiyempo.domain.model.Stance
import java.util.Locale
import kotlin.math.abs

// Same palette as the phone app.
val Navy = Color(0xFF0B2545)
val Gold = Color(0xFFE0A526)
val Gain = Color(0xFF1B998B)
val Loss = Color(0xFFE84855)
val Neutral = Color(0xFF6B7585)

@Composable
fun PisoDesktopTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = Color(0xFF9EC1F0), secondary = Gold, background = Color(0xFF0E1420), surface = Color(0xFF151C2A), surfaceVariant = Color(0xFF222B3B))
    } else {
        lightColorScheme(primary = Navy, onPrimary = Color.White, secondary = Gold, background = Color(0xFFF2F4F7), surface = Color.White, surfaceVariant = Color(0xFFEEF1F5))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

object Fmt {
    fun peso(v: Double?): String {
        if (v == null || v.isNaN()) return "—"
        val p = when {
            abs(v) >= 1 -> "%,.2f"
            abs(v) >= 0.01 -> "%.4f"
            else -> "%.6f"
        }
        return (if (v < 0) "-₱" else "₱") + String.format(Locale.US, p, abs(v))
    }

    fun signedPeso(v: Double) = (if (v >= 0) "+" else "") + peso(v)

    fun pct(v: Double?, signed: Boolean = true): String =
        if (v == null || v.isNaN()) "—" else String.format(Locale.US, if (signed) "%+.2f%%" else "%.2f%%", v)

    fun num(v: Double?, decimals: Int = 2): String = if (v == null || v.isNaN()) "—" else String.format(Locale.US, "%,.${decimals}f", v)

    fun compact(v: Double): String {
        val a = abs(v)
        val s = if (v < 0) "-" else ""
        return s + when {
            a >= 1e9 -> "₱" + String.format(Locale.US, "%.2fB", a / 1e9)
            a >= 1e6 -> "₱" + String.format(Locale.US, "%.1fM", a / 1e6)
            a >= 1e3 -> "₱" + String.format(Locale.US, "%.1fK", a / 1e3)
            else -> "₱" + String.format(Locale.US, "%.0f", a)
        }
    }

    fun shares(v: Long): String = String.format(Locale.US, "%,d", v)
}

fun changeColor(v: Double?): Color = when {
    v == null || v == 0.0 -> Neutral
    v > 0 -> Gain
    else -> Loss
}

@Composable
fun Section(title: String? = null, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(1.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title != null || trailing != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (title != null) Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    trailing?.invoke()
                }
            }
            content()
        }
    }
}

@Composable
fun Muted(text: String, modifier: Modifier = Modifier) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)

@Composable
fun Pill(text: String, color: Color) {
    Text(
        text, color = color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.background(color.copy(alpha = 0.15f), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

fun stanceColor(s: Stance): Color = when (s) {
    Stance.STRONG_BUY, Stance.BUY -> Gain
    Stance.HOLD -> Neutral
    Stance.REDUCE, Stance.SELL -> Loss
}

@Composable
fun StanceBadge(s: Stance, score: Int? = null) = Pill(if (score != null) "${s.label} · $score" else s.label, stanceColor(s))

@Composable
fun SourceBadge(s: NarrativeSource) = Pill(s.label, if (s == NarrativeSource.RULES) Neutral else Gold)

@Composable
fun Value(label: String, value: String, color: Color = Color.Unspecified, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, color = color)
    }
}

@Composable
fun Bullets(items: List<String>) = items.forEach { Row { Text("•  "); Text(it, style = MaterialTheme.typography.bodyMedium) } }

/** One row in a stock list: symbol, subtitle, price and change, with an optional trailing badge. */
@Composable
fun StockLine(symbol: String, subtitle: String, price: Double?, change: Double?, trailing: @Composable (() -> Unit)? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(symbol, fontWeight = FontWeight.Bold)
            Muted(subtitle, Modifier.fillMaxWidth())
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(Fmt.peso(price))
            Text(Fmt.pct(change), color = changeColor(change), style = MaterialTheme.typography.bodySmall)
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

const val DISCLAIMER = "For education only, not investment advice. Piso Tiyempo is not a licensed broker or investment adviser. Data may be delayed or inaccurate."
