package com.example

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.app.role.RoleManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.PhoneNumberUtils
import android.telephony.SmsManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.withLink
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import kotlin.math.roundToInt
import com.example.ui.theme.*
import com.example.finance.FinanceScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.ui.platform.LocalDensity

// Models Representing SMS entities with memory conservation in mind
data class SmsMessage(
    val id: Long,
    val threadId: Long,
    val address: String,
    val body: String,
    val timestamp: Long,
    val read: Int,
    val type: Int // 1 = Inbox, 2 = Sent
)

data class SmsThread(
    val threadId: Long,
    val address: String,
    val name: String,
    val snippet: String,
    val timestamp: Long,
    val unreadCount: Int,
    val isArchived: Boolean
)

data class ContactRecipient(
    val name: String?,
    val number: String
)

// Ultra-light SharedPreferences Archive Manager
class ArchiveManager(context: Context) {
    private val prefs = context.getSharedPreferences("sms_archive_prefs", Context.MODE_PRIVATE)

    fun getArchivedThreadIds(): Set<Long> {
        return prefs.getStringSet("archived_ids", emptySet())
            ?.mapNotNull { it.toLongOrNull() }
            ?.toSet() ?: emptySet()
    }

    fun getUnarchivedThreadIds(): Set<Long> {
        return prefs.getStringSet("unarchived_ids", emptySet())
            ?.mapNotNull { it.toLongOrNull() }
            ?.toSet() ?: emptySet()
    }

    fun archiveThread(context: Context, threadId: Long) {
        val archived = getArchivedThreadIds().toMutableSet()
        archived.add(threadId)
        prefs.edit().putStringSet("archived_ids", archived.map { it.toString() }.toSet()).apply()

        val unarchived = getUnarchivedThreadIds().toMutableSet()
        if (unarchived.remove(threadId)) {
            prefs.edit().putStringSet("unarchived_ids", unarchived.map { it.toString() }.toSet()).apply()
        }

        updateSystemArchiveState(context, threadId, 1)
    }

    fun unarchiveThread(context: Context, threadId: Long) {
        val archived = getArchivedThreadIds().toMutableSet()
        archived.remove(threadId)
        prefs.edit().putStringSet("archived_ids", archived.map { it.toString() }.toSet()).apply()

        val unarchived = getUnarchivedThreadIds().toMutableSet()
        unarchived.add(threadId)
        prefs.edit().putStringSet("unarchived_ids", unarchived.map { it.toString() }.toSet()).apply()

        updateSystemArchiveState(context, threadId, 0)
    }

    private fun updateSystemArchiveState(context: Context, threadId: Long, isArchived: Int) {
        try {
            val values = ContentValues().apply { put("archived", isArchived) }
            context.contentResolver.update(
                Uri.parse("content://mms-sms/conversations/$threadId"),
                values,
                null,
                null
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun shouldSkipThreadDeleteWarning(): Boolean {
        return prefs.getBoolean("skip_delete_warning", false)
    }

    fun setSkipThreadDeleteWarning(skip: Boolean) {
        prefs.edit().putBoolean("skip_delete_warning", skip).apply()
    }

    fun shouldSkipMessageDeleteWarning(): Boolean {
        return prefs.getBoolean("skip_message_delete_warning", false)
    }

    fun setSkipMessageDeleteWarning(skip: Boolean) {
        prefs.edit().putBoolean("skip_message_delete_warning", skip).apply()
    }
}

fun restoreIncomingConversation(context: Context, threadId: Long) {
    if (threadId <= 0L) return
    DeleteManager(context).restoreThread(threadId)
    ArchiveManager(context).unarchiveThread(context, threadId)
}

// Ultra-light SharedPreferences Delete Manager for Soft Deletion (6 hours)
class DeleteManager(context: Context) {
    private val prefs = context.getSharedPreferences("sms_delete_prefs", Context.MODE_PRIVATE)
    private val msgPrefs = context.getSharedPreferences("sms_delete_msg_prefs", Context.MODE_PRIVATE)

    fun getDeletedThreads(): Map<Long, Long> {
        return prefs.all.mapNotNull { (key, value) ->
            val threadId = key.toLongOrNull()
            val timestamp = value as? Long
            if (threadId != null && timestamp != null) threadId to timestamp else null
        }.toMap()
    }

    fun softDeleteThread(threadId: Long) {
        prefs.edit().putLong(threadId.toString(), System.currentTimeMillis()).apply()
    }

    fun restoreThread(threadId: Long) {
        prefs.edit().remove(threadId.toString()).apply()
    }

    fun getDeletedMessages(): Map<Long, Long> {
        return msgPrefs.all.mapNotNull { (key, value) ->
            val messageId = key.toLongOrNull()
            val timestamp = value as? Long
            if (messageId != null && timestamp != null) messageId to timestamp else null
        }.toMap()
    }

    fun softDeleteMessage(messageId: Long) {
        msgPrefs.edit().putLong(messageId.toString(), System.currentTimeMillis()).apply()
    }

    fun restoreMessage(messageId: Long) {
        msgPrefs.edit().remove(messageId.toString()).apply()
    }

    fun registerChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
        msgPrefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
        msgPrefs.unregisterOnSharedPreferenceChangeListener(listener)
    }
    
    fun cleanUpExpired(context: Context) {
        val now = System.currentTimeMillis()
        val expired = getDeletedThreads().filter { (now - it.value) > 6 * 60 * 60 * 1000L }
        if (expired.isNotEmpty()) {
            val editor = prefs.edit()
            for ((id, _) in expired) {
                // Permanently delete from Android provider
                try {
                    context.contentResolver.delete(
                        Uri.parse("content://sms/conversations/$id"),
                        null,
                        null
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                editor.remove(id.toString())
            }
            editor.apply()
        }

        val expiredMsgs = getDeletedMessages().filter { (now - it.value) > 6 * 60 * 60 * 1000L }
        if (expiredMsgs.isNotEmpty()) {
            val editor = msgPrefs.edit()
            for ((id, _) in expiredMsgs) {
                try {
                    context.contentResolver.delete(
                        Uri.parse("content://sms/$id"),
                        null,
                        null
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                editor.remove(id.toString())
            }
            editor.apply()
        }
    }
}

// Ultra-light SharedPreferences Star Manager
class StarManager(context: Context) {
    private val prefs = context.getSharedPreferences("sms_star_prefs", Context.MODE_PRIVATE)

    fun getStarredMessageIds(): Set<Long> {
        return prefs.all.keys.mapNotNull { it.toLongOrNull() }.toSet()
    }

    fun toggleStar(messageId: Long) {
        val editor = prefs.edit()
        if (prefs.contains(messageId.toString())) {
            editor.remove(messageId.toString())
        } else {
            editor.putLong(messageId.toString(), System.currentTimeMillis())
        }
        editor.apply()
    }

    fun registerChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }
}

class MainActivity : ComponentActivity() {
    private val targetSenderState = mutableStateOf<String?>(null)
    private val targetThreadIdState = mutableLongStateOf(-1L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        applyNotificationIntent(intent)

        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SMSAppScreen(
                        targetSender = targetSenderState.value,
                        targetThreadId = targetThreadIdState.longValue,
                        onTargetSenderHandled = {
                            targetSenderState.value = null
                            targetThreadIdState.longValue = -1L
                            intent?.removeExtra(SmsReceiver.EXTRA_SENDER_NUMBER)
                            intent?.removeExtra(SmsReceiver.EXTRA_THREAD_ID)
                            intent?.removeExtra(SmsReceiver.EXTRA_MESSAGE_ID)
                        }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyNotificationIntent(intent)
    }

    private fun applyNotificationIntent(intent: Intent?) {
        val senderFromNotif = intent?.getStringExtra(SmsReceiver.EXTRA_SENDER_NUMBER)
        val threadFromNotif = intent?.getLongExtra(SmsReceiver.EXTRA_THREAD_ID, -1L) ?: -1L
        targetSenderState.value = senderFromNotif
        targetThreadIdState.longValue = threadFromNotif
        if (!senderFromNotif.isNullOrEmpty()) {
            SmsReceiver.dismissSenderNotification(this, senderFromNotif)
        }
    }
}

private fun permanentlyDeleteThread(context: Context, threadId: Long): Boolean {
    return try {
        val deletedCount = context.contentResolver.delete(
            Uri.parse("content://sms/conversations/$threadId"),
            null,
            null
        )
        deletedCount > 0
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }
}

private fun deleteSmsMessages(context: Context, ids: List<Long>): Boolean {
    var deletedAny = false
    try {
        for (id in ids) {
            val rows = context.contentResolver.delete(
                Uri.parse("content://sms/$id"),
                null,
                null
            )
            if (rows > 0) deletedAny = true
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return deletedAny
}

@Composable
fun SMSAppScreen(targetSender: String?, targetThreadId: Long, onTargetSenderHandled: () -> Unit) {
    val context = LocalContext.current
    val archiveManager = remember { ArchiveManager(context) }
    val deleteManager = remember { DeleteManager(context) }
    val starManager = remember { StarManager(context) }

    // Dynamic state management
    var archivedIds by remember { mutableStateOf(archiveManager.getArchivedThreadIds()) }
    var unarchivedIds by remember { mutableStateOf(archiveManager.getUnarchivedThreadIds()) }
    var deletedIds by remember { mutableStateOf(deleteManager.getDeletedThreads().keys) }
    var deletedMessageIds by remember { mutableStateOf(deleteManager.getDeletedMessages().keys) }
    var starredIds by remember { mutableStateOf(starManager.getStarredMessageIds()) }

    DisposableEffect(deleteManager) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            deletedIds = deleteManager.getDeletedThreads().keys
            deletedMessageIds = deleteManager.getDeletedMessages().keys
        }
        deleteManager.registerChangeListener(listener)
        onDispose {
            deleteManager.unregisterChangeListener(listener)
        }
    }

    DisposableEffect(starManager) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            starredIds = starManager.getStarredMessageIds()
        }
        starManager.registerChangeListener(listener)
        onDispose {
            starManager.unregisterChangeListener(listener)
        }
    }
    var permissionsGranted by remember { mutableStateOf(checkSmsPermissions(context)) }
    var isDefaultSms by remember { mutableStateOf(checkDefaultSms(context)) }
    var isBannerDismissed by remember { mutableStateOf(false) }

    // Navigation and composing options
    var activeThread by remember { mutableStateOf<SmsThread?>(null) }
    var isNewMessageOpen by remember { mutableStateOf(false) }
    var isDeletedFolderOpen by remember { mutableStateOf(false) }
    var isStarredFolderOpen by remember { mutableStateOf(false) }
    var selectedMessageIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var scrollToMessageId by remember { mutableStateOf<Long?>(null) }

    // Message Lists Thread safe updates
    var threads by remember { mutableStateOf<List<SmsThread>>(emptyList()) }
    var activeMessages by remember { mutableStateOf<List<SmsMessage>>(emptyList()) }
    var starredMessages by remember { mutableStateOf<List<Pair<SmsMessage, String>>>(emptyList()) }
    var refreshCounter by remember { mutableIntStateOf(0) }
    var activeTab by remember { mutableStateOf("INBOX") } // INBOX, ARCHIVE, or FINANCE

    // Swipe to delete thread states
    // Removed legacy threadToDelete state (now handled via soft deletion)

    // Minimal text field states
    var newRecipients by remember { mutableStateOf<List<ContactRecipient>>(emptyList()) }
    var newMessageText by remember { mutableStateOf("") }
    var chatMessageText by remember { mutableStateOf("") }

    BackHandler(enabled = activeThread != null || isNewMessageOpen || isDeletedFolderOpen || isStarredFolderOpen || selectedMessageIds.isNotEmpty()) {
        if (selectedMessageIds.isNotEmpty()) {
            selectedMessageIds = emptySet()
        } else if (activeThread != null) {
            activeThread = null
            chatMessageText = ""
            selectedMessageIds = emptySet()
            scrollToMessageId = null
            refreshCounter++
        } else if (isNewMessageOpen) {
            isNewMessageOpen = false
            newRecipients = emptyList()
            newMessageText = ""
        } else if (isDeletedFolderOpen) {
            isDeletedFolderOpen = false
            refreshCounter++
        } else if (isStarredFolderOpen) {
            isStarredFolderOpen = false
        }
    }

    // SMS Permissions
    val smsPermissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.RECEIVE_SMS,
                Manifest.permission.READ_SMS,
                Manifest.permission.SEND_SMS,
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.POST_NOTIFICATIONS
            )
        } else {
            arrayOf(
                Manifest.permission.RECEIVE_SMS,
                Manifest.permission.READ_SMS,
                Manifest.permission.SEND_SMS,
                Manifest.permission.READ_CONTACTS
            )
        }
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        permissionsGranted = results.values.all { it }
    }

    val defaultSmsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        isDefaultSms = checkDefaultSms(context)
    }

    // Register Background ContentObserver for Database Changes
    DisposableEffect(Unit) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                // Re-trigger load atomically
                refreshCounter++
            }
        }
        try {
            context.contentResolver.registerContentObserver(
                Uri.parse("content://sms"),
                true,
                observer
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }

        onDispose {
            try {
                context.contentResolver.unregisterContentObserver(observer)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // Recheck default SMS app and permissions on resume when user comes back from system settings
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isDefaultSms = checkDefaultSms(context)
                permissionsGranted = checkSmsPermissions(context)
                deletedIds = deleteManager.getDeletedThreads().keys
                deletedMessageIds = deleteManager.getDeletedMessages().keys
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // 1. Thread Querying Hook
    LaunchedEffect(permissionsGranted, archivedIds, unarchivedIds, deletedIds, refreshCounter) {
        if (permissionsGranted) {
            withContext(Dispatchers.IO) {
                deleteManager.cleanUpExpired(context)
                val dbThreads = queryAllThreads(context, archivedIds, unarchivedIds, deletedIds)
                withContext(Dispatchers.Main) {
                    threads = dbThreads
                }
            }
        }
    }

    // Open the conversation from a notification tap. Retry briefly; never fall back to compose.
    LaunchedEffect(permissionsGranted, targetSender, targetThreadId) {
        if (!permissionsGranted) return@LaunchedEffect
        if (targetSender.isNullOrEmpty() && targetThreadId <= 0L) return@LaunchedEffect

        var resolvedThreadId = targetThreadId
        if (resolvedThreadId <= 0L && !targetSender.isNullOrEmpty()) {
            resolvedThreadId = withContext(Dispatchers.IO) {
                try {
                    Telephony.Threads.getOrCreateThreadId(context, targetSender)
                } catch (e: Exception) {
                    0L
                }
            }
        }

        if (resolvedThreadId > 0L) {
            restoreIncomingConversation(context, resolvedThreadId)
            deletedIds = deleteManager.getDeletedThreads().keys
            archivedIds = archiveManager.getArchivedThreadIds()
            unarchivedIds = archiveManager.getUnarchivedThreadIds()
        }

        var found: SmsThread? = null
        for (attempt in 0 until 3) {
            val snapshot = if (attempt == 0 && threads.isNotEmpty()) {
                threads
            } else {
                withContext(Dispatchers.IO) {
                    queryAllThreads(
                        context,
                        archiveManager.getArchivedThreadIds(),
                        archiveManager.getUnarchivedThreadIds(),
                        deleteManager.getDeletedThreads().keys
                    )
                }.also { threads = it }
            }
            found = findThreadForNotification(snapshot, context, targetSender, resolvedThreadId)
            if (found != null) break
            if (attempt < 2) delay(250)
        }

        val matched = found
        if (matched != null) {
            activeThread = matched.copy(isArchived = false)
            isNewMessageOpen = false
            onTargetSenderHandled()
            return@LaunchedEffect
        }

        val address = targetSender.orEmpty()
        if (resolvedThreadId > 0L || address.isNotEmpty()) {
            val name = withContext(Dispatchers.IO) {
                if (address.isNotEmpty()) getContactName(context, address) ?: address else "Unknown"
            }
            activeThread = SmsThread(
                threadId = resolvedThreadId,
                address = address,
                name = name,
                snippet = "",
                timestamp = System.currentTimeMillis(),
                unreadCount = 0,
                isArchived = false
            )
            isNewMessageOpen = false
        }
        onTargetSenderHandled()
    }

    // 2. Active Thread Conversation List Querying Hook
    LaunchedEffect(activeThread, refreshCounter, deletedMessageIds) {
        val currentThread = activeThread
        if (currentThread != null && permissionsGranted) {
            withContext(Dispatchers.IO) {
                val msgs = queryMessagesForThread(context, currentThread.threadId, currentThread.address, deletedMessageIds)
                markThreadAsRead(context, currentThread.threadId)
                withContext(Dispatchers.Main) {
                    activeMessages = msgs
                }
            }
        }
    }

    // 3. Starred Messages Querying Hook
    LaunchedEffect(permissionsGranted, starredIds, refreshCounter, deletedMessageIds, deletedIds) {
        if (permissionsGranted) {
            withContext(Dispatchers.IO) {
                val validStarredIds = starredIds.filter { !deletedMessageIds.contains(it) }.toSet()
                val msgs = queryMessagesByIds(context, validStarredIds)
                if (msgs != null) {
                    // If some validStarredIds are not found in the DB (meaning they were permanently deleted),
                    // we clean them up from starManager's Shared Preferences.
                    val queriedMsgIds = msgs.map { it.id }.toSet()
                    val missingStarredIds = validStarredIds.filter { !queriedMsgIds.contains(it) }
                    if (missingStarredIds.isNotEmpty()) {
                        val starPrefs = context.getSharedPreferences("sms_star_prefs", Context.MODE_PRIVATE)
                        val starEditor = starPrefs.edit()
                        missingStarredIds.forEach { starEditor.remove(it.toString()) }
                        starEditor.apply()
                    }

                    val filteredMsgs = msgs.filter { !deletedIds.contains(it.threadId) }
                    val namedMsgs = filteredMsgs.map { msg ->
                        val name = getContactName(context, msg.address) ?: msg.address
                        msg to name
                    }
                    withContext(Dispatchers.Main) {
                        starredMessages = namedMsgs
                    }
                }
            }
        }
    }

    // Note: threadToDelete AlertDialog has been removed in favor of direct soft deletion

    val mainBackgroundGradient = Brush.verticalGradient(
        colors = listOf(
            Color(0xFF12141A), // Soft premium obsidian graphite slate top
            Color(0xFF090A0D)  // Elegant dark core obsidian bottom (easier on eyes)
        )
    )

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
        containerColor = Color.Transparent
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(mainBackgroundGradient)
                .padding(innerPadding)
        ) {
            // Gorgeous dismissible high-end Alert Banner
            if (permissionsGranted && !isDefaultSms && !isBannerDismissed) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .background(Color(0x12FF9F0A), RoundedCornerShape(12.dp))
                        .border(1.dp, Color(0x30FF9F0A), RoundedCornerShape(12.dp))
                        .padding(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Left Column (Clickable to trigger default SMS selector)
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { 
                                    requestDefaultSmsIntent(context as Activity)?.let { intent ->
                                        defaultSmsLauncher.launch(intent)
                                    }
                                }
                        ) {
                            Text(
                                text = "DEFAULT CLIENT STATUS: PAUSED",
                                color = AccentOrange,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 1.sp
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = "Tap here to grant default SMS dispatch capability",
                                color = TextSecondary,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        
                        Spacer(modifier = Modifier.width(10.dp))
                        
                        // Close Dismiss Icon
                        IconButton(
                            onClick = { isBannerDismissed = true },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Dismiss status advisory banner",
                                tint = AccentOrange.copy(alpha = 0.8f),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            // Render Views depends on permissions
            if (!permissionsGranted) {
                // Perfect, minimal Permission Screen
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "SMS LITE",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "This app acts as the default recipient and device inbox. Permissions to read, send, and receive SMS messages are required.",
                        fontSize = 13.sp,
                        color = TextSecondary,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 16.dp),
                        style = TextStyle(lineHeight = 18.sp)
                    )
                    Spacer(modifier = Modifier.height(28.dp))
                    Button(
                        onClick = { launcher.launch(smsPermissions) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = TextPrimary,
                            contentColor = OLEDBlack
                        ),
                        shape = RoundedCornerShape(2.dp)
                    ) {
                        Text(
                            text = "GRANT PERMISSIONS",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                }
            } else if (activeThread != null) {
                // CONVERSATION VIEW SCREEN
                ConversationScreen(
                    thread = activeThread!!,
                    messages = activeMessages,
                    chatMessageText = chatMessageText,
                    onTextMessageChange = { chatMessageText = it },
                    onBack = {
                        activeThread = null
                        chatMessageText = ""
                        selectedMessageIds = emptySet()
                        scrollToMessageId = null
                        refreshCounter++
                    },
                    onSendMessage = {
                        if (chatMessageText.trim().isNotEmpty()) {
                            sendMessage(context, activeThread!!.address, chatMessageText)
                            chatMessageText = ""
                            refreshCounter++
                        }
                    },
                    selectedMessageIds = selectedMessageIds,
                    starredIds = starredIds,
                    scrollToMessageId = scrollToMessageId,
                    onToggleMessageSelection = { id ->
                        selectedMessageIds = if (selectedMessageIds.contains(id)) {
                            selectedMessageIds - id
                        } else {
                            selectedMessageIds + id
                        }
                    },
                    onClearSelection = {
                        selectedMessageIds = emptySet()
                    },
                    onDeleteMessages = { ids ->
                        deleteSmsMessages(context, ids)
                        selectedMessageIds = emptySet()
                        refreshCounter++
                    },
                    onToggleStar = { id -> starManager.toggleStar(id) },
                    onToggleStarMultiple = { ids ->
                        val anyUnstarred = ids.any { !starredIds.contains(it) }
                        ids.forEach { id ->
                            if (anyUnstarred && !starredIds.contains(id)) {
                                starManager.toggleStar(id)
                            } else if (!anyUnstarred && starredIds.contains(id)) {
                                starManager.toggleStar(id)
                            }
                        }
                        selectedMessageIds = emptySet()
                    }
                )
            } else if (isNewMessageOpen) {
                // COMPOSE NEW MESSAGE SCREEN
                NewMessageScreen(
                    recipients = newRecipients,
                    onRecipientsChange = { newRecipients = it },
                    messageText = newMessageText,
                    onMessageTextChange = { newMessageText = it },
                    onBack = {
                        isNewMessageOpen = false
                        newRecipients = emptyList()
                        newMessageText = ""
                    },
                    onSend = {
                        newRecipients.forEach { rec ->
                            sendMessage(context, rec.number, newMessageText)
                        }
                        newRecipients = emptyList()
                        newMessageText = ""
                        isNewMessageOpen = false
                        refreshCounter++
                    }
                )
            } else if (isDeletedFolderOpen) {
                // RECENTLY DELETED SCREEN
                DeletedThreadsScreen(
                    deletedIds = deletedIds,
                    deletedMsgIds = deletedMessageIds,
                    deleteManager = deleteManager,
                    onBack = {
                        isDeletedFolderOpen = false
                        refreshCounter++
                    },
                    onRefresh = {
                        deletedIds = deleteManager.getDeletedThreads().keys
                        deletedMessageIds = deleteManager.getDeletedMessages().keys
                        refreshCounter++
                    }
                )
            } else if (isStarredFolderOpen) {
                StarredMessagesScreen(
                    starredMessages = starredMessages,
                    onBack = { isStarredFolderOpen = false },
                    onStarredMessageSelect = { threadId, messageId ->
                        val thread = threads.find { it.threadId == threadId }
                        if (thread != null) {
                            isStarredFolderOpen = false
                            scrollToMessageId = messageId
                            activeThread = thread
                        }
                    }
                )
            } else {
                // MAIN THREADS INBOX / ARCHIVAL SCREEN
                MainThreadsScreen(
                    threads = threads,
                    starredMessages = starredMessages,
                    activeTab = activeTab,
                    onTabChange = { activeTab = it },
                    onThreadSelect = { activeThread = it },
                    onStarredMessageSelect = { threadId, messageId ->
                        val thread = threads.find { it.threadId == threadId }
                        if (thread != null) {
                            scrollToMessageId = messageId
                            activeThread = thread
                        }
                    },
                    onArchiveToggle = { thread ->
                        if (thread.isArchived) {
                            archiveManager.unarchiveThread(context, thread.threadId)
                        } else {
                            archiveManager.archiveThread(context, thread.threadId)
                        }
                        archivedIds = archiveManager.getArchivedThreadIds()
                        unarchivedIds = archiveManager.getUnarchivedThreadIds()
                    },
                    onThreadDelete = { thread ->
                        deleteManager.softDeleteThread(thread.threadId)
                        deletedIds = deleteManager.getDeletedThreads().keys
                        refreshCounter++
                    },
                    onOpenDeletedFolder = { isDeletedFolderOpen = true },
                    onOpenStarredFolder = { isStarredFolderOpen = true },
                    onComposeClick = { isNewMessageOpen = true }
                )
            }
        }
    }
}

@Composable
fun MainThreadsScreen(
    threads: List<SmsThread>,
    starredMessages: List<Pair<SmsMessage, String>>,
    activeTab: String,
    onTabChange: (String) -> Unit,
    onThreadSelect: (SmsThread) -> Unit,
    onStarredMessageSelect: (Long, Long) -> Unit,
    onArchiveToggle: (SmsThread) -> Unit,
    onThreadDelete: (SmsThread) -> Unit,
    onOpenDeletedFolder: () -> Unit,
    onOpenStarredFolder: () -> Unit,
    onComposeClick: () -> Unit
) {
    val pagerState = rememberPagerState(initialPage = when (activeTab) {
        "INBOX" -> 0
        "ARCHIVE" -> 1
        else -> 2
    }) { 3 }

    // Sync pager state changes (from swipe) to activeTab
    LaunchedEffect(pagerState.currentPage) {
        val tab = when (pagerState.currentPage) {
            0 -> "INBOX"
            1 -> "ARCHIVE"
            else -> "FINANCE"
        }
        if (tab != activeTab) {
            onTabChange(tab)
        }
    }

    // Sync activeTab changes (from button clicks) to pager state
    LaunchedEffect(activeTab) {
        val targetPage = when (activeTab) {
            "INBOX" -> 0
            "ARCHIVE" -> 1
            else -> 2
        }
        if (pagerState.currentPage != targetPage) {
            pagerState.animateScrollToPage(
                page = targetPage,
                animationSpec = tween(
                    durationMillis = 200,
                    easing = FastOutSlowInEasing
                )
            )
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top Bar with Tab Slider and Menu
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // High-End Segmented Tab Slider - Soft Obsidian bar
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(Color(0xFF161821), RoundedCornerShape(24.dp))
                        .padding(4.dp)
                ) {
                    // Sliding background indicator following pager offset
                    val fraction = pagerState.currentPage + pagerState.currentPageOffsetFraction
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.333f)
                            .height(38.dp)
                            .align(Alignment.CenterStart)
                            .graphicsLayer {
                                translationX = fraction * size.width
                            }
                            .background(Color(0xFF232630), RoundedCornerShape(20.dp))
                            .border(
                                1.dp,
                                Color(0x30FFFFFF),
                                RoundedCornerShape(20.dp)
                            )
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        listOf("INBOX", "ARCHIVE", "FINANCE").forEach { tab ->
                            val selected = (tab == activeTab)
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(38.dp)
                                    .clip(RoundedCornerShape(20.dp))
                                    .clickable {
                                        onTabChange(tab)
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    val text = when (tab) {
                                        "INBOX" -> "INBOX"
                                        "ARCHIVE" -> "ARCHIVES"
                                        else -> "FINANCE"
                                    }
                                    Text(
                                        text = text,
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (selected) PureWhite else TextSecondary,
                                        letterSpacing = 1.sp
                                    )
                                    if (tab == "INBOX" && threads.any { !it.isArchived && it.unreadCount > 0 }) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Box(
                                            modifier = Modifier
                                                .size(5.dp)
                                                .background(AccentGreen, RoundedCornerShape(50))
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                
                // 3-Dot Menu
                var expanded by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { expanded = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Menu",
                            tint = TextSecondary
                        )
                    }
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                        modifier = Modifier.background(Color(0xFF1E2027))
                    ) {
                        DropdownMenuItem(
                            text = { 
                                Text(
                                    "Recently Deleted", 
                                    color = TextPrimary,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp
                                ) 
                            },
                            onClick = {
                                expanded = false
                                onOpenDeletedFolder()
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "Starred",
                                    color = TextPrimary,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp
                                )
                            },
                            onClick = {
                                expanded = false
                                onOpenStarredFolder()
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Thin elegant divider line
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color(0xFF1E2027))
            )

            val flingBehavior = PagerDefaults.flingBehavior(
                state = pagerState,
                pagerSnapDistance = PagerSnapDistance.atMost(1),
                snapAnimationSpec = tween(
                    durationMillis = 200,
                    easing = FastOutSlowInEasing
                )
            )

            // HorizontalPager allowing Horizontal Swiping between Tabs
            HorizontalPager(
                state = pagerState,
                flingBehavior = flingBehavior,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) { page ->
                if (page == 2) {
                    FinanceScreen()
                } else {
                    val listThreads = if (page == 0) {
                        threads.filter { !it.isArchived }
                    } else {
                        threads.filter { it.isArchived }
                    }

                    if (listThreads.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(32.dp)
                        ) {
                            Text(
                                text = if (page == 0) "[ NO ACTIVE TRANSMISSIONS ]" else "[ NO ARCHIVED DISPATCHES ]",
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = TextSecondary,
                                letterSpacing = 1.5.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = if (page == 0) {
                                    "Tap the glowing dispatch key below to start a new contact thread."
                                } else {
                                    "Your archived communication logs will reside here."
                                },
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = TextSecondary.copy(alpha = 0.6f),
                                letterSpacing = 0.5.sp,
                                lineHeight = 15.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                } else {
                    // Lazy Threads rendering - Gorgeous card rows
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 12.dp, bottom = 80.dp)
                    ) {
                        items(listThreads, key = { it.threadId }) { thread ->
                            ThreadListItem(
                                thread = thread,
                                onSelect = { onThreadSelect(thread) },
                                onArchive = { onArchiveToggle(thread) },
                                onDelete = { onThreadDelete(thread) }
                            )
                        }
                    }
                }
                }
            }
        }

        // Circular Floating Action Button with Pen Selector - Glow cyan-to-blue linear gradient
        val fabGradient = Brush.linearGradient(
            colors = listOf(
                Color(0xFF4086FF), // Futuristic Electric Blue
                Color(0xFF00E5FF)  // Glowing Modern Neon Cyan
            )
        )

        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 28.dp, end = 24.dp)
                .size(60.dp)
                .background(fabGradient, RoundedCornerShape(30.dp))
                .clickable { onComposeClick() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Create,
                contentDescription = "New SMS",
                tint = Color(0xFF07080B), // Deep dark black contrast print
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
fun ThreadListItem(
    thread: SmsThread,
    onSelect: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    isDeleted: Boolean = false
) {
    val unread = thread.unreadCount > 0
    val firstChar = (thread.name.firstOrNull() ?: '?').toString().uppercase()

    var offsetX by remember { mutableFloatStateOf(0f) }
    val animatedOffsetX by animateFloatAsState(targetValue = offsetX, label = "swipe_offset")
    val density = LocalDensity.current
    val swipeThresholdPx = with(density) { 100.dp.toPx() }
    
    var isDeleting by remember { mutableStateOf(false) }
    LaunchedEffect(isDeleting) {
        if (isDeleting) {
            offsetX = if (offsetX > 0) 2000f else -2000f
            kotlinx.coroutines.delay(500)
            onDelete()
            // Reset for reuse in lazy list
            isDeleting = false
            offsetX = 0f
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .height(IntrinsicSize.Min)
    ) {
        // Red swipe background underlay
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF2C0F14))
                .border(1.dp, Color(0xFFFF453A).copy(alpha = 0.25f), RoundedCornerShape(16.dp))
                .padding(horizontal = 20.dp),
            contentAlignment = if (offsetX < 0) Alignment.CenterEnd else Alignment.CenterStart
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Swipe to delete",
                    tint = Color(0xFFFF453A),
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "DELETE",
                    color = Color(0xFFFF453A),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Floating card foreground layer that registers horizontal drags
        Box(
            modifier = Modifier
                .offset { IntOffset(animatedOffsetX.roundToInt(), 0) }
                .pointerInput(thread.threadId) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (offsetX < -swipeThresholdPx || offsetX > swipeThresholdPx) {
                                isDeleting = true
                            } else {
                                offsetX = 0f
                            }
                        },
                        onDragCancel = {
                            if (!isDeleting) offsetX = 0f
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            if (!isDeleting) {
                                change.consume()
                                offsetX = (offsetX + dragAmount).coerceIn(-swipeThresholdPx * 1.5f, swipeThresholdPx * 1.5f)
                            }
                        }
                    )
                }
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(
                    if (unread) {
                        Brush.linearGradient(
                            colors = listOf(
                                Color(0xFF181C26), // Subtle navy slate glow for unread messages
                                Color(0xFF0F1117)
                            )
                        )
                    } else {
                        Brush.linearGradient(
                            colors = listOf(
                                Color(0xFF111319), // Pure refined obsidian surface
                                Color(0xFF0D0E12)
                            )
                        )
                    }
                )
                .border(
                    1.dp,
                    if (unread) Color(0x304086FF) else Color(0xFF1D2027),
                    RoundedCornerShape(16.dp)
                )
                .clickable { onSelect() }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Elegant Monospace Brutalist Container Avatar (Super-ellipse inspired)
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            if (unread) {
                                Brush.linearGradient(
                                    colors = listOf(Color(0xFF4086FF), Color(0xFF00E5FF))
                                )
                            } else {
                                Brush.linearGradient(
                                    colors = listOf(Color(0xFF22252E), Color(0xFF16181F))
                                )
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = firstChar,
                        color = if (unread) Color(0xFF07080B) else TextPrimary,
                        fontSize = 16.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                // Main Info Column
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = thread.name,
                                fontSize = 14.sp,
                                fontWeight = if (unread) FontWeight.Bold else FontWeight.SemiBold,
                                fontFamily = FontFamily.Monospace,
                                color = if (unread) PureWhite else TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (unread) {
                                Spacer(modifier = Modifier.width(6.dp))
                                // Pulsing core green dot for unread status
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .background(AccentGreen, RoundedCornerShape(50))
                                )
                            }
                        }
                        Text(
                            text = formatMinimalTimestamp(thread.timestamp),
                            fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace,
                            color = TextSecondary,
                            letterSpacing = 0.5.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = thread.snippet,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = if (unread) PureWhite.copy(alpha = 0.9f) else TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                // Premium outlined minimal archive badge
                Box(
                    modifier = Modifier
                        .border(1.dp, Color(0xFF23252E), RoundedCornerShape(8.dp))
                        .clickable { onArchive() }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = if (isDeleted) "RESTORE" else if (thread.isArchived) "UNARCHIVE" else "ARCHIVE",
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = AccentOrange,
                        letterSpacing = 0.5.sp
                    )
                }
            }
        }
    }
}

@Composable
fun StarredMessageListItem(
    message: SmsMessage,
    name: String,
    onSelect: () -> Unit
) {
    val firstChar = (name.firstOrNull() ?: '?').toString().uppercase()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .height(IntrinsicSize.Min)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF1B1914), // Subtle gold/dark gradient
                            Color(0xFF0D0E12)
                        )
                    )
                )
                .border(
                    1.dp,
                    Color(0x30FFD700), // Gold hint border
                    RoundedCornerShape(16.dp)
                )
                .clickable { onSelect() }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Avatar with gold-ish tint
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            Brush.linearGradient(
                                colors = listOf(Color(0xFF8C7311), Color(0xFF332A06))
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = firstChar,
                        color = Color(0xFFFFD700),
                        fontSize = 16.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                // Main Info Column
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = name,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = FontFamily.Monospace,
                                color = TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            text = formatMinimalTimestamp(message.timestamp),
                            fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace,
                            color = TextSecondary,
                            letterSpacing = 0.5.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = message.body,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Icon(
                    imageVector = Icons.Default.Star,
                    contentDescription = "Starred",
                    tint = Color(0xFFFFD700),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
fun ConversationScreen(
    thread: SmsThread,
    messages: List<SmsMessage>,
    chatMessageText: String,
    onTextMessageChange: (String) -> Unit,
    onBack: () -> Unit,
    onSendMessage: () -> Unit,
    selectedMessageIds: Set<Long>,
    starredIds: Set<Long>,
    scrollToMessageId: Long?,
    onToggleMessageSelection: (Long) -> Unit,
    onClearSelection: () -> Unit,
    onDeleteMessages: (List<Long>) -> Unit,
    onToggleStar: (Long) -> Unit,
    onToggleStarMultiple: (Set<Long>) -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    val inSelectionMode = selectedMessageIds.isNotEmpty()
    val archiveManager = remember { ArchiveManager(context) }

    var fontSizeMultiplier by remember { mutableFloatStateOf(1.0f) }
    var messageToDeleteByTripleTap by remember { mutableStateOf<SmsMessage?>(null) }
    var showBulkDeleteConfirmDialog by remember { mutableStateOf(false) }

    // Triple tap delete confirmation dialog
    if (messageToDeleteByTripleTap != null) {
        val msg = messageToDeleteByTripleTap!!
        var dontWarnAgain by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { messageToDeleteByTripleTap = null },
            containerColor = Color(0xFF161821),
            title = {
                Text(
                    text = "DELETE MESSAGE?",
                    color = PureWhite,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp
                )
            },
            text = {
                Column {
                    Text(
                        text = "Do you want to permanently delete this message?",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 16.sp
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { dontWarnAgain = !dontWarnAgain }
                    ) {
                        Checkbox(
                            checked = dontWarnAgain,
                            onCheckedChange = { dontWarnAgain = it },
                            colors = CheckboxDefaults.colors(
                                checkedColor = Color(0xFF4086FF),
                                uncheckedColor = TextSecondary,
                                checkmarkColor = PureWhite
                            )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Don't warn again",
                            color = TextSecondary,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (dontWarnAgain) {
                            archiveManager.setSkipMessageDeleteWarning(true)
                        }
                        onDeleteMessages(listOf(msg.id))
                        messageToDeleteByTripleTap = null
                    }
                ) {
                    Text(
                        text = "[ " + "DELETE" + " ]",
                        color = Color(0xFFFF453A),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { messageToDeleteByTripleTap = null }) {
                    Text(
                        text = "[ " + "CANCEL" + " ]",
                        color = TextSecondary,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        )
    }

    // Bulk deletion confirmation dialog
    if (showBulkDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showBulkDeleteConfirmDialog = false },
            containerColor = Color(0xFF161821),
            title = {
                Text(
                    text = "DELETE SELECTED MESSAGES?",
                    color = PureWhite,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp
                )
            },
            text = {
                Text(
                    text = "Permanently delete the ${selectedMessageIds.size} selected messages?",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 16.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteMessages(selectedMessageIds.toList())
                        showBulkDeleteConfirmDialog = false
                    }
                ) {
                    Text(
                        text = "[ " + "DELETE" + " ]",
                        color = Color(0xFFFF453A),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showBulkDeleteConfirmDialog = false }) {
                    Text(
                        text = "[ " + "CANCEL" + " ]",
                        color = TextSecondary,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .imePadding()
    ) {
        // Conversation Luxury Top Bar OR Contextual Select Bar
        if (inSelectionMode) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1E2027))
                    .padding(horizontal = 14.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onClearSelection,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF2C2F3E))
                            .size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cancel Selection",
                            tint = PureWhite,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = "${selectedMessageIds.size} SELECTED",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF00E5FF),
                        letterSpacing = 1.sp
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(
                        onClick = { onToggleStarMultiple(selectedMessageIds) },
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF2C2F3E))
                            .size(36.dp)
                    ) {
                        val isAllStarred = selectedMessageIds.isNotEmpty() && selectedMessageIds.all { starredIds.contains(it) }
                        Icon(
                            imageVector = if (isAllStarred) Icons.Default.Star else Icons.Outlined.Star,
                            contentDescription = "Star Selected",
                            tint = Color(0xFFFFD700),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(
                        onClick = {
                            val selectedMsgs = messages.filter { selectedMessageIds.contains(it.id) }
                            val textToCopy = selectedMsgs.joinToString("\n\n") { it.body }
                            clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(textToCopy))
                            Toast.makeText(context, "COPIED TO CLIPBOARD", Toast.LENGTH_SHORT).show()
                            onClearSelection()
                        },
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF2C2F3E))
                            .size(36.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_content_copy),
                            contentDescription = "Copy Selected",
                            tint = PureWhite,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(
                        onClick = { showBulkDeleteConfirmDialog = true },
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF331414))
                            .size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete Selected",
                            tint = Color(0xFFFF453A),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF111319))
                    .padding(horizontal = 14.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF1C1E26))
                        .size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Return",
                        tint = PureWhite,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = thread.name,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = PureWhite,
                        letterSpacing = 0.5.sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (thread.name != thread.address) "PEER DISPATCH • ${thread.address}" else "PEER DISPATCH ACTIVE",
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextSecondary,
                        letterSpacing = 0.5.sp
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                // Text size cycling clicker styled cleanly with retro terminal brackets
                Box(
                    modifier = Modifier
                        .border(1.dp, Color(0xFF23252E), RoundedCornerShape(8.dp))
                        .clickable {
                            fontSizeMultiplier = when (fontSizeMultiplier) {
                                1.0f -> 1.3f
                                1.3f -> 1.6f
                                1.6f -> 2.0f
                                2.0f -> 0.8f
                                else -> 1.0f
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "[ SIZE: ${(fontSizeMultiplier * 100).toInt()}% ]",
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = AccentOrange,
                        letterSpacing = 0.5.sp
                    )
                }
            }
        }
        
        // 1.dp accent header divider line
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color(0xFF1E2027))
        )

        // Deep fluid message lazy list
        val listState = rememberLazyListState()
        
        LaunchedEffect(scrollToMessageId, messages) {
            if (scrollToMessageId != null) {
                val index = messages.indexOfFirst { it.id == scrollToMessageId }
                if (index != -1) {
                    listState.animateScrollToItem(index)
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 16.dp)
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, zoom, _ ->
                        fontSizeMultiplier = (fontSizeMultiplier * zoom).coerceIn(0.7f, 2.2f)
                    }
                },
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(top = 20.dp, bottom = 24.dp)
        ) {
            // Group items or insert Day Headers
            var previousDateKey = ""
            messages.forEachIndexed { index, message ->
                val dateKey = formatDayHeaderDate(message.timestamp)
                if (dateKey != previousDateKey) {
                    previousDateKey = dateKey
                    item(key = "header_$dateKey") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = dateKey,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = TextSecondary,
                                letterSpacing = 1.sp
                            )
                        }
                    }
                }

                val isSelected = selectedMessageIds.contains(message.id)
                val isStarred = starredIds.contains(message.id)
                item(key = message.id) {
                    MessageBubbleItem(
                        message = message,
                        isSelected = isSelected,
                        isStarred = isStarred,
                        isInSelectionMode = inSelectionMode,
                        onToggleSelection = { onToggleMessageSelection(message.id) },
                        onTripleTapDelete = {
                            if (archiveManager.shouldSkipMessageDeleteWarning()) {
                                onDeleteMessages(listOf(message.id))
                            } else {
                                messageToDeleteByTripleTap = message
                            }
                        },
                        onDoubleTapStar = { onToggleStar(message.id) },
                        fontSizeMultiplier = fontSizeMultiplier
                    )
                }
            }
        }

        // 1.dp accent input bar divider line
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color(0xFF1E2027))
        )

        // Floating Docked Messaging Input Panel - Soft, clean layout
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0F1116))
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0xFF161821))
                    .border(1.dp, Color(0xFF232630), RoundedCornerShape(24.dp))
                    .padding(vertical = 12.dp, horizontal = 18.dp)
            ) {
                if (chatMessageText.isEmpty()) {
                    Text(
                        text = "TYPE DISPATCH ENVELOPE...",
                        fontSize = 11.sp,
                        color = TextSecondary.copy(alpha = 0.7f),
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 0.5.sp
                    )
                }
                BasicTextField(
                    value = chatMessageText,
                    onValueChange = onTextMessageChange,
                    textStyle = TextStyle(
                        color = PureWhite,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    cursorBrush = SolidColor(PureWhite)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            
            // Neon send pill
            val sendGradient = Brush.linearGradient(
                colors = listOf(Color(0xFF4086FF), Color(0xFF00E5FF))
            )
            Box(
                modifier = Modifier
                    .height(44.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(sendGradient)
                    .clickable { onSendMessage() }
                    .padding(horizontal = 22.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "SEND",
                    color = Color(0xFF07080B),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }
        }
    }
}

@Composable
fun MessageBubbleItem(
    message: SmsMessage,
    isSelected: Boolean,
    isStarred: Boolean,
    isInSelectionMode: Boolean,
    onToggleSelection: () -> Unit,
    onTripleTapDelete: () -> Unit,
    onDoubleTapStar: () -> Unit,
    fontSizeMultiplier: Float = 1.0f
) {
    val context = LocalContext.current
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    val isMe = message.type == 2 // 2 corresponds to SENT folder type

    var lastTapTime by remember { mutableLongStateOf(0L) }
    var tapCount by remember { mutableIntStateOf(0) }
    val suggestions = remember(message.body) { CopySuggestionsParser.extract(message.body) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isMe) Alignment.End else Alignment.Start
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isInSelectionMode) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Selection Status Indicator",
                    tint = if (isSelected) Color(0xFF00E5FF) else Color(0xFF232630),
                    modifier = Modifier
                        .size(18.dp)
                        .clickable { onToggleSelection() }
                        .padding(end = 6.dp)
                )
            }
            
            val bubbleGradient = if (isMe) {
                Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF3265E9), // Modern Electric Blue
                        Color(0xFF1E3BB2)  // Soft Navy Depth
                    )
                )
            } else {
                Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF161821), // Soft Card Obsidian
                        Color(0xFF0F1116)
                    )
                )
            }

            val bubbleOutline = when {
                isSelected -> Color(0xFF00E5FF)
                isMe -> Color(0x30FFFFFF)
                else -> Color(0xFF232630)
            }

            Box(
                modifier = Modifier
                    .pointerInput(message.id) {
                        detectTapGestures(
                            onTap = {
                                if (isInSelectionMode) {
                                    onToggleSelection()
                                } else {
                                    val currentTime = System.currentTimeMillis()
                                    if (currentTime - lastTapTime < 450) {
                                        tapCount++
                                    } else {
                                        tapCount = 1
                                    }
                                    lastTapTime = currentTime
                                    if (tapCount == 2) {
                                        onDoubleTapStar()
                                    }
                                    if (tapCount >= 3) {
                                        onTripleTapDelete()
                                        tapCount = 0
                                    }
                                }
                            },
                            onLongPress = {
                                onToggleSelection()
                            }
                        )
                    }
                    .widthIn(max = 290.dp)
                    .clip(
                        RoundedCornerShape(
                            topStart = 16.dp,
                            topEnd = 16.dp,
                            bottomStart = if (isMe) 16.dp else 4.dp,
                            bottomEnd = if (isMe) 4.dp else 16.dp
                        )
                    )
                    .background(bubbleGradient)
                    .border(
                        if (isSelected) 2.dp else 1.dp,
                        bubbleOutline,
                        shape = RoundedCornerShape(
                            topStart = 16.dp,
                            topEnd = 16.dp,
                            bottomStart = if (isMe) 16.dp else 4.dp,
                            bottomEnd = if (isMe) 4.dp else 16.dp
                        )
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Column {
                    LinkableText(
                        text = message.body,
                        fontSizeMultiplier = fontSizeMultiplier
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.align(Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (isStarred) {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = "Starred",
                                tint = Color(0xFFFFD700),
                                modifier = Modifier.size(10.dp)
                            )
                        }
                        Text(
                            text = formatMessageTime(message.timestamp),
                            fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace,
                            color = if (isMe) PureWhite.copy(alpha = 0.7f) else TextSecondary.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }

        // Copy Suggestions Section below the message bubble
        if (suggestions.isNotEmpty() && !isInSelectionMode) {
            Spacer(modifier = Modifier.height(6.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = if (isMe) 24.dp else 8.dp,
                        end = if (isMe) 8.dp else 24.dp
                    ),
                horizontalAlignment = if (isMe) Alignment.End else Alignment.Start
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(bottom = 4.dp, start = if (isMe) 0.dp else 2.dp, end = if (isMe) 2.dp else 0.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_content_copy),
                        contentDescription = "Copy suggestions icon",
                        tint = TextSecondary.copy(alpha = 0.75f),
                        modifier = Modifier.size(10.dp)
                    )
                    Text(
                        text = "Copy Suggestions",
                        fontSize = (9 * fontSizeMultiplier).sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        color = TextSecondary.copy(alpha = 0.75f),
                        letterSpacing = 0.5.sp
                    )
                }

                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start,
                    contentPadding = PaddingValues(vertical = 2.dp)
                ) {
                    items(suggestions) { item ->
                        CopySuggestionChip(
                            text = item,
                            fontSizeMultiplier = fontSizeMultiplier,
                            onClick = {
                                clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(item))
                                Toast.makeText(context, "COPIED: $item", Toast.LENGTH_SHORT).show()
                            }
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun CopySuggestionChip(
    text: String,
    fontSizeMultiplier: Float = 1.0f,
    onClick: () -> Unit
) {
    val isUrl = text.startsWith("http://", ignoreCase = true) || text.startsWith("https://", ignoreCase = true) || text.startsWith("www.", ignoreCase = true)
    val isHashtag = text.startsWith("#")

    val chipBorderColor = when {
        isUrl -> Color(0x404086FF)
        isHashtag -> Color(0x4000E5FF)
        else -> Color(0xFF282B37)
    }

    val chipBg = Color(0xFF14161E)
    val textColor = when {
        isUrl -> Color(0xFF64B5F6)
        isHashtag -> Color(0xFF00E5FF)
        else -> PureWhite.copy(alpha = 0.9f)
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(chipBg)
            .border(1.dp, chipBorderColor, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_content_copy),
                contentDescription = "Copy",
                tint = textColor.copy(alpha = 0.6f),
                modifier = Modifier.size(9.dp)
            )
            Text(
                text = text,
                fontSize = (10 * fontSizeMultiplier).sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun LinkableText(text: String, modifier: Modifier = Modifier, fontSizeMultiplier: Float = 1.0f) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    val matches = remember(text) {
        val list = mutableListOf<Triple<IntRange, String, String>>()
        val matcher = android.util.Patterns.WEB_URL.matcher(text)
        while (matcher.find()) {
            val start = matcher.start()
            val end = matcher.end()
            val rawUrl = matcher.group() ?: continue

            var url = rawUrl
            var actualEnd = end
            val trailingPunct = charArrayOf('.', ',', '!', '?', ';', ':', ')', ']', '}')
            while (url.isNotEmpty() && url.last() in trailingPunct) {
                if (url.last() == ')' && url.count { it == '(' } == url.count { it == ')' }) {
                    break
                }
                url = url.substring(0, url.length - 1)
                actualEnd--
            }

            if (url.isNotEmpty()) {
                val targetUrl = if (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) {
                    url
                } else {
                    "https://$url"
                }
                list.add(Triple(start until actualEnd, url, targetUrl))
            }
        }
        list
    }

    val annotatedString = remember(text, matches) {
        buildAnnotatedString {
            var lastIndex = 0
            for ((range, displayUrl, targetUrl) in matches) {
                if (range.first > lastIndex) {
                    append(text.substring(lastIndex, range.first))
                }

                pushStringAnnotation(tag = "URL", annotation = targetUrl)
                withLink(
                    LinkAnnotation.Url(
                        url = targetUrl,
                        linkInteractionListener = {
                            try {
                                uriHandler.openUri(targetUrl)
                            } catch (e: Exception) {
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(intent)
                                } catch (ex: Exception) {
                                    ex.printStackTrace()
                                }
                            }
                        }
                    )
                ) {
                    withStyle(
                        style = SpanStyle(
                            color = AccentBlue,
                            textDecoration = TextDecoration.Underline,
                            fontWeight = FontWeight.Medium
                        )
                    ) {
                        append(displayUrl)
                    }
                }
                pop()

                lastIndex = range.last + 1
            }

            if (lastIndex < text.length) {
                append(text.substring(lastIndex))
            }
        }
    }

    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

    val linkModifier = if (matches.isNotEmpty()) {
        modifier.pointerInput(annotatedString) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val up = waitForUpOrCancellation()
                if (up != null) {
                    layoutResult?.let { layout ->
                        val offset = layout.getOffsetForPosition(up.position)
                        val urlAnnotation = annotatedString.getStringAnnotations(tag = "URL", start = offset, end = offset).firstOrNull()
                        if (urlAnnotation != null) {
                            up.consume()
                            val targetUrl = urlAnnotation.item
                            try {
                                uriHandler.openUri(targetUrl)
                            } catch (e: Exception) {
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(intent)
                                } catch (ex: Exception) {
                                    ex.printStackTrace()
                                }
                            }
                        }
                    }
                }
            }
        }
    } else {
        modifier
    }

    Text(
        text = annotatedString,
        style = TextStyle(
            color = TextPrimary,
            fontSize = (14 * fontSizeMultiplier).sp,
            fontFamily = FontFamily.Monospace,
            lineHeight = (19 * fontSizeMultiplier).sp
        ),
        onTextLayout = { layoutResult = it },
        modifier = linkModifier
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NewMessageScreen(
    recipients: List<ContactRecipient>,
    onRecipientsChange: (List<ContactRecipient>) -> Unit,
    messageText: String,
    onMessageTextChange: (String) -> Unit,
    onBack: () -> Unit,
    onSend: () -> Unit
) {
    val context = LocalContext.current
    var typedInput by remember { mutableStateOf("") }

    var showMultiContactPicker by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedContacts by remember { mutableStateOf(emptySet<String>()) }
    var deviceContacts by remember { mutableStateOf<List<DeviceContact>>(emptyList()) }

    LaunchedEffect(showMultiContactPicker) {
        if (showMultiContactPicker) {
            withContext(Dispatchers.IO) {
                val contacts = queryAllDeviceContacts(context)
                withContext(Dispatchers.Main) {
                    deviceContacts = contacts
                    selectedContacts = recipients.map { it.number }.toSet()
                }
            }
        }
    }

    // Modern contact picker launcher to avoid manual phone number input
    val contactPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickContact()
    ) { contactUri: Uri? ->
        contactUri?.let { uri ->
            try {
                val contentResolver = context.contentResolver
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idColumnIndex = cursor.getColumnIndex(ContactsContract.Contacts._ID)
                        val nameColumnIndex = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
                        val hasPhoneIndex = cursor.getColumnIndex(ContactsContract.Contacts.HAS_PHONE_NUMBER)
                        if (idColumnIndex >= 0 && hasPhoneIndex >= 0) {
                            val contactId = cursor.getString(idColumnIndex)
                            val displayName = if (nameColumnIndex >= 0) cursor.getString(nameColumnIndex) else null
                            val hasPhoneNumber = cursor.getString(hasPhoneIndex)
                            if (hasPhoneNumber == "1") {
                                contentResolver.query(
                                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                                    null,
                                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?",
                                    arrayOf(contactId),
                                    null
                                )?.use { phoneCursor ->
                                    if (phoneCursor.moveToFirst()) {
                                        val numberIndex = phoneCursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                                        if (numberIndex >= 0) {
                                            val phoneNumber = phoneCursor.getString(numberIndex)
                                            if (!recipients.any { it.number == phoneNumber }) {
                                                onRecipientsChange(recipients + ContactRecipient(name = displayName, number = phoneNumber))
                                            }
                                        }
                                    }
                                }
                             } else {
                                Toast.makeText(context, "Contact has no phone number", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(context, "Failed to load contact info", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val handleSendAction = {
        var currentRecipients = recipients
        if (typedInput.trim().isNotEmpty()) {
            val num = typedInput.trim()
            if (!currentRecipients.any { it.number == num }) {
                currentRecipients = currentRecipients + ContactRecipient(name = null, number = num)
                onRecipientsChange(currentRecipients)
            }
            typedInput = ""
        }
        if (currentRecipients.isEmpty()) {
            Toast.makeText(context, "At least one recipient contact is required", Toast.LENGTH_SHORT).show()
        } else if (messageText.trim().isEmpty()) {
            Toast.makeText(context, "Compose a message body first", Toast.LENGTH_SHORT).show()
        } else {
            onSend()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .imePadding()
    ) {
        // Conversation Luxury Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF111319))
                .padding(horizontal = 14.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF1C1E26))
                    .size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Return",
                    tint = PureWhite,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Text(
                text = "NEW SMS",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = PureWhite,
                letterSpacing = 1.sp
            )
        }

        // 1.dp divider right after composer header row
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color(0xFF1E2027))
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Recipient Field - Highly integrated layout with inline Address Book trigger
            Column {
                Text(
                    text = "RECIPIENT CONTACT(S):",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TextSecondary,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(12.dp))
                
                // Display recipients selection as gorgeous chips
                if (recipients.isNotEmpty()) {
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        recipients.forEach { rec ->
                            Box(
                                modifier = Modifier
                                    .background(Color(0xFF1E212E), RoundedCornerShape(16.dp))
                                    .border(1.dp, Color(0x304086FF), RoundedCornerShape(16.dp))
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = rec.name ?: rec.number,
                                        color = PureWhite,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Remove contact recipient",
                                        tint = TextSecondary,
                                        modifier = Modifier
                                            .size(14.dp)
                                            .clickable {
                                                onRecipientsChange(recipients.filter { it.number != rec.number })
                                            }
                                    )
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Number entry box
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(Color(0xFF161821), RoundedCornerShape(12.dp))
                            .border(1.dp, Color(0xFF232630), RoundedCornerShape(12.dp))
                            .padding(14.dp)
                    ) {
                        if (typedInput.isEmpty()) {
                            Text(
                                text = "ENTER NUMBER OR TAP ICON...",
                                fontSize = 11.sp,
                                color = TextSecondary.copy(alpha = 0.6f),
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 0.5.sp
                            )
                        }
                        BasicTextField(
                            value = typedInput,
                            onValueChange = { typedInput = it },
                            textStyle = TextStyle(
                                color = PureWhite,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp
                            ),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            modifier = Modifier.fillMaxWidth(),
                            cursorBrush = SolidColor(PureWhite)
                        )
                    }

                    // Luxury [+] Add Typed contact button
                    if (typedInput.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .height(50.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF1C1E26))
                                .border(1.dp, Color(0x40FFFFFF), RoundedCornerShape(12.dp))
                                .clickable {
                                    val num = typedInput.trim()
                                    if (num.isNotEmpty() && !recipients.any { it.number == num }) {
                                        onRecipientsChange(recipients + ContactRecipient(name = null, number = num))
                                    }
                                    typedInput = ""
                                }
                                .padding(horizontal = 14.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "+ ADD",
                                color = Color(0xFF00E5FF),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // Luxury Person Contact Picker Floating Button
                    Box(
                        modifier = Modifier
                            .size(50.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF232630))
                            .border(1.dp, Color(0x30FFFFFF), RoundedCornerShape(12.dp))
                            .clickable { showMultiContactPicker = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = "Trigger Address Contacts Book Picker",
                            tint = Color(0xFF00E5FF), // Cyber-Cyan
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // Message Body text - Elegant spacious text area
            Column {
                Text(
                    text = "SMS BODY:",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TextSecondary,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF161821), RoundedCornerShape(12.dp))
                        .border(1.dp, Color(0xFF232630), RoundedCornerShape(12.dp))
                        .padding(14.dp)
                ) {
                    if (messageText.isEmpty()) {
                        Text(
                            text = "Compose your message...",
                            fontSize = 11.sp,
                            color = TextSecondary.copy(alpha = 0.6f),
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 0.5.sp
                        )
                    }
                    BasicTextField(
                        value = messageText,
                        onValueChange = onMessageTextChange,
                        textStyle = TextStyle(
                            color = PureWhite,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 140.dp),
                        cursorBrush = SolidColor(PureWhite)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Action Transmit button
            val submitGradient = Brush.linearGradient(
                colors = listOf(Color(0xFF4086FF), Color(0xFF00E5FF))
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .background(submitGradient)
                    .clickable { handleSendAction() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "SEND",
                    color = Color(0xFF07080B),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    letterSpacing = 1.5.sp
                )
            }
        }

        if (showMultiContactPicker) {
            val filteredContacts = remember(deviceContacts, searchQuery) {
                if (searchQuery.trim().isEmpty()) {
                    deviceContacts
                } else {
                    deviceContacts.filter {
                        it.name.contains(searchQuery, ignoreCase = true) ||
                        it.number.contains(searchQuery)
                    }
                }
            }

            AlertDialog(
                onDismissRequest = { showMultiContactPicker = false },
                containerColor = Color(0xFF161821),
                modifier = Modifier.fillMaxWidth().fillMaxHeight(0.85f),
                title = {
                    Text(
                        text = "Select Contacts",
                        color = PureWhite,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp
                    )
                },
                text = {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF0F1116), RoundedCornerShape(12.dp))
                                .border(1.dp, Color(0xFF232630), RoundedCornerShape(12.dp))
                                .padding(12.dp)
                        ) {
                            if (searchQuery.isEmpty()) {
                                Text(
                                    text = "Search contacts...",
                                    fontSize = 11.sp,
                                    color = TextSecondary.copy(alpha = 0.6f),
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            BasicTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                textStyle = TextStyle(
                                    color = PureWhite,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp
                                ),
                                modifier = Modifier.fillMaxWidth(),
                                cursorBrush = SolidColor(PureWhite)
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        if (deviceContacts.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxWidth().weight(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(color = Color(0xFF00E5FF))
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(filteredContacts, key = { it.number }) { contact ->
                                    val isChecked = selectedContacts.contains(contact.number)
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (isChecked) Color(0xFF1E2027) else Color.Transparent)
                                            .clickable {
                                                selectedContacts = if (isChecked) {
                                                    selectedContacts - contact.number
                                                } else {
                                                    selectedContacts + contact.number
                                                }
                                            }
                                            .padding(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(
                                            checked = isChecked,
                                            onCheckedChange = { checked ->
                                                selectedContacts = if (checked == true) {
                                                    selectedContacts + contact.number
                                                } else {
                                                    selectedContacts - contact.number
                                                }
                                            },
                                            colors = CheckboxDefaults.colors(
                                                checkedColor = Color(0xFF4086FF),
                                                uncheckedColor = TextSecondary,
                                                checkmarkColor = PureWhite
                                            )
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column {
                                            Text(
                                                text = contact.name,
                                                color = PureWhite,
                                                fontSize = 13.sp,
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                text = contact.number,
                                                color = TextSecondary,
                                                fontSize = 11.sp,
                                                fontFamily = FontFamily.Monospace
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val newRecipients = selectedContacts.map { number ->
                                val match = deviceContacts.find { it.number == number }
                                ContactRecipient(name = match?.name, number = number)
                            }
                            onRecipientsChange(newRecipients)
                            showMultiContactPicker = false
                        }
                    ) {
                        Text(
                            text = "[ Select (${selectedContacts.size}) ]",
                            color = Color(0xFF00E5FF),
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showMultiContactPicker = false }) {
                        Text(
                            text = "[ Cancel ]",
                            color = TextSecondary,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            )
        }
    }
}

// Minimal fast SMS parsing helpers
private fun checkSmsPermissions(context: Context): Boolean {
    val read = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
    val receive = ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
    val send = ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
    return read && receive && send
}

private fun checkDefaultSms(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val roleManager = context.getSystemService(RoleManager::class.java)
        roleManager?.isRoleHeld(RoleManager.ROLE_SMS) == true
    } else {
        @Suppress("DEPRECATION")
        val defaultPackage = Telephony.Sms.getDefaultSmsPackage(context)
        defaultPackage == context.packageName
    }
}

private fun requestDefaultSmsIntent(activity: Activity): Intent? {
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = activity.getSystemService(RoleManager::class.java)
            if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_SMS)) {
                if (!roleManager.isRoleHeld(RoleManager.ROLE_SMS)) {
                    roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS)
                } else null
            } else null
        } else {
            @Suppress("DEPRECATION")
            Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).apply {
                putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, activity.packageName)
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

private fun sendMessage(context: Context, number: String, body: String) {
    try {
        val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }

        // Write to system Outbox first to get the URI
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, number)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, System.currentTimeMillis())
            put(Telephony.Sms.READ, 1)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_OUTBOX)
            try {
                val threadId = Telephony.Threads.getOrCreateThreadId(context, number)
                if (threadId > 0) {
                    put(Telephony.Sms.THREAD_ID, threadId)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        val uri = context.contentResolver.insert(Telephony.Sms.Outbox.CONTENT_URI, values)

        // Create PendingIntent for sent status
        val sentIntent = Intent(context, SmsSentReceiver::class.java).apply {
            action = SmsSentReceiver.ACTION_SMS_SENT
            putExtra(SmsSentReceiver.EXTRA_MESSAGE_URI, uri?.toString() ?: "")
            putExtra(SmsSentReceiver.EXTRA_RECIPIENT, number)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val sentPendingIntent = PendingIntent.getBroadcast(
            context, 
            System.currentTimeMillis().toInt(), 
            sentIntent, 
            flags
        )

        // Deliver text (handles long SMS (>160 characters) by dividing the message)
        val parts = smsManager.divideMessage(body)
        if (parts.size == 1) {
            smsManager.sendTextMessage(number, null, body, sentPendingIntent, null)
        } else {
            @Suppress("UNCHECKED_CAST")
            val sentIntents = ArrayList<PendingIntent?>().apply {
                add(sentPendingIntent)
                repeat(parts.size - 1) { add(null) }
            } as ArrayList<PendingIntent>
            smsManager.sendMultipartTextMessage(number, null, parts, sentIntents, null)
        }
    } catch (e: Exception) {
        Toast.makeText(context, "Send failed: ${e.message}", Toast.LENGTH_SHORT).show()
        e.printStackTrace()
    }
}

private fun formatDayHeaderDate(milliSeconds: Long): String {
    val messageCal = java.util.Calendar.getInstance().apply { timeInMillis = milliSeconds }
    val nowCal = java.util.Calendar.getInstance()

    val isSameYear = messageCal.get(java.util.Calendar.YEAR) == nowCal.get(java.util.Calendar.YEAR)
    val isSameDay = isSameYear && messageCal.get(java.util.Calendar.DAY_OF_YEAR) == nowCal.get(java.util.Calendar.DAY_OF_YEAR)

    if (isSameDay) return "Today"

    val yesterdayCal = java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_YEAR, -1) }
    val isYesterday = yesterdayCal.get(java.util.Calendar.YEAR) == messageCal.get(java.util.Calendar.YEAR) &&
            yesterdayCal.get(java.util.Calendar.DAY_OF_YEAR) == messageCal.get(java.util.Calendar.DAY_OF_YEAR)

    if (isYesterday) return "Yesterday"

    val format = java.text.SimpleDateFormat("dd MMMM", java.util.Locale.US)
    return format.format(messageCal.time)
}

private fun formatMessageTime(milliSeconds: Long): String {
    val format = java.text.SimpleDateFormat("hh:mm a", java.util.Locale.US)
    return format.format(java.util.Date(milliSeconds))
}

private fun formatMinimalTimestamp(milliSeconds: Long): String {
    val diff = System.currentTimeMillis() - milliSeconds
    val sec = diff / 1000
    val min = sec / 60
    val hours = min / 60
    val days = hours / 24

    return when {
        sec < 60 -> "NOW"
        min < 60 -> "${min}MIN AGO"
        hours < 24 -> "${hours}H AGO"
        days < 7 -> "${days}D AGO"
        else -> {
            val date = java.util.Date(milliSeconds)
            val format = java.text.SimpleDateFormat("MMM dd", java.util.Locale.US)
            format.format(date).uppercase()
        }
    }
}

// Core database queries performing asynchronous operations
private fun queryAllThreads(
    context: Context,
    archivedIds: Set<Long>,
    unarchivedIds: Set<Long>,
    deletedIds: Set<Long>,
    onlyDeleted: Boolean = false
): List<SmsThread> {
    val fromConversations = queryThreadsFromConversations(
        context, archivedIds, unarchivedIds, deletedIds, onlyDeleted
    )
    if (fromConversations != null) return fromConversations
    return queryThreadsFromSmsScan(context, archivedIds, unarchivedIds, deletedIds, onlyDeleted)
}

private fun queryThreadsFromConversations(
    context: Context,
    archivedIds: Set<Long>,
    unarchivedIds: Set<Long>,
    deletedIds: Set<Long>,
    onlyDeleted: Boolean
): List<SmsThread>? {
    val uri = Uri.parse("content://mms-sms/conversations?simple=true")
    val threadsList = mutableListOf<SmsThread>()
    val contactCache = mutableMapOf<String, String>()
    try {
        val canonicalMap = loadCanonicalAddresses(context)
        val unreadCounts = loadUnreadCounts(context)
        context.contentResolver.query(uri, null, null, null, "date DESC")?.use { cursor ->
            val idIndex = cursor.getColumnIndex("_id")
            if (idIndex == -1) return null
            val dateIndex = cursor.getColumnIndex("date")
            val snippetIndex = cursor.getColumnIndex("snippet")
            val recipientIndex = cursor.getColumnIndex("recipient_ids")
            val archIndex = cursor.getColumnIndex("archived")

            var rowCount = 0
            var unresolved = 0
            while (cursor.moveToNext()) {
                rowCount++
                val threadId = cursor.getLong(idIndex)
                if (threadId <= 0L) continue
                if (onlyDeleted) {
                    if (!deletedIds.contains(threadId)) continue
                } else if (deletedIds.contains(threadId)) {
                    continue
                }

                val date = if (dateIndex != -1) cursor.getLong(dateIndex) else 0L
                val snippet = if (snippetIndex != -1) cursor.getString(snippetIndex).orEmpty() else ""
                val recipientIds = if (recipientIndex != -1) cursor.getString(recipientIndex).orEmpty() else ""
                val systemArchived = archIndex != -1 && cursor.getInt(archIndex) == 1

                val address = resolveAddressFromRecipientIds(recipientIds, canonicalMap)
                    ?: lookupLatestAddressForThread(context, threadId)
                if (address.isNullOrBlank()) {
                    unresolved++
                    continue
                }

                val name = contactCache.getOrPut(address) {
                    getContactName(context, address) ?: address
                }
                val isArchived =
                    (archivedIds.contains(threadId) || systemArchived) && !unarchivedIds.contains(threadId)

                threadsList.add(
                    SmsThread(
                        threadId = threadId,
                        address = address,
                        name = name,
                        snippet = snippet,
                        timestamp = date,
                        unreadCount = unreadCounts[threadId] ?: 0,
                        isArchived = isArchived
                    )
                )
            }
            if (rowCount > 0 && threadsList.isEmpty() && unresolved > 0) {
                return null
            }
        } ?: return null
    } catch (e: Exception) {
        e.printStackTrace()
        return null
    }
    return threadsList.sortedByDescending { it.timestamp }
}

private fun loadCanonicalAddresses(context: Context): Map<Long, String> {
    val map = mutableMapOf<Long, String>()
    try {
        context.contentResolver.query(
            Uri.parse("content://mms-sms/canonical-addresses"),
            arrayOf("_id", "address"),
            null,
            null,
            null
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndex("_id")
            val addrIndex = cursor.getColumnIndex("address")
            while (cursor.moveToNext()) {
                if (idIndex == -1 || addrIndex == -1) continue
                val address = cursor.getString(addrIndex) ?: continue
                if (address.isNotBlank()) {
                    map[cursor.getLong(idIndex)] = address
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return map
}

private fun resolveAddressFromRecipientIds(
    recipientIds: String,
    canonicalMap: Map<Long, String>
): String? {
    if (recipientIds.isBlank() || canonicalMap.isEmpty()) return null
    val addresses = recipientIds.trim().split(Regex("\\s+")).mapNotNull { token ->
        token.toLongOrNull()?.let { canonicalMap[it] }
    }
    return addresses.firstOrNull { it.isNotBlank() }
}

private fun lookupLatestAddressForThread(context: Context, threadId: Long): String? {
    try {
        context.contentResolver.query(
            Uri.parse("content://sms"),
            arrayOf("address"),
            "thread_id = ?",
            arrayOf(threadId.toString()),
            "date DESC"
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex("address")
                if (index != -1) {
                    val address = cursor.getString(index)
                    if (!address.isNullOrBlank()) return address
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return null
}

private fun loadUnreadCounts(context: Context): Map<Long, Int> {
    val map = mutableMapOf<Long, Int>()
    try {
        context.contentResolver.query(
            Uri.parse("content://sms/inbox"),
            arrayOf("thread_id"),
            "read = 0",
            null,
            null
        )?.use { cursor ->
            val index = cursor.getColumnIndex("thread_id")
            while (cursor.moveToNext()) {
                if (index == -1) continue
                val threadId = cursor.getLong(index)
                map[threadId] = (map[threadId] ?: 0) + 1
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return map
}

private fun queryThreadsFromSmsScan(
    context: Context,
    archivedIds: Set<Long>,
    unarchivedIds: Set<Long>,
    deletedIds: Set<Long>,
    onlyDeleted: Boolean
): List<SmsThread> {
    val threadsList = mutableListOf<SmsThread>()
    val uri = Uri.parse("content://sms")
    val projection = arrayOf("_id", "thread_id", "address", "body", "date", "read", "type")
    val contactCache = mutableMapOf<String, String>()

    val systemArchivedIds = mutableSetOf<Long>()
    try {
        context.contentResolver.query(
            Uri.parse("content://mms-sms/conversations?simple=true"),
            arrayOf("_id", "archived"),
            null, null, null
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndex("_id")
            val archIndex = cursor.getColumnIndex("archived")
            while (cursor.moveToNext()) {
                if (idIndex != -1 && archIndex != -1) {
                    if (cursor.getInt(archIndex) == 1) {
                        systemArchivedIds.add(cursor.getLong(idIndex))
                    }
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }

    try {
        context.contentResolver.query(uri, projection, null, null, "date DESC")?.use { cursor ->
            val idIndex = cursor.getColumnIndex("_id")
            val threadIdIndex = cursor.getColumnIndex("thread_id")
            val addressIndex = cursor.getColumnIndex("address")
            val bodyIndex = cursor.getColumnIndex("body")
            val dateIndex = cursor.getColumnIndex("date")
            val readIndex = cursor.getColumnIndex("read")
            val typeIndex = cursor.getColumnIndex("type")

            val tempMessages = mutableListOf<SmsMessage>()
            while (cursor.moveToNext()) {
                var threadId = if (threadIdIndex != -1) cursor.getLong(threadIdIndex) else 0L
                val address = if (addressIndex != -1) cursor.getString(addressIndex) ?: "Unknown" else "Unknown"
                if (address == "Unknown" || address.isBlank()) continue

                if (threadId == 0L) {
                    try {
                        threadId = Telephony.Threads.getOrCreateThreadId(context, address)
                    } catch (e: Exception) {
                        // ignore
                    }
                }
                if (threadId == 0L) continue

                val id = if (idIndex != -1) cursor.getLong(idIndex) else 0L
                val body = if (bodyIndex != -1) cursor.getString(bodyIndex) ?: "" else ""
                val date = if (dateIndex != -1) cursor.getLong(dateIndex) else 0L
                val read = if (readIndex != -1) cursor.getInt(readIndex) else 1
                val type = if (typeIndex != -1) cursor.getInt(typeIndex) else 1

                tempMessages.add(SmsMessage(id, threadId, address, body, date, read, type))
            }

            val grouped = tempMessages.groupBy { it.threadId }
            for ((threadId, msgs) in grouped) {
                if (onlyDeleted) {
                    if (!deletedIds.contains(threadId)) continue
                } else {
                    if (deletedIds.contains(threadId)) continue
                }

                val lastMsg = msgs.firstOrNull()
                val address = msgs.firstOrNull { it.type == 1 }?.address
                    ?: msgs.firstOrNull { it.address.isNotEmpty() && it.address != "Unknown" }?.address
                    ?: lastMsg?.address
                    ?: "Unknown"

                val name = contactCache.getOrPut(address) {
                    getContactName(context, address) ?: address
                }

                val unreadCount = msgs.count { it.read == 0 && it.type == 1 }
                val isArchived = (archivedIds.contains(threadId) || systemArchivedIds.contains(threadId)) && !unarchivedIds.contains(threadId)

                threadsList.add(
                    SmsThread(
                        threadId = threadId,
                        address = address,
                        name = name,
                        snippet = lastMsg?.body ?: "",
                        timestamp = lastMsg?.timestamp ?: 0L,
                        unreadCount = unreadCount,
                        isArchived = isArchived
                    )
                )
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return threadsList.sortedByDescending { it.timestamp }
}

private fun findThreadForNotification(
    threads: List<SmsThread>,
    context: Context,
    sender: String?,
    threadId: Long
): SmsThread? {
    if (threadId > 0L) {
        threads.find { it.threadId == threadId }?.let { return it }
    }
    if (!sender.isNullOrEmpty()) {
        threads.find { it.address == sender }?.let { return it }
        threads.find { PhoneNumberUtils.compare(it.address, sender) }?.let { return it }
        try {
            val resolved = Telephony.Threads.getOrCreateThreadId(context, sender)
            if (resolved > 0L) {
                threads.find { it.threadId == resolved }?.let { return it }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    return null
}

private fun queryMessagesForThread(
    context: Context,
    threadId: Long,
    address: String? = null,
    deletedMessageIds: Set<Long> = emptySet()
): List<SmsMessage> {
    val messagesMap = mutableMapOf<Long, SmsMessage>()
    val projection = arrayOf("_id", "thread_id", "address", "body", "date", "read", "type")

    fun extractMessagesFromCursor(cursor: Cursor?) {
        cursor?.use { c ->
            val idIndex = c.getColumnIndex("_id")
            val threadIdIndex = c.getColumnIndex("thread_id")
            val addressIndex = c.getColumnIndex("address")
            val bodyIndex = c.getColumnIndex("body")
            val dateIndex = c.getColumnIndex("date")
            val readIndex = c.getColumnIndex("read")
            val typeIndex = c.getColumnIndex("type")

            while (c.moveToNext()) {
                val id = if (idIndex != -1) c.getLong(idIndex) else 0L
                if (id == 0L || deletedMessageIds.contains(id)) continue
                if (messagesMap.containsKey(id)) continue

                val tId = if (threadIdIndex != -1) c.getLong(threadIdIndex) else threadId
                val msgAddress = if (addressIndex != -1) c.getString(addressIndex) ?: "" else ""
                val body = if (bodyIndex != -1) c.getString(bodyIndex) ?: "" else ""
                val date = if (dateIndex != -1) c.getLong(dateIndex) else 0L
                val read = if (readIndex != -1) c.getInt(readIndex) else 1
                val type = if (typeIndex != -1) c.getInt(typeIndex) else 1

                messagesMap[id] = SmsMessage(id, tId, msgAddress, body, date, read, type)
            }
        }
    }

    if (threadId > 0) {
        try {
            val cursor = context.contentResolver.query(
                Uri.parse("content://sms"),
                projection,
                "thread_id = ?",
                arrayOf(threadId.toString()),
                "date ASC"
            )
            extractMessagesFromCursor(cursor)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    if (messagesMap.isEmpty() && !address.isNullOrEmpty() && address != "Unknown") {
        try {
            val cursor = context.contentResolver.query(
                Uri.parse("content://sms"),
                projection,
                "address = ?",
                arrayOf(address),
                "date ASC"
            )
            extractMessagesFromCursor(cursor)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (messagesMap.isEmpty()) {
            try {
                val resolved = Telephony.Threads.getOrCreateThreadId(context, address)
                if (resolved > 0L && resolved != threadId) {
                    val cursor = context.contentResolver.query(
                        Uri.parse("content://sms"),
                        projection,
                        "thread_id = ?",
                        arrayOf(resolved.toString()),
                        "date ASC"
                    )
                    extractMessagesFromCursor(cursor)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (messagesMap.isEmpty()) {
            try {
                context.contentResolver.query(
                    Uri.parse("content://sms"),
                    projection,
                    null,
                    null,
                    "date DESC"
                )?.use { cursor ->
                    val idIndex = cursor.getColumnIndex("_id")
                    val threadIdIndex = cursor.getColumnIndex("thread_id")
                    val addressIndex = cursor.getColumnIndex("address")
                    val bodyIndex = cursor.getColumnIndex("body")
                    val dateIndex = cursor.getColumnIndex("date")
                    val readIndex = cursor.getColumnIndex("read")
                    val typeIndex = cursor.getColumnIndex("type")
                    var scanned = 0
                    while (cursor.moveToNext() && scanned < 2000) {
                        scanned++
                        val msgAddress = if (addressIndex != -1) cursor.getString(addressIndex) ?: "" else ""
                        if (msgAddress.isBlank()) continue
                        if (msgAddress != address && !PhoneNumberUtils.compare(msgAddress, address)) continue
                        val id = if (idIndex != -1) cursor.getLong(idIndex) else 0L
                        if (id == 0L || deletedMessageIds.contains(id) || messagesMap.containsKey(id)) continue
                        val tId = if (threadIdIndex != -1) cursor.getLong(threadIdIndex) else threadId
                        val body = if (bodyIndex != -1) cursor.getString(bodyIndex) ?: "" else ""
                        val date = if (dateIndex != -1) cursor.getLong(dateIndex) else 0L
                        val read = if (readIndex != -1) cursor.getInt(readIndex) else 1
                        val type = if (typeIndex != -1) cursor.getInt(typeIndex) else 1
                        messagesMap[id] = SmsMessage(id, tId, msgAddress, body, date, read, type)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    return messagesMap.values.sortedBy { it.timestamp }
}

private fun queryMessagesByIds(context: Context, ids: Set<Long>): List<SmsMessage>? {
    if (ids.isEmpty()) return emptyList()
    val messages = mutableListOf<SmsMessage>()
    val uri = Uri.parse("content://sms")
    val projection = arrayOf("_id", "thread_id", "address", "body", "date", "read", "type")
    
    val placeholders = ids.joinToString(",") { "?" }
    val selection = "_id IN ($placeholders)"
    val selectionArgs = ids.map { it.toString() }.toTypedArray()
    try {
        val cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, "date DESC")
        if (cursor == null) {
            return null
        }
        cursor.use { c ->
            val idIndex = c.getColumnIndex("_id")
            val threadIdIndex = c.getColumnIndex("thread_id")
            val addressIndex = c.getColumnIndex("address")
            val bodyIndex = c.getColumnIndex("body")
            val dateIndex = c.getColumnIndex("date")
            val readIndex = c.getColumnIndex("read")
            val typeIndex = c.getColumnIndex("type")

            while (c.moveToNext()) {
                val id = if (idIndex != -1) c.getLong(idIndex) else 0L
                val tId = if (threadIdIndex != -1) c.getLong(threadIdIndex) else 0L
                val address = if (addressIndex != -1) c.getString(addressIndex) ?: "" else ""
                val body = if (bodyIndex != -1) c.getString(bodyIndex) ?: "" else ""
                val date = if (dateIndex != -1) c.getLong(dateIndex) else 0L
                val read = if (readIndex != -1) c.getInt(readIndex) else 1
                val type = if (typeIndex != -1) c.getInt(typeIndex) else 1

                messages.add(SmsMessage(id, tId, address, body, date, read, type))
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
        return null
    }
    return messages
}

private fun markThreadAsRead(context: Context, threadId: Long) {
    try {
        val values = ContentValues().apply {
            put("read", 1)
        }
        context.contentResolver.update(
            Uri.parse("content://sms/inbox"),
            values,
            "thread_id = ? AND read = 0",
            arrayOf(threadId.toString())
        )
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

private fun getContactName(context: Context, phoneNumber: String): String? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
        context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
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

@Composable
fun StarredMessagesScreen(
    starredMessages: List<Pair<SmsMessage, String>>,
    onBack: () -> Unit,
    onStarredMessageSelect: (Long, Long) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(24.dp)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = PureWhite
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = "Starred".uppercase(),
                color = PureWhite,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
        }

        if (starredMessages.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(32.dp)
                ) {
                    Text(
                        text = "[ NO STARRED MESSAGES ]",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = TextSecondary,
                        letterSpacing = 1.5.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Starred messages will appear here for quick access.",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextSecondary.copy(alpha = 0.6f),
                        letterSpacing = 0.5.sp,
                        lineHeight = 15.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 12.dp, bottom = 80.dp)
            ) {
                items(starredMessages, key = { it.first.id }) { (msg, name) ->
                    StarredMessageListItem(
                        message = msg,
                        name = name,
                        onSelect = { onStarredMessageSelect(msg.threadId, msg.id) }
                    )
                }
            }
        }
    }
}

@Composable
fun DeletedThreadsScreen(
    deletedIds: Set<Long>,
    deletedMsgIds: Set<Long>,
    deleteManager: DeleteManager,
    onBack: () -> Unit,
    onRefresh: () -> Unit
) {
    val context = LocalContext.current
    var deletedThreads by remember { mutableStateOf<List<SmsThread>>(emptyList()) }
    var deletedMessages by remember { mutableStateOf<List<SmsMessage>>(emptyList()) }
    var senderNamesMap by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    LaunchedEffect(deletedIds, deletedMsgIds) {
        withContext(Dispatchers.IO) {
            val threads = queryAllThreads(context, emptySet(), emptySet(), deletedIds, true)
            val messages = queryMessagesByIds(context, deletedMsgIds) ?: emptyList()
            val names = messages.map { it.address }.distinct().associateWith { address ->
                getContactName(context, address) ?: address
            }
            withContext(Dispatchers.Main) {
                deletedThreads = threads
                deletedMessages = messages
                senderNamesMap = names
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Custom Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(24.dp)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = PureWhite
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = "Recently Deleted".uppercase(),
                color = PureWhite,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
        }

        // Info Banner
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .background(Color(0xFF2C0F14), RoundedCornerShape(8.dp))
                .border(1.dp, Color(0xFFFF453A).copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                .padding(12.dp)
        ) {
            Text(
                text = "Deleted Messages will be permanently removed in 6 hours.",
                color = Color(0xFFFF453A),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (deletedThreads.isEmpty() && deletedMessages.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "No deleted threads or messages",
                    color = TextSecondary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 80.dp)
            ) {
                if (deletedThreads.isNotEmpty()) {
                    item {
                        Text(
                            text = "DELETED THREADS",
                            color = TextSecondary,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                        )
                    }
                    items(deletedThreads, key = { "thread_${it.threadId}" }) { thread ->
                        ThreadListItem(
                            thread = thread,
                            isDeleted = true,
                            onSelect = {}, // No action on select for deleted
                            onArchive = {
                                // Restore action
                                deleteManager.restoreThread(thread.threadId)
                                onRefresh()
                            },
                            onDelete = {
                                // Permanently delete
                                permanentlyDeleteThread(context, thread.threadId)
                                deleteManager.restoreThread(thread.threadId)
                                onRefresh()
                            }
                        )
                    }
                }

                if (deletedMessages.isNotEmpty()) {
                    item {
                        Text(
                            text = "DELETED MESSAGES",
                            color = TextSecondary,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                        )
                    }
                    items(deletedMessages, key = { "msg_${it.id}" }) { message ->
                        DeletedMessageListItem(
                            message = message,
                            senderName = senderNamesMap[message.address] ?: message.address,
                            onRestore = {
                                deleteManager.restoreMessage(message.id)
                                onRefresh()
                            },
                            onDelete = {
                                deleteSmsMessages(context, listOf(message.id))
                                deleteManager.restoreMessage(message.id)
                                onRefresh()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DeletedMessageListItem(
    message: SmsMessage,
    senderName: String,
    onRestore: () -> Unit,
    onDelete: () -> Unit
) {
    val firstChar = (senderName.firstOrNull() ?: '?').toString().uppercase()

    var offsetX by remember { mutableFloatStateOf(0f) }
    val animatedOffsetX by animateFloatAsState(targetValue = offsetX, label = "swipe_offset")
    val density = LocalDensity.current
    val swipeThresholdPx = with(density) { 100.dp.toPx() }
    
    var isDeleting by remember { mutableStateOf(false) }
    LaunchedEffect(isDeleting) {
        if (isDeleting) {
            offsetX = if (offsetX > 0) 2000f else -2000f
            kotlinx.coroutines.delay(500)
            onDelete()
            isDeleting = false
            offsetX = 0f
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .height(IntrinsicSize.Min)
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF2C0F14))
                .border(1.dp, Color(0xFFFF453A).copy(alpha = 0.25f), RoundedCornerShape(16.dp))
                .padding(horizontal = 20.dp),
            contentAlignment = if (offsetX < 0) Alignment.CenterEnd else Alignment.CenterStart
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Swipe to delete",
                    tint = Color(0xFFFF453A),
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "DELETE",
                    color = Color(0xFFFF453A),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Box(
            modifier = Modifier
                .offset { IntOffset(animatedOffsetX.roundToInt(), 0) }
                .pointerInput(message.id) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (offsetX < -swipeThresholdPx || offsetX > swipeThresholdPx) {
                                isDeleting = true
                            } else {
                                offsetX = 0f
                            }
                        },
                        onDragCancel = {
                            if (!isDeleting) offsetX = 0f
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            if (!isDeleting) {
                                change.consume()
                                offsetX = (offsetX + dragAmount).coerceIn(-swipeThresholdPx * 1.5f, swipeThresholdPx * 1.5f)
                            }
                        }
                    )
                }
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF111319),
                            Color(0xFF0D0E12)
                        )
                    )
                )
                .border(
                    1.dp,
                    Color(0xFF1D2027),
                    RoundedCornerShape(16.dp)
                )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            Brush.linearGradient(
                                colors = listOf(Color(0xFF22252E), Color(0xFF16181F))
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = firstChar,
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = senderName,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Monospace,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = formatMinimalTimestamp(message.timestamp),
                            fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace,
                            color = TextSecondary,
                            letterSpacing = 0.5.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = message.body,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Box(
                    modifier = Modifier
                        .border(1.dp, Color(0xFF23252E), RoundedCornerShape(8.dp))
                        .clickable { onRestore() }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "RESTORE",
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = AccentOrange,
                        letterSpacing = 0.5.sp
                    )
                }
            }
        }
    }
}

data class DeviceContact(
    val name: String,
    val number: String
)

private fun queryAllDeviceContacts(context: Context): List<DeviceContact> {
    val list = mutableListOf<DeviceContact>()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
        context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED
    ) {
        return emptyList()
    }
    try {
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        context.contentResolver.query(uri, projection, null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC")?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (cursor.moveToNext()) {
                val name = if (nameIndex != -1) cursor.getString(nameIndex) ?: "" else ""
                val number = if (numberIndex != -1) cursor.getString(numberIndex) ?: "" else ""
                if (number.isNotEmpty()) {
                    val cleanNumber = number.replace(" ", "").replace("-", "")
                    if (cleanNumber.isNotEmpty()) {
                        list.add(DeviceContact(name, cleanNumber))
                    }
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return list.distinctBy { it.number }
}
