package com.orangexp.app.navigation

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.orangexp.app.R
import com.orangexp.feature.academics.navigation.AcademicsDestination
import com.orangexp.feature.academics.navigation.academicsScreen
import com.orangexp.feature.history.navigation.HistoryDestination
import com.orangexp.feature.history.navigation.historyScreen
import com.orangexp.feature.settings.navigation.SettingsDestination
import com.orangexp.feature.settings.navigation.settingsScreen
import com.orangexp.feature.today.navigation.TodayDestination
import com.orangexp.feature.today.navigation.todayScreen
import kotlin.reflect.KClass

/** Top-level tabs. Adding a feature = one entry here plus its `NavGraphBuilder` extension below. */
enum class TopLevelDestination(
    val route: Any,
    val routeClass: KClass<*>,
    val icon: ImageVector,
    @StringRes val label: Int,
) {
    Today(TodayDestination, TodayDestination::class, Icons.Filled.CalendarMonth, R.string.nav_today),
    History(HistoryDestination, HistoryDestination::class, Icons.Filled.GridView, R.string.nav_history),
    Academics(AcademicsDestination, AcademicsDestination::class, Icons.Filled.School, R.string.nav_academics),
    Settings(SettingsDestination, SettingsDestination::class, Icons.Filled.Tune, R.string.nav_settings),
}

@Composable
fun OrangeXpApp() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val current = backStackEntry?.destination

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                TopLevelDestination.entries.forEach { destination ->
                    val selected = current?.hierarchy?.any { it.hasRoute(destination.routeClass) } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(destination.icon, contentDescription = null) },
                        label = { Text(stringResource(destination.label)) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = TodayDestination,
            modifier = Modifier.padding(padding),
        ) {
            todayScreen()
            historyScreen()
            academicsScreen()
            settingsScreen()
        }
    }
}
