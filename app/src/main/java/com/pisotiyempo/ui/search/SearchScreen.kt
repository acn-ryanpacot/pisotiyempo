package com.pisotiyempo.ui.search

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.pisotiyempo.data.local.StockEntity
import com.pisotiyempo.ui.common.MutedText
import com.pisotiyempo.ui.common.StockRow
import com.pisotiyempo.ui.common.appContainer
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(onOpenStock: (String) -> Unit, onBack: () -> Unit) {
    val c = appContainer()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<StockEntity>>(emptyList()) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(query) {
        delay(150)
        results = if (query.isBlank()) emptyList() else c.market.search(query)
    }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = {
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Symbol or company name") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding())) {
            if (query.isNotBlank() && results.isEmpty()) item { MutedText("No matches. Stocks appear after the first sync.") }
            items(results, key = { it.symbol }) { s ->
                StockRow(s.symbol, s.name, s.close, s.changePct, subtitle = "${s.name} · ${s.sector}", onClick = { onOpenStock(s.symbol) })
                HorizontalDivider()
            }
        }
    }
}
