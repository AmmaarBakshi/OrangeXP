package com.orangexp.app.navigation

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.GraphicEq
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
import androidx.compose.runtime.LaunchedEffect
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
import com.orangexp.feature.competitions.navigation.CompetitionsDestination
import com.orangexp.feature.competitions.navigation.competitionsScreen
import com.orangexp.feature.history.navigation.HistoryDestination
import com.orangexp.feature.holstrom.navigation.HolstromDestination
import com.orangexp.feature.holstrom.navigation.holstromScreen
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
    Holstrom(HolstromDestination, HolstromDestination::class, Icons.Filled.GraphicEq, R.string.nav_holstrom),
    History(HistoryDestination, HistoryDestination::class, Icons.Filled.GridView, R.string.nav_history),
    Academics(AcademicsDestination, AcademicsDestination::class, Icons.Filled.School, R.string.nav_academics),
    Compete(CompetitionsDestination, CompetitionsDestination::class, Icons.Filled.EmojiEvents, R.string.nav_compete),
    Settings(SettingsDestination, SettingsDestination::class, Icons.Filled.Tune, R.string.nav_settings),
}

/**
 * @param requestedTab a tab to switch to, e.g. from a Holstrom notification or widget;
 * [onTabShown] is called once it is shown.
 */
@Composable
fun OrangeXpApp(requestedTab: TopLevelDestination? = null, onTabShown: () -> Unit = {}) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val current = backStackEntry?.destination

    LaunchedEffect(requestedTab) {
        val tab = requestedTab ?: return@LaunchedEffect
        navController.navigate(tab.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
        onTabShown()
    }

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
                        label = { Text(stringResource(destination.label), maxLines = 1) },
                        // Six tabs: the label shows under the selected one only, so none are cut off.
                        alwaysShowLabel = false,
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
            holstromScreen()
            historyScreen()
            academicsScreen()
            competitionsScreen()
            settingsScreen()
        }
    }
}
