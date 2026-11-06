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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pisotiyempo.R
import com.pisotiyempo.ui.common.Bullets

/** First-run screen: the user must acknowledge the app's limits before seeing any buy/sell signals. */
@Composable
fun DisclaimerScreen(onAccept: () -> Unit) {
    var agreed by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.disclaimer_app_name), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.disclaimer_tagline), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.disclaimer_before_you_start), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Bullets(
            listOf(
                stringResource(R.string.disclaimer_point_not_adviser),
                stringResource(R.string.disclaimer_point_probabilities),
                stringResource(R.string.disclaimer_point_data),
                stringResource(R.string.disclaimer_point_ai),
                stringResource(R.string.disclaimer_point_risk),
                stringResource(R.string.disclaimer_point_privacy),
            ),
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Checkbox(checked = agreed, onCheckedChange = { agreed = it })
            Text(stringResource(R.string.disclaimer_agree), style = MaterialTheme.typography.bodyMedium)
        }
        Button(onClick = onAccept, enabled = agreed, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.disclaimer_continue)) }
    }
}
