package com.example

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class MmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // MMS and WAP push are intentionally ignored for high-performance and lightweight focus.
    }
}
