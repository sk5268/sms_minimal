package com.example

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class OtpDeleteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val messageId = intent.getLongExtra("message_id", -1L)
        if (messageId != -1L) {
            val deleteManager = DeleteManager(context)
            deleteManager.softDeleteMessage(messageId)
        }
    }
}
