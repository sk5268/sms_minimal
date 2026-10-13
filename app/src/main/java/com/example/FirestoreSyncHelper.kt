package com.example

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

object FirestoreSyncHelper {
    private const val TAG = "FirestoreSyncHelper"
    private const val COLLECTION_SMS = "sms"

    /**
     * Upload an incoming message to Firestore under collection `sms`.
     * Uses anonymous authentication on the phone so sync operates seamlessly in the background.
     */
    fun syncMessage(
        sender: String,
        body: String,
        timestamp: Long,
        isOtp: Boolean = false,
        messageId: Long? = null
    ) {
        val auth = FirebaseAuth.getInstance()
        val currentUser = auth.currentUser

        if (currentUser == null) {
            auth.signInAnonymously()
                .addOnSuccessListener {
                    writeMessageDocument(sender, body, timestamp, isOtp, messageId)
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to authenticate anonymously with Firebase", e)
                }
        } else {
            writeMessageDocument(sender, body, timestamp, isOtp, messageId)
        }
    }

    private fun writeMessageDocument(
        sender: String,
        body: String,
        timestamp: Long,
        isOtp: Boolean,
        messageId: Long?
    ) {
        val db = FirebaseFirestore.getInstance()
        // Deduplicate using sender + timestamp or specific messageId
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
}
