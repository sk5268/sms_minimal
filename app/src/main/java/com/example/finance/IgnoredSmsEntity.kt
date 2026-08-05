package com.example.finance

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ignored_sms")
data class IgnoredSmsEntity(
    @PrimaryKey val smsMessageId: Long
)
