package com.orangexp.feature.today.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.orangexp.feature.today.TodayRoute
import kotlinx.serialization.Serializable

@Serializable
data object TodayDestination

fun NavGraphBuilder.todayScreen() {
    composable<TodayDestination> { TodayRoute() }
}
