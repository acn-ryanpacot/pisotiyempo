package com.pisotiyempo.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pisotiyempo.ui.common.Bullets

/** First-run screen: the user must acknowledge the app's limits before seeing any buy/sell signals. */
@Composable
fun DisclaimerScreen(onAccept: () -> Unit) {
    var agreed by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Piso Tiyempo", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text("Buy right. Sell right. Tamang tiyempo.", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text("Before you start", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Bullets(
            listOf(
                "Piso Tiyempo is an educational tool. It is not a broker, and not an investment adviser licensed by the Philippine SEC.",
                "Buy and sell ideas come from technical analysis of past prices. They are probabilities, not predictions, and they are often wrong.",
                "Market data comes from third-party and community sources and may be delayed, incomplete or inaccurate.",
                "AI-written analysis (by Anthropic's Claude) can make mistakes. Always check the numbers yourself.",
                "Only invest money you can afford to lose. Consider talking to a licensed professional before making decisions.",
                "Your portfolio and trades are stored only on this phone. Only computed indicators for a stock are sent for AI analysis.",
            ),
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Checkbox(checked = agreed, onCheckedChange = { agreed = it })
            Text("I understand, and I'm responsible for my own investment decisions.", style = MaterialTheme.typography.bodyMedium)
        }
        Button(onClick = onAccept, enabled = agreed, modifier = Modifier.fillMaxWidth()) { Text("Continue") }
    }
}
