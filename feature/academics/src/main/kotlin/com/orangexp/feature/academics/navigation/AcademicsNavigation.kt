package com.orangexp.feature.academics.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.orangexp.feature.academics.AcademicsRoute
import kotlinx.serialization.Serializable

@Serializable
data object AcademicsDestination

fun NavGraphBuilder.academicsScreen() {
    composable<AcademicsDestination> { AcademicsRoute() }
}
