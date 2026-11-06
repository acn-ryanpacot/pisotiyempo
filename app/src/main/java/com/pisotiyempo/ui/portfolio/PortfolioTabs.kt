package com.pisotiyempo.ui.portfolio

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.pisotiyempo.R
import com.pisotiyempo.ui.journal.JournalTab

/** Portfolio tab: the real portfolio, the practice account and the trading journal. */
@Composable
fun PortfolioScreen(onOpenStock: (String) -> Unit, contentPadding: PaddingValues) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding())) {
        TabRow(selectedTabIndex = tab) {
            listOf(
                stringResource(R.string.portfolio_tab_mine),
                stringResource(R.string.portfolio_tab_practice),
                stringResource(R.string.portfolio_tab_journal),
            ).forEachIndexed { i, label ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label) })
            }
        }
        // The tab row already sits below the top bar, so the tabs only need the bottom inset.
        val inner = PaddingValues(bottom = contentPadding.calculateBottomPadding())
        when (tab) {
            0 -> RealPortfolioTab(onOpenStock, inner)
            1 -> PracticeTab(onOpenStock, inner)
            else -> JournalTab(inner)
        }
    }
}
