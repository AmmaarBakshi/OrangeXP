package com.orangexp.feature.competitions

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orangexp.core.data.repository.CompetitionItem
import com.orangexp.core.data.repository.CompetitionOverview
import com.orangexp.core.data.repository.CompetitionRepository
import com.orangexp.core.data.repository.ConfigRepository
import com.orangexp.core.data.repository.TeamMember
import com.orangexp.core.engine.ffi.CompetitionConfig
import com.orangexp.core.engine.ffi.CompetitionResult
import com.orangexp.core.engine.ffi.CompetitionStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CompetitionsUiState(
    val overview: CompetitionOverview? = null,
    val members: List<TeamMember> = emptyList(),
    val config: CompetitionConfig? = null,
) {
    val upcoming: List<CompetitionItem>
        get() = overview?.competitions.orEmpty().filter { it.isUpcoming(overview!!.today) }

    val past: List<CompetitionItem>
        get() = overview?.competitions.orEmpty().filterNot { it.isUpcoming(overview!!.today) }.sortedByDescending { it.eventEndDay }
}

internal fun CompetitionItem.isUpcoming(today: Int): Boolean =
    status in setOf(CompetitionStatus.PLANNED, CompetitionStatus.REGISTERED) && eventEndDay >= today

@HiltViewModel
class CompetitionsViewModel @Inject constructor(
    private val repository: CompetitionRepository,
    configRepository: ConfigRepository,
) : ViewModel() {

    val uiState: StateFlow<CompetitionsUiState> =
        combine(repository.overview, repository.members, configRepository.config) { overview, members, config ->
            CompetitionsUiState(overview, members, config.competitions)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CompetitionsUiState())

    fun save(item: CompetitionItem, memberIds: List<Long>) = launch { repository.save(item, memberIds) }

    fun delete(id: Long) = launch { repository.delete(id) }

    fun register(item: CompetitionItem) = launch { repository.setOutcome(item.id, CompetitionStatus.REGISTERED, null) }

    /** `null` result means withdrawn. */
    fun recordResult(item: CompetitionItem, result: CompetitionResult?) = launch {
        repository.setOutcome(item.id, if (result == null) CompetitionStatus.WITHDRAWN else CompetitionStatus.COMPLETED, result)
    }

    fun saveMember(name: String) = launch { repository.saveMember(TeamMember(name = name)) }

    fun deleteMember(id: Long) = launch { repository.deleteMember(id) }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                Log.w("CompetitionsViewModel", "Action failed", e)
            }
        }
    }
}
