package com.example.finance

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val colorArgb: Int,
    val sortOrder: Int,
    val isSystem: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)
