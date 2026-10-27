package com.pisotiyempo.ui.nav

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.pisotiyempo.ui.common.MutedText
import com.pisotiyempo.ui.dashboard.DashboardScreen
import com.pisotiyempo.ui.market.MarketScreen
import com.pisotiyempo.ui.portfolio.PortfolioScreen
import com.pisotiyempo.ui.search.SearchScreen
import com.pisotiyempo.ui.settings.SettingsScreen
import com.pisotiyempo.ui.stock.StockDetailScreen
import com.pisotiyempo.ui.tools.ToolsScreen
import com.pisotiyempo.ui.watchlist.WatchlistScreen

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    DASHBOARD("dashboard", "Home", Icons.Filled.Dashboard),
    MARKET("market", "Market", Icons.Filled.ShowChart),
    PORTFOLIO("portfolio", "Portfolio", Icons.Filled.AccountBalanceWallet),
    WATCHLIST("watchlist", "Watchlist", Icons.Filled.Star),
    TOOLS("tools", "Tools", Icons.Filled.Build),
}

@Composable
fun AppNav(openSymbol: String?, onSymbolOpened: () -> Unit) {
    val nav = rememberNavController()
    val openStock: (String) -> Unit = { nav.navigate("stock/$it") }

    // Deep link from an alert notification.
    LaunchedEffect(openSymbol) {
        if (openSymbol != null) {
            nav.navigate("stock/$openSymbol")
            onSymbolOpened()
        }
    }

    NavHost(nav, startDestination = "tabs") {
        composable("tabs") { TabsHost(nav, openStock) }
        composable("stock/{symbol}", arguments = listOf(navArgument("symbol") { type = NavType.StringType })) { entry ->
            StockDetailScreen(symbol = entry.arguments?.getString("symbol").orEmpty(), onBack = { nav.popBackStack() })
        }
        composable("search") { SearchScreen(onOpenStock = openStock, onBack = { nav.popBackStack() }) }
        composable("settings") { SettingsScreen(onBack = { nav.popBackStack() }) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TabsHost(rootNav: NavHostController, openStock: (String) -> Unit) {
    val tabsNav = rememberNavController()
    val backStack by tabsNav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route ?: Tab.DASHBOARD.route

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Piso Tiyempo", fontWeight = FontWeight.Bold)
                        MutedText(Tab.entries.firstOrNull { it.route == current }?.label ?: "")
                    }
                },
                actions = {
                    IconButton(onClick = { rootNav.navigate("search") }) { Icon(Icons.Filled.Search, "Search stocks") }
                    IconButton(onClick = { rootNav.navigate("settings") }) { Icon(Icons.Filled.Settings, "Settings") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = current == tab.route,
                        onClick = {
                            tabsNav.navigate(tab.route) {
                                popUpTo(tabsNav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, null) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(tabsNav, startDestination = Tab.DASHBOARD.route) {
            composable(Tab.DASHBOARD.route) { DashboardScreen(openStock, padding) }
            composable(Tab.MARKET.route) { MarketScreen(openStock, padding) }
            composable(Tab.PORTFOLIO.route) { PortfolioScreen(openStock, padding) }
            composable(Tab.WATCHLIST.route) { WatchlistScreen(openStock, padding) }
            composable(Tab.TOOLS.route) { ToolsScreen(padding) }
        }
    }
}
