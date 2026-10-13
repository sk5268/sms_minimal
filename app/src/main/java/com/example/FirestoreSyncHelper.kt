package com.example

import android.content.Context
import android.provider.Telephony
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object FirestoreSyncHelper {
    private const val TAG = "FirestoreSyncHelper"
    private const val COLLECTION_SMS = "sms"

    /**
     * Upload an incoming message to Firestore under collection `sms`.
     */
    fun syncMessage(
        sender: String,
        body: String,
        timestamp: Long,
        isOtp: Boolean = false,
        messageId: Long? = null,
        isArchived: Boolean = false
    ) {
        val auth = FirebaseAuth.getInstance()
        val currentUser = auth.currentUser

        if (currentUser == null) {
            auth.signInAnonymously()
                .addOnSuccessListener {
                    writeMessageDocument(sender, body, timestamp, isOtp, messageId, isArchived)
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to authenticate anonymously with Firebase", e)
                }
        } else {
            writeMessageDocument(sender, body, timestamp, isOtp, messageId, isArchived)
        }
    }

    private fun writeMessageDocument(
        sender: String,
        body: String,
        timestamp: Long,
        isOtp: Boolean,
        messageId: Long?,
        isArchived: Boolean
    ) {
        val db = FirebaseFirestore.getInstance()
        val docId = if (messageId != null && messageId > 0) {
            "msg_$messageId"
        } else {
            "msg_${sender.replace(Regex("[^a-zA-Z0-9]"), "_")}_$timestamp"
        }

        val data = hashMapOf(
            "sender" to sender,
            "body" to body,
            "timestamp" to timestamp,
            "isOtp" to isOtp,
            "isArchived" to isArchived,
            "syncedAt" to System.currentTimeMillis()
        )

        db.collection(COLLECTION_SMS)
            .document(docId)
            .set(data, SetOptions.merge())
            .addOnSuccessListener {
                Log.d(TAG, "Successfully synced message $docId to Firestore")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to sync message $docId to Firestore", e)
            }
    }

    /**
     * Query and sync all historic SMS in batches.
     */
    suspend fun syncAllHistoricMessages(
        context: Context,
        onProgress: (synced: Int, total: Int) -> Unit = { _, _ -> },
        onComplete: (totalSynced: Int) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser == null) {
            try {
                com.google.android.gms.tasks.Tasks.await(auth.signInAnonymously())
            } catch (e: Exception) {
                Log.e(TAG, "Auth failed during bulk sync", e)
                withContext(Dispatchers.Main) { onComplete(0) }
                return@withContext
            }
        }

        val archivedIds = ArchiveManager(context).getArchivedThreadIds()
        val deletedManager = DeleteManager(context)
        val deletedIds = deletedManager.getDeletedThreads().keys

        val messagesToSync = mutableListOf<Map<String, Any?>>()

        val uri = Telephony.Sms.CONTENT_URI
        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.THREAD_ID,
            Telephony.Sms.TYPE
        )

        try {
            context.contentResolver.query(
                uri,
                projection,
                null,
                null,
                "${Telephony.Sms.DATE} DESC"
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(Telephony.Sms._ID)
                val addrIdx = cursor.getColumnIndex(Telephony.Sms.ADDRESS)
                val bodyIdx = cursor.getColumnIndex(Telephony.Sms.BODY)
                val dateIdx = cursor.getColumnIndex(Telephony.Sms.DATE)
                val threadIdx = cursor.getColumnIndex(Telephony.Sms.THREAD_ID)
                val typeIdx = cursor.getColumnIndex(Telephony.Sms.TYPE)

                while (cursor.moveToNext()) {
                    val msgId = if (idIdx != -1) cursor.getLong(idIdx) else 0L
                    val threadId = if (threadIdx != -1) cursor.getLong(threadIdx) else 0L

                    // Skip deleted threads
                    if (deletedIds.contains(threadId)) continue

                    val address = if (addrIdx != -1) cursor.getString(addrIdx) ?: "Unknown" else "Unknown"
                    val body = if (bodyIdx != -1) cursor.getString(bodyIdx) ?: "" else ""
                    val timestamp = if (dateIdx != -1) cursor.getLong(dateIdx) else 0L
                    val type = if (typeIdx != -1) cursor.getInt(typeIdx) else 1

                    // Only sync inbox and sent
                    if (type != Telephony.Sms.MESSAGE_TYPE_INBOX && type != Telephony.Sms.MESSAGE_TYPE_SENT) {
                        continue
                    }

                    val isArchived = archivedIds.contains(threadId)
                    val isOtp = OtpClassifier.extractOtp(body) != null

                    val docId = "msg_$msgId"
                    messagesToSync.add(
                        mapOf(
                            "docId" to docId,
                            "sender" to address,
                            "body" to body,
                            "timestamp" to timestamp,
                            "isOtp" to isOtp,
                            "isArchived" to isArchived,
                            "type" to type,
                            "syncedAt" to System.currentTimeMillis()
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying SMS for bulk sync", e)
        }

        val total = messagesToSync.size
        val db = FirebaseFirestore.getInstance()
        var count = 0

        // Batch upload in chunks of 250 (Firestore limit is 500 per batch)
        val chunks = messagesToSync.chunked(250)
        for (chunk in chunks) {
            val batch = db.batch()
            for (msg in chunk) {
                val docId = msg["docId"] as String
                val data = msg.filterKeys { it != "docId" }
                val ref = db.collection(COLLECTION_SMS).document(docId)
                batch.set(ref, data, SetOptions.merge())
            }
            try {
                com.google.android.gms.tasks.Tasks.await(batch.commit())
                count += chunk.size
                withContext(Dispatchers.Main) {
                    onProgress(count, total)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error committing batch sync", e)
            }
        }

        withContext(Dispatchers.Main) {
            onComplete(count)
        }
    }
}
