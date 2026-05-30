package com.example

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.Telephony

class MmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // MMS and WAP push are intentionally ignored for high-performance and lightweight focus.
        // However, to prevent silent data loss, we insert a fallback SMS notification.
        
        try {
            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, "MMS Notice")
                put(Telephony.Sms.BODY, "[MMS/Group Message Received - Open in Default App to View]")
                put(Telephony.Sms.DATE, System.currentTimeMillis())
                put(Telephony.Sms.READ, 0)
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
            }
            context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
