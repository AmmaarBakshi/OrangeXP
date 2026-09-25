package com.orangexp.feature.holstrom.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.orangexp.feature.holstrom.HolstromRoute
import kotlinx.serialization.Serializable

@Serializable
data object HolstromDestination

fun NavGraphBuilder.holstromScreen() {
    composable<HolstromDestination> { HolstromRoute() }
}
