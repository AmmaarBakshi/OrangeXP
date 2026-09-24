package com.orangexp.feature.competitions.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.orangexp.feature.competitions.CompetitionsRoute
import kotlinx.serialization.Serializable

@Serializable
data object CompetitionsDestination

fun NavGraphBuilder.competitionsScreen() {
    composable<CompetitionsDestination> { CompetitionsRoute() }
}
