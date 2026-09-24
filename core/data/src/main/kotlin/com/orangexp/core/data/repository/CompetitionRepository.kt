package com.orangexp.core.data.repository

import com.orangexp.core.common.coroutines.Dispatcher
import com.orangexp.core.common.coroutines.OxDispatchers
import com.orangexp.core.common.time.EpochDay
import com.orangexp.core.common.time.TimeSource
import com.orangexp.core.common.time.today
import com.orangexp.core.data.model.enumValueOrDefault
import com.orangexp.core.database.dao.CompetitionDao
import com.orangexp.core.database.model.CompetitionEntity
import com.orangexp.core.database.model.CompetitionWithMembers
import com.orangexp.core.database.model.TeamMemberEntity
import com.orangexp.core.engine.OrangeEngine
import com.orangexp.core.engine.ffi.Competition
import com.orangexp.core.engine.ffi.CompetitionAssessment
import com.orangexp.core.engine.ffi.CompetitionPlan
import com.orangexp.core.engine.ffi.CompetitionResult
import com.orangexp.core.engine.ffi.CompetitionStatus
import com.orangexp.core.engine.ffi.TeammateStats
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

data class TeamMember(val id: Long = 0, val name: String, val notes: String = "")

data class CompetitionItem(
    val id: Long = 0,
    val name: String,
    val prepStartDay: EpochDay,
    val eventStartDay: EpochDay,
    val eventEndDay: EpochDay,
    val prepHours: Double,
    val importance: Int = 3,
    val status: CompetitionStatus = CompetitionStatus.PLANNED,
    val result: CompetitionResult? = null,
    val notes: String = "",
    val members: List<TeamMember> = emptyList(),
)

data class RankedTeammate(val member: TeamMember, val stats: TeammateStats)

data class CompetitionOverview(
    val today: EpochDay,
    val competitions: List<CompetitionItem>,
    val assessments: Map<Long, CompetitionAssessment>,
    val plan: CompetitionPlan,
    /** Teammates who have competed with you, strongest first; newcomers after. */
    val teammates: List<RankedTeammate>,
    val newMembers: List<TeamMember>,
)

interface CompetitionRepository {
    val overview: Flow<CompetitionOverview>
    val members: Flow<List<TeamMember>>

    suspend fun save(item: CompetitionItem, memberIds: List<Long>): Long
    suspend fun delete(id: Long)
    suspend fun setOutcome(id: Long, status: CompetitionStatus, result: CompetitionResult?)

    suspend fun saveMember(member: TeamMember): Long
    suspend fun deleteMember(id: Long)
}

@Singleton
internal class OfflineCompetitionRepository @Inject constructor(
    private val dao: CompetitionDao,
    private val configRepository: ConfigRepository,
    private val engine: OrangeEngine,
    private val time: TimeSource,
    @Dispatcher(OxDispatchers.Default) private val dispatcher: CoroutineDispatcher,
) : CompetitionRepository {

    override val members: Flow<List<TeamMember>> =
        dao.observeMembers().map { list -> list.map { TeamMember(it.id, it.name, it.notes) } }

    override val overview: Flow<CompetitionOverview> =
        combine(dao.observeAll(), members, configRepository.config) { rows, allMembers, config ->
            val today = time.today()
            val items = rows.map { it.toItem() }
            val engineList = items.map { it.toEngine() }
            val plan = engine.planCompetitions(engineList, today, config.competitions)
            val stats = engine.rankTeammates(engineList, today, config.competitions)
            val byId = allMembers.associateBy { it.id.toString() }
            val ranked = stats.mapNotNull { s -> byId[s.memberId]?.let { RankedTeammate(it, s) } }
            val rankedIds = ranked.map { it.member.id }.toSet()
            CompetitionOverview(
                today = today,
                competitions = items,
                assessments = plan.assessments.associateBy { it.id.toLong() },
                plan = plan,
                teammates = ranked,
                newMembers = allMembers.filter { it.id !in rankedIds },
            )
        }.flowOn(dispatcher)

    override suspend fun save(item: CompetitionItem, memberIds: List<Long>): Long {
        require(item.name.isNotBlank()) { "A competition needs a name" }
        require(item.eventEndDay >= item.eventStartDay) { "The event must end on or after its start" }
        return dao.save(
            CompetitionEntity(
                id = item.id,
                name = item.name.trim(),
                prepStartDay = minOf(item.prepStartDay, item.eventStartDay),
                eventStartDay = item.eventStartDay,
                eventEndDay = item.eventEndDay,
                prepHours = item.prepHours.coerceAtLeast(0.0),
                importance = item.importance.coerceIn(1, 5),
                status = item.status.name,
                result = item.result?.name,
                notes = item.notes.trim(),
            ),
            memberIds,
        )
    }

    override suspend fun delete(id: Long) = dao.delete(id)

    override suspend fun setOutcome(id: Long, status: CompetitionStatus, result: CompetitionResult?) {
        val existing = dao.get(id) ?: return
        dao.upsert(existing.copy(status = status.name, result = result?.name))
    }

    override suspend fun saveMember(member: TeamMember): Long {
        require(member.name.isNotBlank()) { "A team member needs a name" }
        val saved = dao.upsertMember(TeamMemberEntity(member.id, member.name.trim(), member.notes.trim()))
        return if (member.id == 0L) saved else member.id
    }

    override suspend fun deleteMember(id: Long) = dao.deleteMember(id)
}

private fun CompetitionWithMembers.toItem() = CompetitionItem(
    id = competition.id,
    name = competition.name,
    prepStartDay = competition.prepStartDay,
    eventStartDay = competition.eventStartDay,
    eventEndDay = competition.eventEndDay,
    prepHours = competition.prepHours,
    importance = competition.importance,
    status = enumValueOrDefault(competition.status, CompetitionStatus.PLANNED),
    result = competition.result?.let { enumValueOrDefault(it, CompetitionResult.PARTICIPATED) },
    notes = competition.notes,
    members = members.map { TeamMember(it.id, it.name, it.notes) }.sortedBy { it.name },
)

private fun CompetitionItem.toEngine() = Competition(
    id = id.toString(),
    name = name,
    prepStartDay = prepStartDay,
    eventStartDay = eventStartDay,
    eventEndDay = eventEndDay,
    prepHours = prepHours,
    importance = importance.toUByte(),
    status = status,
    result = result,
    memberIds = members.map { it.id.toString() },
)
