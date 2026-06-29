package com.madhav.scanner.app

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.madhav.scanner.feature.benchmark.BenchmarkScreen
import com.madhav.scanner.feature.history.HistoryScreen
import com.madhav.scanner.feature.scan.ScanScreen

private enum class Destination(val route: String, val label: String) {
    SCAN("scan", "Scan"),
    HISTORY("history", "History"),
    BENCHMARK("benchmark", "Benchmark"),
}

/** Three-tab shell: scan (the golden path), history, and the benchmark harness (DESIGN.md
 * §6 — "a first-class feature, not a debug afterthought," hence its own top-level tab).
 */
@Composable
fun AppNavHost() {
    val navController = rememberNavController()

    Scaffold(
        bottomBar = {
            val backStackEntry by navController.currentBackStackEntryAsState()
            val currentDestination = backStackEntry?.destination

            NavigationBar {
                Destination.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = currentDestination?.hierarchy?.any { it.route == destination.route } == true,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { }, // avoids a Material Icons dependency; label alone is enough here
                        label = { Text(destination.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Destination.SCAN.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Destination.SCAN.route) { ScanScreen() }
            composable(Destination.HISTORY.route) { HistoryScreen() }
            composable(Destination.BENCHMARK.route) { BenchmarkScreen() }
        }
    }
}
