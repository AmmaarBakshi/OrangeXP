package com.orangexp.core.data.repository

import com.orangexp.core.common.time.TimeSource
import com.orangexp.core.common.time.nowMs
import com.orangexp.core.data.model.ChatMessage
import com.orangexp.core.data.model.Speaker
import com.orangexp.core.data.model.enumValueOrDefault
import com.orangexp.core.database.dao.AssistantMessageDao
import com.orangexp.core.database.model.AssistantMessageEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** The conversation with Holstrom, kept on the device. */
interface ConversationRepository {
    val messages: Flow<List<ChatMessage>>

    /** The last [count] messages, oldest first. */
    suspend fun recent(count: Int): List<ChatMessage>

    suspend fun add(speaker: Speaker, text: String): Long

    /** Replaces the text of a message that is still being written (streamed answers). */
    suspend fun update(id: Long, text: String)

    suspend fun clear()
}

@Singleton
internal class OfflineConversationRepository @Inject constructor(
    private val dao: AssistantMessageDao,
    private val time: TimeSource,
) : ConversationRepository {

    override val messages: Flow<List<ChatMessage>> = dao.observeRecent(HISTORY).map { rows -> rows.map { it.toModel() } }

    override suspend fun recent(count: Int): List<ChatMessage> = dao.recent(count).map { it.toModel() }

    override suspend fun add(speaker: Speaker, text: String): Long =
        dao.insert(AssistantMessageEntity(role = speaker.name, text = text, timestampMs = time.nowMs()))

    override suspend fun update(id: Long, text: String) = dao.updateText(id, text)

    override suspend fun clear() = dao.clear()

    private fun AssistantMessageEntity.toModel() =
        ChatMessage(id, enumValueOrDefault(role, Speaker.HOLSTROM), text, timestampMs)

    private companion object {
        const val HISTORY = 200
    }
}
