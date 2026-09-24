package com.orangexp.core.database.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Junction
import androidx.room.PrimaryKey
import androidx.room.Relation
import androidx.room.Embedded

@Entity(tableName = "competitions", indices = [Index("eventStartDay")])
data class CompetitionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val prepStartDay: Int,
    val eventStartDay: Int,
    val eventEndDay: Int,
    val prepHours: Double,
    /** 1 (nice to have) ..= 5 (must do). */
    val importance: Int = 3,
    /** Engine `CompetitionStatus` name. */
    val status: String = "PLANNED",
    /** Engine `CompetitionResult` name once completed. */
    val result: String? = null,
    val notes: String = "",
)

@Entity(tableName = "team_members")
data class TeamMemberEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val notes: String = "",
)

@Entity(
    tableName = "competition_members",
    primaryKeys = ["competitionId", "memberId"],
    indices = [Index("memberId")],
    foreignKeys = [
        ForeignKey(entity = CompetitionEntity::class, parentColumns = ["id"], childColumns = ["competitionId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = TeamMemberEntity::class, parentColumns = ["id"], childColumns = ["memberId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class CompetitionMemberEntity(
    val competitionId: Long,
    val memberId: Long,
)

data class CompetitionWithMembers(
    @Embedded val competition: CompetitionEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(CompetitionMemberEntity::class, parentColumn = "competitionId", entityColumn = "memberId"),
    )
    val members: List<TeamMemberEntity>,
)
