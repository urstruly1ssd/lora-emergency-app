package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "nodes")
data class NodeEntity(
    @PrimaryKey val name: String,
    val rssi: Int,
    val distanceEstimate: Double,
    val isConnected: Boolean,
    val lastSeen: Long = System.currentTimeMillis()
)

@Entity(tableName = "responders")
data class ResponderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: String, // "Emergency" or "Local"
    val distanceKm: Double,
    val status: String, // Online status or custom message like "Active near Sector 3"
    val avatarInitial: String
)

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val responderId: String,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isFromUser: Boolean,
    val status: String, // "DELIVERED", "SENDING", "FAILED"
    val attachmentType: String, // "NONE", "GPS", "PHOTO"
    val attachmentData: String? = null,
    val isRead: Boolean = false
)
