package com.orangexp.feature.settings.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.orangexp.feature.settings.SettingsRoute
import kotlinx.serialization.Serializable

@Serializable
data object SettingsDestination

fun NavGraphBuilder.settingsScreen() {
    composable<SettingsDestination> { SettingsRoute() }
}
