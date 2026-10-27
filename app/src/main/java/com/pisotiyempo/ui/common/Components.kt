package com.pisotiyempo.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pisotiyempo.AppContainer
import com.pisotiyempo.PisoApp
import com.pisotiyempo.domain.analysis.NarrativeSource
import com.pisotiyempo.domain.model.Stance
import com.pisotiyempo.ui.theme.Gold
import com.pisotiyempo.ui.theme.LocalMarketColors

@Composable
fun appContainer(): AppContainer = (LocalContext.current.applicationContext as PisoApp).container

@Composable
fun SectionCard(
    title: String? = null,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
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
fun changeColor(v: Double?): Color {
    val c = LocalMarketColors.current
    return when {
        v == null || v == 0.0 -> c.neutral
        v > 0 -> c.gain
        else -> c.loss
    }
}

@Composable
fun ChangeText(pct: Double?, modifier: Modifier = Modifier, bold: Boolean = false) {
    Text(Fmt.pct(pct), color = changeColor(pct), modifier = modifier, fontWeight = if (bold) FontWeight.SemiBold else null, style = MaterialTheme.typography.bodyMedium)
}

@Composable
fun Pill(text: String, background: Color, foreground: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        color = foreground,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.background(background, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
fun StanceBadge(stance: Stance, score: Int? = null) {
    val c = LocalMarketColors.current
    val color = when (stance) {
        Stance.STRONG_BUY, Stance.BUY -> c.gain
        Stance.HOLD -> c.neutral
        Stance.REDUCE, Stance.SELL -> c.loss
    }
    Pill(if (score != null) "${stance.label} · $score" else stance.label, color.copy(alpha = 0.15f), color)
}

@Composable
fun SourceBadge(source: NarrativeSource) {
    val ai = source != NarrativeSource.RULES
    Pill(source.label, if (ai) Gold.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant, if (ai) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun StockRow(
    symbol: String,
    name: String,
    price: Double?,
    changePct: Double?,
    subtitle: String? = null,
    trailing: @Composable (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(symbol, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(subtitle ?: name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(Fmt.peso(price), style = MaterialTheme.typography.bodyMedium)
            ChangeText(changePct)
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

@Composable
fun LabeledValue(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = Color.Unspecified) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = valueColor)
    }
}

@Composable
fun MutedText(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

@Composable
fun Bullets(items: List<String>) {
    items.forEach { Row { Text("•  ", style = MaterialTheme.typography.bodyMedium); Text(it, style = MaterialTheme.typography.bodyMedium) } }
}

const val DISCLAIMER_SHORT =
    "For education only, not investment advice. Piso Tiyempo is not a licensed broker or investment adviser. Data may be delayed or inaccurate."

@Composable
fun DisclaimerFooter() {
    MutedText(DISCLAIMER_SHORT, Modifier.fillMaxWidth().padding(vertical = 12.dp))
}
