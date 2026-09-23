package com.orangexp.feature.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orangexp.core.common.time.EpochDay
import com.orangexp.core.data.model.DayBreakdown
import com.orangexp.core.data.repository.DayRepository
import com.orangexp.core.designsystem.component.HeatCell
import com.orangexp.core.engine.ffi.PoolTotal
import com.orangexp.core.engine.ffi.Statistics
import com.orangexp.core.engine.ffi.StreakResult
import com.orangexp.core.ui.monthLabels
import com.orangexp.core.ui.toHeatCells
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class HistoryUiState(
    val loading: Boolean = true,
    val lifetimePoints: Long = 0,
    val pools: List<PoolTotal> = emptyList(),
    val cells: List<HeatCell> = emptyList(),
    val weekCount: Int = 0,
    val monthLabels: Map<Int, String> = emptyMap(),
    val activeDaysInRange: Int = 0,
    val streak: StreakResult? = null,
    val statistics: Statistics? = null,
    val selectedDay: EpochDay? = null,
    val selected: DayBreakdown? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HistoryViewModel @Inject constructor(days: DayRepository) : ViewModel() {

    private val selectedDay = MutableStateFlow<EpochDay?>(null)

    val uiState: StateFlow<HistoryUiState> = combine(
        days.history(),
        selectedDay,
        selectedDay.flatMapLatest { day -> if (day == null) flowOf(null) else days.breakdown(day) },
    ) { history, selected, breakdown ->
        HistoryUiState(
            loading = false,
            lifetimePoints = history.statistics.lifetimePoints,
            pools = history.statistics.lifetimeByPool,
            cells = history.heatmap.toHeatCells(),
            weekCount = history.heatmap.weekCount.toInt(),
            monthLabels = history.heatmap.monthLabels(),
            activeDaysInRange = history.heatmap.cells.count { it.points > 0 },
            streak = history.streak,
            statistics = history.statistics,
            selectedDay = selected,
            selected = breakdown,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState())

    fun select(day: EpochDay) {
        selectedDay.value = if (selectedDay.value == day) null else day
    }
}
