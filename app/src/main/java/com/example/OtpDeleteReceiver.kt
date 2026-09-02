package com.example

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class OtpDeleteReceiver : BroadcastReceiver() {

    companion object {
        const val EXTRA_MESSAGE_ID = "message_id"
        const val EXTRA_MESSAGE_DATE = "message_date"
        const val EXTRA_ADDRESS = "address"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val messageId = intent.getLongExtra(EXTRA_MESSAGE_ID, -1L)
        val messageDate = intent.getLongExtra(EXTRA_MESSAGE_DATE, 0L)
        if (messageId <= 0L || messageDate <= 0L) return

        // The OTP row may already be gone by now, in which case the provider will
        // have handed this id to a newer message. Only expire the row this alarm
        // was actually scheduled for.
        val appContext = context.applicationContext
        val fingerprint = readSmsRowFingerprint(appContext, messageId) ?: return
        val address = intent.getStringExtra(EXTRA_ADDRESS).orEmpty()
        val expected = MessageTombstone(messageId, messageDate, address, 0L)
        if (!expected.matches(fingerprint.first, fingerprint.second)) return

        DeleteManager(appContext).softDeleteMessage(messageId, messageDate, address)
    }
}
