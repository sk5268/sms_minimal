package com.example.finance

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "debits",
    indices = [
        Index(value = ["messageKey"], unique = true),
        Index(value = ["payeeKey"]),
        Index(value = ["lat"])
    ]
)
data class DebitEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val messageKey: String,
    val amountPaise: Long,
    /** SMS sender, i.e. the bank (or the payee for manual entries). */
    val sender: String,
    val snippet: String,
    val categoryId: Long,
    val occurredAt: Long,
    val createdAt: Long = System.currentTimeMillis(),
    val autoCategorized: Boolean = false,
    val note: String? = null,
    /** Who was paid: `V:<upi id>` or `N:<merchant name>`. Tag memory matches on this. */
    val payeeKey: String? = null,
    val payeeName: String? = null,
    /** Where the phone was when the payment SMS arrived (or when it was tagged). */
    val lat: Double? = null,
    val lng: Double? = null,
    val locAccuracyM: Double? = null,
    /** manual | payee | place | none | legacy_auto. Only manual tags teach the memory. */
    val tagSource: String = TagMemory.SOURCE_NONE,
    /** Fuel only: trip meter reading typed at this refuel (km since the previous one). */
    val tripKm: Double? = null,
    val litres: Double? = null,
    val fuelPricePerL: Double? = null,
    val updatedAt: Long = System.currentTimeMillis()
)

fun DebitEntity.isManualEntry(): Boolean = messageKey.startsWith("manual:")

fun DebitEntity.displayPayee(): String = payeeName?.takeIf { it.isNotBlank() } ?: sender
