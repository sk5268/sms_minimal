package com.example

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Telephony
import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object FirestoreSyncHelper {
    private const val TAG = "FirestoreSyncHelper"
    private const val COLLECTION_SMS = "sms"
    private const val SOFT_DELETE_RETENTION_MS = 48L * 60 * 60 * 1000

    private var debouncedSyncJob: Job? = null
    private val syncScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Schedule a background sync with a debounce delay (default 3 seconds).
     * Subsequent calls within the delay period reset the timer.
     */
    fun scheduleDebouncedSync(context: Context, delayMs: Long = 3000L) {
        val appContext = context.applicationContext
        debouncedSyncJob?.cancel()
        debouncedSyncJob = syncScope.launch {
            delay(delayMs)
            Log.d(TAG, "Debounced timer elapsed ($delayMs ms). Executing auto sync and prune...")
            syncAllHistoricMessages(appContext)
        }
    }

    private fun resolveContactName(context: Context, phoneNumber: String): String? {
        if (context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(phoneNumber)
        )
        val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)
        try {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                    if (index != -1) {
                        return cursor.getString(index)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    /**
     * Upload an incoming message to Firestore under collection `sms`.
     */
    fun syncMessage(
        context: Context,
        sender: String,
        body: String,
        timestamp: Long,
        isOtp: Boolean = false,
        messageId: Long? = null,
        isArchived: Boolean = false,
        threadId: Long? = null
    ) {
        if (CloudAuth.signedInUser() == null) {
            // The next bulk sync after the user signs in picks this message up.
            Log.w(TAG, "Not signed in to cloud sync; skipping upload of incoming SMS")
            return
        }

        val contactName = resolveContactName(context, sender) ?: SmsAddress.cleanDisplayName(sender)
        val conversationKey = SmsAddress.conversationKey(sender)
        writeMessageDocument(sender, contactName, conversationKey, threadId, body, timestamp, isOtp, messageId, isArchived)
    }

    private fun writeMessageDocument(
        sender: String,
        contactName: String,
        conversationKey: String,
        threadId: Long?,
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
            "contactName" to contactName,
            "conversationKey" to conversationKey,
            "threadId" to (threadId ?: 0L),
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
     * Query and sync all historic SMS in batches, and prune deleted/orphaned messages from Firestore.
     */
    suspend fun syncAllHistoricMessages(
        context: Context,
        onProgress: (synced: Int, total: Int) -> Unit = { _, _ -> },
        onComplete: (totalSynced: Int, errorMsg: String?) -> Unit = { _, _ -> }
    ) = withContext(Dispatchers.IO) {
        if (CloudAuth.signedInUser() == null) {
            Log.w(TAG, "Not signed in to cloud sync; skipping bulk sync")
            withContext(Dispatchers.Main) { onComplete(0, "Not signed in. Use Sync to Cloud to sign in.") }
            return@withContext
        }

        val archiveManager = ArchiveManager(context)
        val prefArchivedIds = archiveManager.getArchivedThreadIds()
        val prefUnarchivedIds = archiveManager.getUnarchivedThreadIds()

        // Also query system-level archived threads from content://mms-sms/conversations
        val systemArchivedIds = mutableSetOf<Long>()
        try {
            context.contentResolver.query(
                android.net.Uri.parse("content://mms-sms/conversations?simple=true"),
                arrayOf("_id", "archived"),
                null, null, null
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndex("_id")
                val archIndex = cursor.getColumnIndex("archived")
                while (cursor.moveToNext()) {
                    if (idIndex != -1 && archIndex != -1 && cursor.getInt(archIndex) == 1) {
                        systemArchivedIds.add(cursor.getLong(idIndex))
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val deletedManager = DeleteManager(context)
        val deletedIds = deletedManager.getDeletedThreads().keys
        val deletedMessages = deletedManager.getDeletedMessages()
        val contactCache = mutableMapOf<String, String>()

        val messagesToSync = mutableListOf<Map<String, Any?>>()
        val activeDocIds = mutableSetOf<String>()

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

                    // Skip soft-deleted individual messages
                    val tombstone = deletedMessages[msgId]
                    if (tombstone != null && tombstone.matches(timestamp, address)) continue

                    // Only sync inbox and sent
                    if (type != Telephony.Sms.MESSAGE_TYPE_INBOX && type != Telephony.Sms.MESSAGE_TYPE_SENT) {
                        continue
                    }

                    val isArchived = (prefArchivedIds.contains(threadId) || systemArchivedIds.contains(threadId)) && !prefUnarchivedIds.contains(threadId)
                    val isOtp = OtpClassifier.extractOtp(body) != null

                    val contactName = contactCache.getOrPut(address) {
                        resolveContactName(context, address) ?: SmsAddress.cleanDisplayName(address)
                    }
                    val conversationKey = SmsAddress.conversationKey(address)

                    val docId = "msg_$msgId"
                    activeDocIds.add(docId)
                    messagesToSync.add(
                        mapOf(
                            "docId" to docId,
                            "sender" to address,
                            "contactName" to contactName,
                            "conversationKey" to conversationKey,
                            "threadId" to threadId,
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

        var lastError: String? = null
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
                lastError = e.localizedMessage
            }
        }

        // Soft-delete messages that are gone from the phone, restore ones that came
        // back, and purge tombstones older than the retention window. Readers that
        // were offline (e.g. Jarvis) still see the deletion for 48 hours.
        try {
            val snapshot = com.google.android.gms.tasks.Tasks.await(db.collection(COLLECTION_SMS).get())
            val now = System.currentTimeMillis()
            val docsToSoftDelete = mutableListOf<com.google.firebase.firestore.DocumentSnapshot>()
            val docsToRestore = mutableListOf<com.google.firebase.firestore.DocumentReference>()
            val docsToPurge = mutableListOf<com.google.firebase.firestore.DocumentReference>()
            for (doc in snapshot.documents) {
                val docId = doc.id
                val deletedAt = doc.getLong("deletedAt")
                if (deletedAt != null) {
                    when {
                        now - deletedAt >= SOFT_DELETE_RETENTION_MS -> docsToPurge.add(doc.reference)
                        // Restored on the phone; a pending web deletion keeps its flag until the phone applies it.
                        activeDocIds.contains(docId) && doc.getBoolean("deletedFromWeb") != true ->
                            docsToRestore.add(doc.reference)
                    }
                    continue
                }
                val docThreadId = doc.getLong("threadId")

                val isDeletedThread = docThreadId != null && deletedIds.contains(docThreadId)
                val isDeletedMsg = if (docId.startsWith("msg_")) {
                    val msgId = docId.substring(4).toLongOrNull()
                    if (msgId != null) {
                        val tombstone = deletedMessages[msgId]
                        val docTimestamp = doc.getLong("timestamp") ?: 0L
                        val docSender = doc.getString("sender")
                        tombstone != null && tombstone.matches(docTimestamp, docSender)
                    } else false
                } else false
                val isNotActive = !activeDocIds.contains(docId)

                if (isDeletedThread || isDeletedMsg || isNotActive) {
                    docsToSoftDelete.add(doc)
                }
            }

            for (chunk in docsToSoftDelete.chunked(250)) {
                val batch = db.batch()
                for (doc in chunk) {
                    batch.update(doc.reference, "deletedAt", now)
                }
                com.google.android.gms.tasks.Tasks.await(batch.commit())
            }
            for (chunk in docsToRestore.chunked(250)) {
                val batch = db.batch()
                for (ref in chunk) batch.update(ref, "deletedAt", FieldValue.delete())
                com.google.android.gms.tasks.Tasks.await(batch.commit())
            }
            for (chunk in docsToPurge.chunked(250)) {
                val batch = db.batch()
                for (ref in chunk) batch.delete(ref)
                com.google.android.gms.tasks.Tasks.await(batch.commit())
            }
            Log.d(
                TAG,
                "Soft-deleted ${docsToSoftDelete.size}, restored ${docsToRestore.size}, purged ${docsToPurge.size} messages in Firestore"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error reconciling deleted messages in Firestore", e)
        }

        // Tagged debits ride along so Jarvis's finance tables stay current.
        try {
            com.example.finance.FinanceCloudSync.syncAll(context)
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing debits", e)
        }

        withContext(Dispatchers.Main) {
            onComplete(count, lastError)
        }
    }

    /**
     * Checks Firestore for any messages flagged with `deletedFromWeb == true`.
     * Deletes them from the local device Telephony database, updates DeleteManager,
     * and clears the web flag, leaving a `deletedAt` tombstone that the bulk sync
     * purges after the retention window.
     */
    suspend fun syncWebDeletionsToDevice(context: Context): Int = withContext(Dispatchers.IO) {
        if (CloudAuth.signedInUser() == null) return@withContext 0

        val db = FirebaseFirestore.getInstance()
        val deletedCount: Int
        try {
            val querySnapshot = com.google.android.gms.tasks.Tasks.await(
                db.collection(COLLECTION_SMS)
                    .whereEqualTo("deletedFromWeb", true)
                    .get()
            )

            if (querySnapshot.isEmpty) {
                return@withContext 0
            }

            Log.d(TAG, "Found ${querySnapshot.size()} pending web deletions to sync to device")
            val deleteManager = DeleteManager(context)
            val docsToDeleteFromFirestore = mutableListOf<com.google.firebase.firestore.DocumentReference>()

            for (doc in querySnapshot.documents) {
                val docId = doc.id
                docsToDeleteFromFirestore.add(doc.reference)

                val messageId = if (docId.startsWith("msg_")) {
                    docId.substring(4).toLongOrNull()
                } else null

                if (messageId != null && messageId > 0L) {
                    val timestamp = doc.getLong("timestamp") ?: 0L
                    val sender = doc.getString("sender")
                    // Soft-delete message in DeleteManager first so UI updates immediately
                    deleteManager.softDeleteMessage(messageId, timestamp, sender)
                    // Delete from system SMS Telephony provider
                    deleteSmsById(context, messageId)
                    Log.d(TAG, "Deleted SMS $messageId from device triggered by web sync")
                }
            }

            deletedCount = docsToDeleteFromFirestore.size
            if (docsToDeleteFromFirestore.isNotEmpty()) {
                val now = System.currentTimeMillis()
                val chunks = docsToDeleteFromFirestore.chunked(250)
                for (chunk in chunks) {
                    val batch = db.batch()
                    for (ref in chunk) {
                        batch.update(
                            ref,
                            mapOf<String, Any>(
                                "deletedFromWeb" to FieldValue.delete(),
                                "deletedAt" to now
                            )
                        )
                    }
                    com.google.android.gms.tasks.Tasks.await(batch.commit())
                }
                Log.d(TAG, "Applied $deletedCount web deletions on device and tombstoned them in Firestore")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing web deletions to device", e)
            return@withContext 0
        }

        deletedCount
    }
}
