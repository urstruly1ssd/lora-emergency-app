package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface AppDao {
    // --- Nodes Queries ---
    @Query("SELECT * FROM nodes ORDER BY lastSeen DESC")
    fun getAllNodes(): Flow<List<NodeEntity>>

    @Query("SELECT * FROM nodes WHERE isConnected = 1 LIMIT 1")
    fun getConnectedNode(): Flow<NodeEntity?>

    @Query("SELECT * FROM nodes WHERE isConnected = 1 LIMIT 1")
    suspend fun getConnectedNodeSync(): NodeEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNode(node: NodeEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNodes(nodes: List<NodeEntity>)

    @Query("DELETE FROM nodes WHERE isConnected = 0")
    suspend fun clearDiscoveredNodes()

    @Query("UPDATE nodes SET isConnected = 0")
    suspend fun clearAllConnections()

    @Transaction
    suspend fun connectToNode(nodeName: String) {
        clearAllConnections()
        val existing = getConnectedNodeSync()
        // If it exists, update connection, else make a new connected record
        // (will be fully populated by the scanning screen)
    }

    @Query("UPDATE nodes SET isConnected = 1 WHERE name = :nodeName")
    suspend fun markNodeConnected(nodeName: String)

    // --- Responders Queries ---
    @Query("SELECT * FROM responders ORDER BY type ASC, distanceKm ASC")
    fun getAllResponders(): Flow<List<ResponderEntity>>

    @Query("SELECT * FROM responders WHERE id = :id LIMIT 1")
    fun getResponderById(id: String): Flow<ResponderEntity?>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertResponders(responders: List<ResponderEntity>)

    @Query("DELETE FROM responders WHERE id = :id")
    suspend fun deleteResponderById(id: String)

    @Query("DELETE FROM responders")
    suspend fun clearResponders()

    // --- Messages Queries ---
    @Query("SELECT * FROM messages WHERE responderId = :responderId ORDER BY timestamp ASC")
    fun getMessagesForResponder(responderId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE responderId = :responderId ORDER BY timestamp DESC LIMIT 1")
    fun getLatestMessageForResponder(responderId: String): Flow<MessageEntity?>

    @Query("SELECT * FROM messages WHERE responderId = :responderId AND status = 'SENDING' ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastSendingMessageSync(responderId: String): MessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity): Long

    @Update
    suspend fun updateMessage(message: MessageEntity)

    @Query("SELECT COUNT(*) FROM messages WHERE status != 'DELIVERED'")
    fun getUnsentMessagesCountFlow(): Flow<Int>

    @Query("SELECT * FROM messages WHERE status != 'DELIVERED' ORDER BY timestamp ASC")
    suspend fun getUnsentMessagesSync(): List<MessageEntity>

    @Query("DELETE FROM messages WHERE responderId = :responderId")
    suspend fun deleteMessagesForResponder(responderId: String)
}
