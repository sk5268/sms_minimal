package com.example.finance

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sender_category_memory")
data class SenderCategoryMemoryEntity(
    @PrimaryKey val senderKey: String,
    val categoryId: Long,
    val updatedAt: Long = System.currentTimeMillis()
)
