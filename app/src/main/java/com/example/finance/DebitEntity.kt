package com.example.finance

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "debits",
    indices = [Index(value = ["smsMessageId"], unique = true)]
)
data class DebitEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val smsMessageId: Long,
    val amountPaise: Long,
    val sender: String,
    val snippet: String,
    val categoryId: Long,
    val occurredAt: Long,
    val createdAt: Long = System.currentTimeMillis(),
    val autoCategorized: Boolean = false
)
