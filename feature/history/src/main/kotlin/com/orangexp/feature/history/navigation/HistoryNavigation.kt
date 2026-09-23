package com.orangexp.feature.history.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.orangexp.feature.history.HistoryRoute
import kotlinx.serialization.Serializable

@Serializable
data object HistoryDestination

fun NavGraphBuilder.historyScreen() {
    composable<HistoryDestination> { HistoryRoute() }
}
