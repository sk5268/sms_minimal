package com.example.finance

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.example.CategorizeOverlayActivity
import com.example.NotificationActionReceiver
import com.example.SmsReceiver
import com.example.formatRupees
import java.util.Locale

/**
 * Debit actions on the SMS notification. Android shows three actions at most, so:
 *  - untagged: two one-tap category chips (best suggestions) + "More…"
 *  - tagged fuel: "Trip km" inline reply + "Change"
 *  - tagged other: "Change" + "Don't track"
 * Callers add "Delete message" only if a slot is left.
 */
object DebitNotifier {

    const val KEY_TRIP_KM = "trip_km"

    private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    private const val FLAGS_MUTABLE = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE

    /** Returns how many actions were added. */
    fun addActions(
        context: Context,
        builder: NotificationCompat.Builder,
        result: FinanceRepository.IngestResult,
        amountPaise: Long,
        sender: String,
        snippet: String,
        messageKey: String,
        notifId: Int
    ): Int {
        builder.setSubText(subText(result, amountPaise))
        val more = openSheet(context, result.debitId, amountPaise, sender, snippet, notifId, requestCode = notifId + 4)

        if (!result.isTagged) {
            val chips = result.suggestions.take(2)
            chips.forEachIndexed { i, category ->
                builder.addAction(0, category.name, tagIntent(context, result.debitId, category.id, sender, notifId, notifId + 6 + i))
            }
            builder.addAction(0, if (chips.isEmpty()) "Tag…" else "More…", more)
            return chips.size + 1
        }

        if (result.isFuel) {
            builder.addAction(tripKmAction(context, result.debitId, notifId))
            builder.addAction(0, "Change", more)
            return 2
        }

        builder.addAction(0, "Change", more)
        val dontTrack = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_DONT_TRACK
            putExtra(NotificationActionReceiver.EXTRA_MESSAGE_KEY, messageKey)
            putExtra(NotificationActionReceiver.EXTRA_NOTIF_ID, notifId)
            putExtra(NotificationActionReceiver.EXTRA_SENDER, sender)
        }
        builder.addAction(0, "Don't track", PendingIntent.getBroadcast(context, notifId + 5, dontTrack, FLAGS))
        return 2
    }

    /** After a fuel tag from a chip: ask for the trip reading right in the shade. */
    fun showTripPrompt(context: Context, notifId: Int, debitId: Long, amountPaise: Long, litres: Double?, sender: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val litresText = litres?.let { String.format(Locale.US, " · %.2f L", it) }.orEmpty()
        val builder = NotificationCompat.Builder(context, SmsReceiver.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setContentTitle("Fuel · ${formatRupees(amountPaise)}$litresText")
            .setContentText("Trip km since the last fill? Reset the trip meter after.")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .addAction(tripKmAction(context, debitId, notifId))
        SmsReceiver.clearSenderMessages(context, sender)
        nm.notify(notifId, builder.build())
    }

    private fun subText(result: FinanceRepository.IngestResult, amountPaise: Long): String {
        val amount = formatRupees(amountPaise)
        if (!result.isTagged) return "$amount · untagged"
        val via = when (result.tagSource) {
            TagMemory.SOURCE_PLACE -> " (this spot)"
            TagMemory.SOURCE_PAYEE -> " (same payee)"
            else -> ""
        }
        val litres = if (result.isFuel && result.litres != null) String.format(Locale.US, " · %.2f L", result.litres) else ""
        return "$amount · ${result.categoryName}$via$litres"
    }

    private fun tagIntent(context: Context, debitId: Long, categoryId: Long, sender: String, notifId: Int, requestCode: Int): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_TAG
            putExtra(NotificationActionReceiver.EXTRA_DEBIT_ID, debitId)
            putExtra(NotificationActionReceiver.EXTRA_CATEGORY_ID, categoryId)
            putExtra(NotificationActionReceiver.EXTRA_NOTIF_ID, notifId)
            putExtra(NotificationActionReceiver.EXTRA_SENDER, sender)
        }
        return PendingIntent.getBroadcast(context, requestCode, intent, FLAGS)
    }

    private fun tripKmAction(context: Context, debitId: Long, notifId: Int): NotificationCompat.Action {
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_TRIP_KM
            putExtra(NotificationActionReceiver.EXTRA_DEBIT_ID, debitId)
            putExtra(NotificationActionReceiver.EXTRA_NOTIF_ID, notifId)
        }
        val pending = PendingIntent.getBroadcast(context, notifId + 8, intent, FLAGS_MUTABLE)
        val input = RemoteInput.Builder(KEY_TRIP_KM).setLabel("Trip km").build()
        return NotificationCompat.Action.Builder(0, "Trip km", pending)
            .addRemoteInput(input)
            .setAllowGeneratedReplies(false)
            .build()
    }

    private fun openSheet(
        context: Context,
        debitId: Long,
        amountPaise: Long,
        sender: String,
        snippet: String,
        notifId: Int,
        requestCode: Int
    ): PendingIntent {
        val intent = Intent(context, CategorizeOverlayActivity::class.java).apply {
            putExtra(CategorizeOverlayActivity.EXTRA_DEBIT_ID, debitId)
            putExtra(CategorizeOverlayActivity.EXTRA_AMOUNT_PAISE, amountPaise)
            putExtra(CategorizeOverlayActivity.EXTRA_SENDER, sender)
            putExtra(CategorizeOverlayActivity.EXTRA_SNIPPET, snippet)
            putExtra(CategorizeOverlayActivity.EXTRA_NOTIF_ID, notifId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(context, requestCode, intent, FLAGS)
    }
}
