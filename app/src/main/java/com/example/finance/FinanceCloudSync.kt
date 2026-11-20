package com.example.finance

import android.content.Context
import android.util.Log
import com.example.CloudAuth
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.security.MessageDigest
import kotlin.math.round

/**
 * Mirrors tagged debits to Firestore collection `debits` so Jarvis can build its
 * daily/monthly tables without re-parsing SMS. Owner-only rules apply.
 *
 * The phone is the source of truth: removed debits are soft-deleted (`deletedAt`)
 * so an offline reader still sees the removal; nothing is hard-deleted here.
 */
object FinanceCloudSync {
    private const val TAG = "FinanceCloudSync"
    const val COLLECTION = "debits"
    const val SCHEMA_VERSION = 1

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun docId(messageKey: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(messageKey.toByteArray(Charsets.UTF_8))
        return "d_" + digest.take(12).joinToString("") { "%02x".format(it) }
    }

    fun push(context: Context, debitId: Long) {
        if (CloudAuth.signedInUser() == null) return
        val appContext = context.applicationContext
        scope.launch {
            try {
                val dao = FinanceDatabase.getInstance(appContext).financeDao()
                val debit = dao.getDebitById(debitId) ?: return@launch
                val categories = dao.getCategories().associateBy { it.id }
                FirebaseFirestore.getInstance().collection(COLLECTION)
                    .document(docId(debit.messageKey))
                    .set(toDoc(debit, categories), SetOptions.merge())
            } catch (e: Exception) {
                Log.e(TAG, "push failed", e)
            }
        }
    }

    fun markDeleted(messageKey: String) {
        if (CloudAuth.signedInUser() == null || messageKey.isBlank()) return
        scope.launch {
            try {
                FirebaseFirestore.getInstance().collection(COLLECTION)
                    .document(docId(messageKey))
                    .set(deletion(System.currentTimeMillis()), SetOptions.merge())
            } catch (e: Exception) {
                Log.e(TAG, "markDeleted failed", e)
            }
        }
    }

    fun syncAllInBackground(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            try {
                syncAll(appContext)
            } catch (e: Exception) {
                Log.e(TAG, "syncAll failed", e)
            }
        }
    }

    /** Full upload plus reconciliation; run after the SMS bulk sync and category edits. */
    suspend fun syncAll(context: Context): Int {
        if (CloudAuth.signedInUser() == null) return 0
        val dao = FinanceDatabase.getInstance(context.applicationContext).financeDao()
        val categories = dao.getCategories().associateBy { it.id }
        val debits = dao.allDebits()
        val db = FirebaseFirestore.getInstance()
        val activeIds = HashSet<String>()
        var count = 0
        for (chunk in debits.chunked(250)) {
            val batch = db.batch()
            for (debit in chunk) {
                val id = docId(debit.messageKey)
                activeIds += id
                val doc = toDoc(debit, categories).toMutableMap()
                doc["deletedAt"] = com.google.firebase.firestore.FieldValue.delete()
                batch.set(db.collection(COLLECTION).document(id), doc, SetOptions.merge())
            }
            try {
                Tasks.await(batch.commit())
                count += chunk.size
            } catch (e: Exception) {
                Log.e(TAG, "batch failed", e)
            }
        }
        try {
            val now = System.currentTimeMillis()
            val stale = Tasks.await(db.collection(COLLECTION).get()).documents
                .filter { it.id !in activeIds && it.getLong("deletedAt") == null }
            for (chunk in stale.chunked(250)) {
                val batch = db.batch()
                chunk.forEach { batch.set(it.reference, deletion(now), SetOptions.merge()) }
                Tasks.await(batch.commit())
            }
        } catch (e: Exception) {
            Log.e(TAG, "reconcile failed", e)
        }
        return count
    }

    private fun toDoc(debit: DebitEntity, categories: Map<Long, CategoryEntity>): Map<String, Any?> {
        val category = categories[debit.categoryId]
        val parent = category?.parentCategoryId?.let { categories[it] }
        return mapOf(
            "schema" to SCHEMA_VERSION,
            "amountPaise" to debit.amountPaise,
            "occurredAt" to debit.occurredAt,
            "bankSender" to debit.sender,
            "snippet" to debit.snippet,
            "payeeKey" to debit.payeeKey,
            "payeeName" to debit.payeeName,
            "category" to (category?.name ?: "Uncategorized"),
            "parentCategory" to parent?.name,
            "tagSource" to debit.tagSource,
            "note" to debit.note,
            "manualEntry" to debit.isManualEntry(),
            // ~11 m precision is plenty for "where do I spend" and keeps the doc small.
            "lat" to debit.lat?.let { round4(it) },
            "lng" to debit.lng?.let { round4(it) },
            "locAccuracyM" to debit.locAccuracyM?.let { round(it) },
            "tripKm" to debit.tripKm,
            "litres" to debit.litres,
            "fuelPricePerL" to debit.fuelPricePerL,
            "updatedAt" to debit.updatedAt,
            "syncedAt" to System.currentTimeMillis()
        )
    }

    /** Readers follow `updatedAt`, so a deletion bumps it too. */
    private fun deletion(now: Long): Map<String, Any> = mapOf("deletedAt" to now, "updatedAt" to now)

    private fun round4(v: Double) = round(v * 10_000.0) / 10_000.0
}
