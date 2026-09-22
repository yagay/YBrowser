package com.yagay.ybrowser.ai.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction

@Entity(tableName = "conversations")
data class StoredConversationEntity(
    @PrimaryKey val sessionKey: String,
    val providerId: String,
    val windowId: String,
    val updatedAt: Long,
)

@Entity(
    tableName = "conversation_messages",
    primaryKeys = ["sessionKey", "messageId"],
    indices = [
        Index(value = ["sessionKey"]),
        Index(value = ["sessionKey", "sequence"], unique = true),
    ],
)
data class StoredMessageEntity(
    val sessionKey: String,
    val messageId: String,
    val sequence: Int,
    val role: String,
    val text: String,
    val timestamp: Long,
    val attachmentsJson: String,
)

@Dao
abstract class ConversationDao {
    @Query(
        "SELECT * FROM conversation_messages " +
            "WHERE sessionKey = :sessionKey ORDER BY sequence ASC"
    )
    abstract fun loadMessages(sessionKey: String): List<StoredMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun upsertConversation(entity: StoredConversationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun upsertMessages(messages: List<StoredMessageEntity>)

    @Query(
        "DELETE FROM conversation_messages " +
            "WHERE sessionKey = :sessionKey AND sequence >= :fromSequence"
    )
    abstract fun deleteTail(sessionKey: String, fromSequence: Int)

    @Query("DELETE FROM conversation_messages WHERE sessionKey = :sessionKey")
    abstract fun deleteMessages(sessionKey: String)

    @Query("DELETE FROM conversations WHERE sessionKey = :sessionKey")
    abstract fun deleteConversation(sessionKey: String)

    @Transaction
    open fun saveIncremental(
        conversation: StoredConversationEntity,
        incoming: List<StoredMessageEntity>,
    ) {
        upsertConversation(conversation)
        val previous = loadMessages(conversation.sessionKey)

        var commonPrefix = 0
        val limit = minOf(previous.size, incoming.size)
        while (commonPrefix < limit) {
            val old = previous[commonPrefix]
            val next = incoming[commonPrefix]
            val same =
                old.messageId == next.messageId &&
                    old.role == next.role &&
                    old.text == next.text &&
                    old.attachmentsJson == next.attachmentsJson
            if (!same) break
            commonPrefix++
        }

        if (commonPrefix < previous.size) {
            deleteTail(conversation.sessionKey, commonPrefix)
        }
        if (commonPrefix < incoming.size) {
            upsertMessages(incoming.drop(commonPrefix))
        }
    }

    @Transaction
    open fun clearSession(sessionKey: String) {
        deleteMessages(sessionKey)
        deleteConversation(sessionKey)
    }
}

@Database(
    entities = [
        StoredConversationEntity::class,
        StoredMessageEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class ConversationDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao

    companion object {
        @Volatile
        private var instance: ConversationDatabase? = null

        fun get(context: Context): ConversationDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ConversationDatabase::class.java,
                    "aihub_conversations.db",
                ).build().also { instance = it }
            }
    }
}
