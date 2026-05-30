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
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.Telephony
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.ui.text.withLink
import kotlin.math.roundToInt
import com.example.ui.theme.*
import kotlinx.coroutines.Dispatchers
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // Retrieve initial target address if clicking a notification
        targetSenderState.value = intent?.getStringExtra("sender_number")

        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SMSAppScreen(
                        targetSender = targetSenderState.value,
                        onTargetSenderHandled = {
                            targetSenderState.value = null
                            intent?.removeExtra("sender_number")
                        }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        targetSenderState.value = intent.getStringExtra("sender_number")
    }
}

private fun deleteThread(context: Context, threadId: Long): Boolean {
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
fun SMSAppScreen(targetSender: String?, onTargetSenderHandled: () -> Unit) {
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
    var selectedMessageIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var scrollToMessageId by remember { mutableStateOf<Long?>(null) }

    // Message Lists Thread safe updates
    var threads by remember { mutableStateOf<List<SmsThread>>(emptyList()) }
    var activeMessages by remember { mutableStateOf<List<SmsMessage>>(emptyList()) }
    var starredMessages by remember { mutableStateOf<List<Pair<SmsMessage, String>>>(emptyList()) }
    var refreshCounter by remember { mutableIntStateOf(0) }
    var activeTab by remember { mutableStateOf("INBOX") } // INBOX, ARCHIVE, or STARRED

    // Swipe to delete thread states
    // Removed legacy threadToDelete state (now handled via soft deletion)

    // Minimal text field states
    var newRecipients by remember { mutableStateOf<List<ContactRecipient>>(emptyList()) }
    var newMessageText by remember { mutableStateOf("") }
    var chatMessageText by remember { mutableStateOf("") }

    BackHandler(enabled = activeThread != null || isNewMessageOpen || isDeletedFolderOpen || selectedMessageIds.isNotEmpty()) {
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
    DisposableEffect(refreshCounter) {
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
    LaunchedEffect(permissionsGranted, archivedIds, unarchivedIds, deletedIds, refreshCounter, targetSender) {
        if (permissionsGranted) {
            withContext(Dispatchers.IO) {
                // Background cleanup of expired soft-deleted threads
                deleteManager.cleanUpExpired(context)
                
                val dbThreads = queryAllThreads(context, archivedIds, unarchivedIds, deletedIds)
                withContext(Dispatchers.Main) {
                    threads = dbThreads
                    
                    // If target sender is provided from notification, open conversation automatically
                    if (targetSender != null) {
                        val found = dbThreads.find { it.address == targetSender }
                        if (found != null) {
                            activeThread = found
                        } else {
                            // If thread doesn't exist, navigate to compose directly
                            newRecipients = listOf(ContactRecipient(name = null, number = targetSender))
                            isNewMessageOpen = true
                        }
                        onTargetSenderHandled()
                    }
                }
            }
        }
    }

    // 2. Active Thread Conversation List Querying Hook
    LaunchedEffect(activeThread, refreshCounter, deletedMessageIds) {
        val currentThread = activeThread
        if (currentThread != null && permissionsGranted) {
            withContext(Dispatchers.IO) {
                val msgs = queryMessagesForThread(context, currentThread.threadId, deletedMessageIds)
                markThreadAsRead(context, currentThread.threadId)
                withContext(Dispatchers.Main) {
                    activeMessages = msgs
                }
            }
        }
    }

    // 3. Starred Messages Querying Hook
    LaunchedEffect(permissionsGranted, starredIds, refreshCounter, deletedMessageIds) {
        if (permissionsGranted) {
            withContext(Dispatchers.IO) {
                val validStarredIds = starredIds.filter { !deletedMessageIds.contains(it) }.toSet()
                val msgs = queryMessagesByIds(context, validStarredIds)
                val namedMsgs = msgs.map { msg ->
                    val name = getContactName(context, msg.address) ?: msg.address
                    msg to name
                }
                withContext(Dispatchers.Main) {
                    starredMessages = namedMsgs
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
                                text = Translator.get("default_client_paused"),
                                color = AccentOrange,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 1.sp
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = Translator.get("tap_grant_capability"),
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
                        text = "MINIMAL SMS",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = Translator.get("permission_required"),
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
                            text = Translator.get("grant_permissions"),
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
            else -> "STARRED"
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

    // Snappy fling behavior for HorizontalPager
    val flingBehavior = PagerDefaults.flingBehavior(
        state = pagerState,
        pagerSnapDistance = PagerSnapDistance.atMost(1),
        snapAnimationSpec = tween(
            durationMillis = 200,
            easing = FastOutSlowInEasing
        )
    )

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
                        listOf("INBOX", "ARCHIVE", "STARRED").forEach { tab ->
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
                                        "INBOX" -> Translator.get("inbox_tab")
                                        "ARCHIVE" -> Translator.get("archive_tab")
                                        else -> Translator.get("starred_tab")
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

            // HorizontalPager allowing Horizontal Swiping between Tabs
            HorizontalPager(
                state = pagerState,
                flingBehavior = flingBehavior,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) { page ->
                if (page == 2) {
                    if (starredMessages.isEmpty()) {
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
                                    text = Translator.get("no_starred_messages"),
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = TextSecondary,
                                    letterSpacing = 1.5.sp
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = Translator.get("no_starred_desc"),
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
                                text = if (page == 0) Translator.get("no_active_transmissions") else Translator.get("no_archived_dispatches"),
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = TextSecondary,
                                letterSpacing = 1.5.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = if (page == 0) {
                                    Translator.get("no_active_desc")
                                } else {
                                    Translator.get("no_archived_desc")
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
                    text = Translator.get("delete_message"),
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
                        text = Translator.get("delete_message_desc"),
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
                            text = Translator.get("dont_warn_again"),
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
                            archiveManager.setSkipThreadDeleteWarning(true)
                        }
                        onDeleteMessages(listOf(msg.id))
                        messageToDeleteByTripleTap = null
                    }
                ) {
                    Text(
                        text = "[ " + Translator.get("delete") + " ]",
                        color = Color(0xFFFF453A),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { messageToDeleteByTripleTap = null }) {
                    Text(
                        text = "[ " + Translator.get("cancel") + " ]",
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
                    text = Translator.get("delete_selected_messages"),
                    color = PureWhite,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp
                )
            },
            text = {
                Text(
                    text = Translator.get("delete_selected_messages_desc", selectedMessageIds.size),
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
                        text = "[ " + Translator.get("delete") + " ]",
                        color = Color(0xFFFF453A),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showBulkDeleteConfirmDialog = false }) {
                    Text(
                        text = "[ " + Translator.get("cancel") + " ]",
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
                        text = Translator.get("selected_count", selectedMessageIds.size),
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
                            Toast.makeText(context, Translator.get("copied_to_clipboard"), Toast.LENGTH_SHORT).show()
                            onClearSelection()
                        },
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF2C2F3E))
                            .size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
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
                        text = if (thread.name != thread.address) Translator.get("peer_dispatch_info", thread.address) else Translator.get("peer_dispatch_active"),
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
            items(messages, key = { it.id }) { message ->
                val isSelected = selectedMessageIds.contains(message.id)
                val isStarred = starredIds.contains(message.id)
                MessageBubbleItem(
                    message = message,
                    isSelected = isSelected,
                    isStarred = isStarred,
                    isInSelectionMode = inSelectionMode,
                    onToggleSelection = { onToggleMessageSelection(message.id) },
                    onTripleTapDelete = {
                        if (archiveManager.shouldSkipThreadDeleteWarning()) {
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
                        text = Translator.get("type_dispatch_envelope"),
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
                    text = Translator.get("send"),
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
    val isMe = message.type == 2 // 2 corresponds to SENT folder type

    var lastTapTime by remember { mutableLongStateOf(0L) }
    var tapCount by remember { mutableIntStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxWidth(),
        horizontalAlignment = if (isMe) Alignment.End else Alignment.Start
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (isInSelectionMode) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Selection Status Indicator",
                    tint = if (isSelected) Color(0xFF00E5FF) else Color(0xFF232630),
                    modifier = Modifier
                        .size(16.dp)
                        .clickable { onToggleSelection() }
                )
            }
            Text(
                text = formatMinimalTimestamp(message.timestamp),
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace,
                color = TextSecondary
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        
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
                .padding(14.dp)
        ) {
            Box {
                // Add bottom-right padding only when starred to keep star from clipping text
                LinkableText(
                    text = message.body,
                    fontSizeMultiplier = fontSizeMultiplier,
                    modifier = if (isStarred) Modifier.padding(end = 16.dp, bottom = 14.dp) else Modifier
                )
                if (isStarred) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = "Starred",
                        tint = Color(0xFFFFD700),
                        modifier = Modifier
                            .size(12.dp)
                            .align(Alignment.BottomEnd)
                    )
                }
            }
        }
    }
}

@Composable
fun LinkableText(text: String, modifier: Modifier = Modifier, fontSizeMultiplier: Float = 1.0f) {
    val uriHandler = LocalUriHandler.current
    // Pure fast regex for matching standard link protocols in 2026
    val urlPattern = Regex("(https?://[\\w-]+(\\.[\\w-]+)+(:\\d+)?(/[^\\s]*)?)")

    val annotatedString = buildAnnotatedString {
        var lastIndex = 0
        urlPattern.findAll(text).forEach { matchResult ->
            val start = matchResult.range.first
            val end = matchResult.range.last + 1

            if (start > lastIndex) {
                append(text.substring(lastIndex, start))
            }

            val url = matchResult.value
            pushStringAnnotation(tag = "URL", annotation = url)
            withLink(
                LinkAnnotation.Url(
                    url = url,
                    linkInteractionListener = {
                        try {
                            uriHandler.openUri(url)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                )
            ) {
                withStyle(
                    style = SpanStyle(
                        color = AccentBlue,
                        textDecoration = TextDecoration.Underline
                    )
                ) {
                    append(url)
                }
            }
            pop()

            lastIndex = end
        }

        if (lastIndex < text.length) {
            append(text.substring(lastIndex))
        }
    }

    Text(
        text = annotatedString,
        style = TextStyle(
            color = TextPrimary,
            fontSize = (14 * fontSizeMultiplier).sp,
            fontFamily = FontFamily.Monospace,
            lineHeight = (19 * fontSizeMultiplier).sp
        ),
        modifier = modifier
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
                                Toast.makeText(context, Translator.get("contact_no_phone"), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(context, Translator.get("failed_contact_info"), Toast.LENGTH_SHORT).show()
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
            Toast.makeText(context, Translator.get("empty_recipients_warning"), Toast.LENGTH_SHORT).show()
        } else if (messageText.trim().isEmpty()) {
            Toast.makeText(context, Translator.get("empty_body_warning"), Toast.LENGTH_SHORT).show()
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
                text = Translator.get("new_sms"),
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
                    text = Translator.get("recipient_contacts"),
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
                                text = Translator.get("enter_number_or_tap"),
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
                                text = Translator.get("add"),
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
                    text = Translator.get("sms_body"),
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
                            text = Translator.get("compose_message"),
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
                    text = Translator.get("send"),
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

        // Deliver text
        smsManager.sendTextMessage(number, null, body, sentPendingIntent, null)
    } catch (e: Exception) {
        Toast.makeText(context, "Send failed: ${e.message}", Toast.LENGTH_SHORT).show()
        e.printStackTrace()
    }
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
private fun queryAllThreads(context: Context, archivedIds: Set<Long>, unarchivedIds: Set<Long>, deletedIds: Set<Long>, onlyDeleted: Boolean = false): List<SmsThread> {
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
            // Standard limit on threads load to guarantee low RAM foot print & fast query execution times
            var count = 0
            while (cursor.moveToNext() && count < 800) {
                val threadId = if (threadIdIndex != -1) cursor.getLong(threadIdIndex) else 0L
                val address = if (addressIndex != -1) cursor.getString(addressIndex) ?: "Unknown" else "Unknown"
                if (threadId == 0L || address == "Unknown") continue

                val id = if (idIndex != -1) cursor.getLong(idIndex) else 0L
                val body = if (bodyIndex != -1) cursor.getString(bodyIndex) ?: "" else ""
                val date = if (dateIndex != -1) cursor.getLong(dateIndex) else 0L
                val read = if (readIndex != -1) cursor.getInt(readIndex) else 1
                val type = if (typeIndex != -1) cursor.getInt(typeIndex) else 1

                tempMessages.add(SmsMessage(id, threadId, address, body, date, read, type))
                count++
            }

            val grouped = tempMessages.groupBy { it.threadId }
            for ((threadId, msgs) in grouped) {
                if (onlyDeleted) {
                    if (!deletedIds.contains(threadId)) continue
                } else {
                    if (deletedIds.contains(threadId)) continue
                }
                
                val lastMsg = msgs.firstOrNull()
                val address = lastMsg?.address ?: "Unknown"

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

private fun queryMessagesForThread(context: Context, threadId: Long, deletedMessageIds: Set<Long>): List<SmsMessage> {
    val messages = mutableListOf<SmsMessage>()
    val uri = Uri.parse("content://sms")
    val projection = arrayOf("_id", "thread_id", "address", "body", "date", "read", "type")

    try {
        context.contentResolver.query(
            uri,
            projection,
            "thread_id = ?",
            arrayOf(threadId.toString()),
            "date ASC"
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndex("_id")
            val threadIdIndex = cursor.getColumnIndex("thread_id")
            val addressIndex = cursor.getColumnIndex("address")
            val bodyIndex = cursor.getColumnIndex("body")
            val dateIndex = cursor.getColumnIndex("date")
            val readIndex = cursor.getColumnIndex("read")
            val typeIndex = cursor.getColumnIndex("type")

            while (cursor.moveToNext()) {
                val id = if (idIndex != -1) cursor.getLong(idIndex) else 0L
                if (deletedMessageIds.contains(id)) continue
                val tId = if (threadIdIndex != -1) cursor.getLong(threadIdIndex) else 0L
                val address = if (addressIndex != -1) cursor.getString(addressIndex) ?: "" else ""
                val body = if (bodyIndex != -1) cursor.getString(bodyIndex) ?: "" else ""
                val date = if (dateIndex != -1) cursor.getLong(dateIndex) else 0L
                val read = if (readIndex != -1) cursor.getInt(readIndex) else 1
                val type = if (typeIndex != -1) cursor.getInt(typeIndex) else 1

                messages.add(SmsMessage(id, tId, address, body, date, read, type))
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return messages
}

private fun queryMessagesByIds(context: Context, ids: Set<Long>): List<SmsMessage> {
    if (ids.isEmpty()) return emptyList()
    val messages = mutableListOf<SmsMessage>()
    val uri = Uri.parse("content://sms")
    val projection = arrayOf("_id", "thread_id", "address", "body", "date", "read", "type")
    
    val selection = "_id IN (${ids.joinToString(",")})"
    try {
        context.contentResolver.query(uri, projection, selection, null, "date DESC")?.use { cursor ->
            val idIndex = cursor.getColumnIndex("_id")
            val threadIdIndex = cursor.getColumnIndex("thread_id")
            val addressIndex = cursor.getColumnIndex("address")
            val bodyIndex = cursor.getColumnIndex("body")
            val dateIndex = cursor.getColumnIndex("date")
            val readIndex = cursor.getColumnIndex("read")
            val typeIndex = cursor.getColumnIndex("type")

            while (cursor.moveToNext()) {
                val id = if (idIndex != -1) cursor.getLong(idIndex) else 0L
                val tId = if (threadIdIndex != -1) cursor.getLong(threadIdIndex) else 0L
                val address = if (addressIndex != -1) cursor.getString(addressIndex) ?: "" else ""
                val body = if (bodyIndex != -1) cursor.getString(bodyIndex) ?: "" else ""
                val date = if (dateIndex != -1) cursor.getLong(dateIndex) else 0L
                val read = if (readIndex != -1) cursor.getInt(readIndex) else 1
                val type = if (typeIndex != -1) cursor.getInt(typeIndex) else 1

                messages.add(SmsMessage(id, tId, address, body, date, read, type))
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
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

object Translator {
    private val translations = mapOf(
        "en" to mapOf(
            "delete_thread" to "DELETE THREAD?",
            "delete_thread_desc" to "Are you sure you want to delete this conversation thread? This deletes all contained dispatches and is irreversible.",
            "dont_warn_again" to "Don't warn again",
            "delete" to "DELETE",
            "cancel" to "CANCEL",
            "default_client_paused" to "DEFAULT CLIENT STATUS: PAUSED",
            "default_client_active" to "DEFAULT CLIENT STATUS: ACTIVE",
            "tap_grant_capability" to "Tap here to grant default SMS dispatch capability",
            "permission_required" to "This app acts as the default recipient and device inbox. Permissions to read, send, and receive SMS messages are required.",
            "grant_permissions" to "GRANT PERMISSIONS",
            "archive" to "ARCHIVE",
            "unarchive" to "UNARCHIVE",
            "no_active_transmissions" to "[ NO ACTIVE TRANSMISSIONS ]",
            "no_active_desc" to "Tap the glowing dispatch key below to start a new contact thread.",
            "no_archived_dispatches" to "[ NO ARCHIVED DISPATCHES ]",
            "no_archived_desc" to "Your archived communication logs will reside here.",
            "peer_dispatch_active" to "PEER DISPATCH ACTIVE",
            "peer_dispatch_info" to "PEER DISPATCH • %s",
            "type_dispatch_envelope" to "TYPE DISPATCH ENVELOPE...",
            "send" to "SEND",
            "delete_selected_messages" to "DELETE SELECTED MESSAGES?",
            "delete_selected_messages_desc" to "Permanently delete the %d selected messages?",
            "selected_count" to "%d SELECTED",
            "delete_message" to "DELETE MESSAGE?",
            "delete_message_desc" to "Do you want to permanently delete this message?",
            "new_sms" to "NEW SMS",
            "recipient_contacts" to "RECIPIENT CONTACT(S):",
            "enter_number_or_tap" to "ENTER NUMBER OR TAP ICON...",
            "add" to "+ ADD",
            "sms_body" to "SMS BODY:",
            "compose_message" to "Compose your message...",
            "empty_recipients_warning" to "At least one recipient contact is required",
            "empty_body_warning" to "Compose a message body first",
            "contact_no_phone" to "Contact has no phone number",
            "failed_contact_info" to "Failed to load contact info",
            "inbox_tab" to "INBOX",
            "archive_tab" to "ARCHIVES",
            "starred_tab" to "STARRED",
            "no_starred_messages" to "[ NO STARRED MESSAGES ]",
            "no_starred_desc" to "Starred messages will appear here for quick access.",
            "copied_to_clipboard" to "COPIED TO CLIPBOARD"
        ),
        "es" to mapOf(
            "delete_thread" to "¿ELIMINAR HILO?",
            "delete_thread_desc" to "¿Está seguro de que desea eliminar este hilo de conversación? Esto elimina todos los envíos contenidos y es irreversible.",
            "dont_warn_again" to "No volver a advertir",
            "delete" to "ELIMINAR",
            "cancel" to "CANCELAR",
            "default_client_paused" to "ESTADO DE CLIENTE PREDETERMINADO: PAUSADO",
            "default_client_active" to "ESTADO DE CLIENTE PREDETERMINADO: ACTIVO",
            "tap_grant_capability" to "Toque aquí para otorgar capacidad de envío de SMS predeterminada",
            "permission_required" to "Esta aplicación actúa como el destinatario predeterminado y la bandeja de entrada del dispositivo. Se requieren permisos para leer, enviar y recibir mensajes SMS.",
            "grant_permissions" to "OTORGAR PERMISOS",
            "archive" to "ARCHIVAR",
            "unarchive" to "DESARCHIVAR",
            "no_active_transmissions" to "[ SIN TRANSMISIONES ACTIVAS ]",
            "no_active_desc" to "Toque la tecla luminosa de envío para iniciar un nuevo hilo.",
            "no_archived_dispatches" to "[ SIN ENVÍOS ARCHIVADOS ]",
            "no_archived_desc" to "Sus registros de comunicación archivados residirán aquí.",
            "peer_dispatch_active" to "DESPACHO DE PARES ACTIVO",
            "peer_dispatch_info" to "DESPACHO DE PARES • %s",
            "type_dispatch_envelope" to "ESCRIBIR MENSAJE...",
            "send" to "ENVIAR",
            "delete_selected_messages" to "¿ELIMINAR MENSAJES SELECCIONADOS?",
            "delete_selected_messages_desc" to "¿Eliminar permanentemente los %d mensajes seleccionados?",
            "selected_count" to "%d SELECCIONADOS",
            "delete_message" to "¿ELIMINAR MENSAJE?",
            "delete_message_desc" to "¿Desea eliminar permanentemente este mensaje?",
            "new_sms" to "NUEVO SMS",
            "recipient_contacts" to "CONTACTOS DESTINATARIOS:",
            "enter_number_or_tap" to "INGRESAR NÚMERO O TOCAR ICONO...",
            "add" to "+ AÑADIR",
            "sms_body" to "SMS CUERPO:",
            "compose_message" to "Escribe tu mensaje...",
            "empty_recipients_warning" to "Se requiere al menos un contacto destinatario",
            "empty_body_warning" to "Escriba un cuerpo de mensaje primero",
            "contact_no_phone" to "El contacto no tiene número de teléfono",
            "failed_contact_info" to "Error al cargar la información del contacto",
            "inbox_tab" to "BANDEJA",
            "archive_tab" to "ARCHIVOS",
            "starred_tab" to "DESTACADO",
            "no_starred_messages" to "[ NO HAY MENSAJES DESTACADOS ]",
            "no_starred_desc" to "Los mensajes destacados aparecerán aquí para un acceso rápido.",
            "copied_to_clipboard" to "COPIADO AL PORTAPAPELES"
        ),
        "fr" to mapOf(
            "delete_thread" to "SUPPRIMER LE FIL?",
            "delete_thread_desc" to "Êtes-vous sûr de vouloir supprimer ce fil de discussion? Cela supprime tous les messages qu'il contient et c'est irréversible.",
            "dont_warn_again" to "Ne plus m'avertir",
            "delete" to "SUPPRIMER",
            "cancel" to "ANNULER",
            "default_client_paused" to "STATUT CLIENT PAR DÉFAUT : EN PAUSE",
            "default_client_active" to "STATUT CLIENT PAR DÉFAUT : ACTIF",
            "tap_grant_capability" to "Appuyez ici pour définir l'application SMS par défaut",
            "permission_required" to "Cette application fait office de destinataire par défaut. Les autorisations de lecture, d'envoi et de réception de SMS sont requises.",
            "grant_permissions" to "ACCORDER L'AUTORISATION",
            "archive" to "ARCHIVER",
            "unarchive" to "DÉSARCHIVER",
            "no_active_transmissions" to "[ AUCUNE TRANSMISSION ]",
            "no_active_desc" to "Appuyez sur la touche d'envoi pour démarrer un nouveau fil de discussion.",
            "no_archived_dispatches" to "[ AUCUN MESSAGE ARCHIVÉ ]",
            "no_archived_desc" to "Vos communications archivées s'afficheront ici.",
            "peer_dispatch_active" to "CANAL DE TRANSMISSION ACTIF",
            "peer_dispatch_info" to "CANAL DE TRANSMISSION • %s",
            "type_dispatch_envelope" to "ÉCRIRE UN MESSAGE...",
            "send" to "ENVOYER",
            "delete_selected_messages" to "SUPPRIMER LES MESSAGES SÉLECTIONNÉS?",
            "delete_selected_messages_desc" to "Supprimer définitivement les %d messages sélectionnés?",
            "selected_count" to "%d SÉLECTIONNÉS",
            "delete_message" to "SUPPRIMER LE MESSAGE?",
            "delete_message_desc" to "Voulez-vous supprimer définitivement ce message?",
            "new_sms" to "NOUVEAU SMS",
            "recipient_contacts" to "DESTINATAIRES:",
            "enter_number_or_tap" to "ENTRER LE NUMÈRO OU COCHEZ...",
            "add" to "+ AJOUTER",
            "sms_body" to "MESSAGE TEXTE:",
            "compose_message" to "Rédiger votre message...",
            "empty_recipients_warning" to "Veuillez fournir un destinataire",
            "empty_body_warning" to "Rédiger un texte d'abord",
            "contact_no_phone" to "Le contact n'a pas de numéro de téléphone",
            "failed_contact_info" to "Échec du chargement des informations",
            "inbox_tab" to "BOÎTE",
            "archive_tab" to "ARCHIVES",
            "starred_tab" to "FAVORIS",
            "no_starred_messages" to "[ AUCUN MESSAGE FAVORIS ]",
            "no_starred_desc" to "Les messages favoris apparaîtront ici pour un accès rapide.",
            "copied_to_clipboard" to "COPIÉ DANS LE PRESSE-PAPIERS"
        ),
        "de" to mapOf(
            "delete_thread" to "THREAD LÖSCHEN?",
            "delete_thread_desc" to "Sind Sie sicher, dass Sie diesen Konversationsverlauf lösung möchten? Dies löscht alle enthaltenen Nachrichten unwiderruflich.",
            "dont_warn_again" to "Nicht mehr warnen",
            "delete" to "LÖSCHEN",
            "cancel" to "ABBRECHEN",
            "default_client_paused" to "STANDARD-CLIENT-STATUS: PAUSIERT",
            "default_client_active" to "STANDARD-CLIENT-STATUS: AKTIV",
            "tap_grant_capability" to "Hier tippen, um Standard-SMS-App festzulegen",
            "permission_required" to "Diese App fungiert als Standard-Empfänger. Berechtigungen zum Lesen, Senden und Empfangen von SMS sind erforderlich.",
            "grant_permissions" to "BERECHTIGUNGEN ERTEILEN",
            "archive" to "ARCHIVIEREN",
            "unarchive" to "DEARCHIVIEREN",
            "no_active_transmissions" to "[ KEINE AKTIVEN ÜBERTRAGUNGEN ]",
            "no_active_desc" to "Tippen Sie unten auf die Leuchttaste, um einen Thread zu starten.",
            "no_archived_dispatches" to "[ KEINE ARCHIVIERTEN NACHRICHTEN ]",
            "no_archived_desc" to "Ihre archivierten Protokolle werden hier angezeigt.",
            "peer_dispatch_active" to "DIREKTVERBINDUNG AKTIV",
            "peer_dispatch_info" to "DIREKTVERBINDUNG • %s",
            "type_dispatch_envelope" to "NACHRICHT SCHREIBEN...",
            "send" to "SENDEN",
            "delete_selected_messages" to "AUSGEWÄHLTE NACHRICHTEN LÖSCHEN?",
            "delete_selected_messages_desc" to "Die %d ausgewählten Nachrichten dauerhaft löschen?",
            "selected_count" to "%d AUSGEWÄHLT",
            "delete_message" to "NACHRICHT LÖSCHEN?",
            "delete_message_desc" to "Möchten Sie diese Nachricht dauerhaft löschen?",
            "new_sms" to "NEUE SMS",
            "recipient_contacts" to "EMPFÄNGERKONTAKT(E):",
            "enter_number_or_tap" to "EMPFÄNGERNUMMER EINGEBEN...",
            "add" to "+ HINZUFÜGEN",
            "sms_body" to "NACHRICHTENINHALT:",
            "compose_message" to "Nachricht verfassen...",
            "empty_recipients_warning" to "Empfänger erforderlich",
            "empty_body_warning" to "Nachrichtentext erforderlich",
            "contact_no_phone" to "Kontakt hat keine Telefonnummer",
            "failed_contact_info" to "Kontaktinfo konnte nicht geladen werden",
            "inbox_tab" to "POSTEINGANG",
            "archive_tab" to "ARCHIV",
            "starred_tab" to "MARKIERT",
            "no_starred_messages" to "[ KEINE MARKIERTEN NACHRICHTEN ]",
            "no_starred_desc" to "Markierte Nachrichten werden hier für den schnellen Zugriff angezeigt.",
            "copied_to_clipboard" to "IN ZWISCHENABLAGE KOPIERT"
        ),
        "zh" to mapOf(
            "delete_thread" to "删除会话？",
            "delete_thread_desc" to "确定要删除此会话吗？这将删除其中包含的所有信息，且无法恢复。",
            "dont_warn_again" to "不再提示",
            "delete" to "删除",
            "cancel" to "取消",
            "default_client_paused" to "默认客户端状态：已暂停",
            "default_client_active" to "默认客户端状态：已激活",
            "tap_grant_capability" to "点击此处授予默认SMS派遣权限",
            "permission_required" to "此应用需要作为默认短信程序。需要读取、发送和接收短信的权限。",
            "grant_permissions" to "授予权限",
            "archive" to "归档",
            "unarchive" to "取消归档",
            "no_active_transmissions" to "[ 无活动传输 ]",
            "no_active_desc" to "点击下方发信键开始新会话。",
            "no_archived_dispatches" to "[ 无已归档信息 ]",
            "no_archived_desc" to "您归档的通信记录将在此显示。",
            "peer_dispatch_active" to "点对点传输已激活",
            "peer_dispatch_info" to "点对点传输 • %s",
            "type_dispatch_envelope" to "输入信件内容...",
            "send" to "发送",
            "delete_selected_messages" to "删除选中的信息？",
            "delete_selected_messages_desc" to "永久删除选中的 %d 条信息？",
            "selected_count" to "已选中 %d 项",
            "delete_message" to "删除信息？",
            "delete_message_desc" to "确定要永久删除这条信息吗？",
            "new_sms" to "新建短信",
            "recipient_contacts" to "联系人:",
            "enter_number_or_tap" to "输入号码或点击图标...",
            "add" to "+ 添加",
            "sms_body" to "短信正文:",
            "compose_message" to "输入你的短信息内容...",
            "empty_recipients_warning" to "至少需要一个联系人",
            "empty_body_warning" to "请先编写内容",
            "contact_no_phone" to "该联系人没有电话号码",
            "failed_contact_info" to "加载联系人信息失败",
            "inbox_tab" to "收件箱",
            "archive_tab" to "归档箱",
            "starred_tab" to "星标",
            "no_starred_messages" to "[ 无星标信息 ]",
            "no_starred_desc" to "星标信息将在此处显示，以便快速访问。",
            "copied_to_clipboard" to "已复制到剪贴板"
        ),
        "ja" to mapOf(
            "delete_thread" to "スレッドを削除しますか？",
            "delete_thread_desc" to "この会話スレッドを削除してもよろしいですか？すべてのメッセージが消去され、復元することはできません。",
            "dont_warn_again" to "次回から表示しない",
            "delete" to "削除",
            "cancel" to "キャンセル",
            "default_client_paused" to "デフォルトクライアント：一時停止中",
            "default_client_active" to "デフォルトクライアント：有効",
            "tap_grant_capability" to "ここをタップしてデフォルトのSMSアプリに設定します",
            "permission_required" to "このアプリはデフォルトのメッセージングハンドラーとして動作します。SMSの読み取り、送信、および受信の権限が必要です。",
            "grant_permissions" to "権限を許可",
            "archive" to "アーカイブ",
            "unarchive" to "元に戻す",
            "no_active_transmissions" to "[ スレッドがありません ]",
            "no_active_desc" to "下のメッセージ作成ボタンをタップして開始します。",
            "no_archived_dispatches" to "[ アーカイブはありません ]",
            "no_archived_desc" to "アーカイブされたスレッドはここに表示されます。",
            "peer_dispatch_active" to "ピア配信有効",
            "peer_dispatch_info" to "ピア配信 • %s",
            "type_dispatch_envelope" to "メッセージを入力...",
            "send" to "送信",
            "delete_selected_messages" to "選択したメッセージを削除？",
            "delete_selected_messages_desc" to "選択された %d 件のメッセージを完全に削除しますか？",
            "selected_count" to "%d 件選択中",
            "delete_message" to "メッセージを削除しますか？",
            "delete_message_desc" to "このメッセージを永久に削除しますか？",
            "new_sms" to "新規SMS",
            "recipient_contacts" to "宛先:",
            "enter_number_or_tap" to "電話番号を入力...",
            "add" to "+ 追加",
            "sms_body" to "本文:",
            "compose_message" to "本文を入力してください...",
            "empty_recipients_warning" to "少なくとも1つの宛先が必要です",
            "empty_body_warning" to "まず本文を入力してください",
            "contact_no_phone" to "連絡先に電話番号がありません",
            "failed_contact_info" to "連絡先情報の読み込みに失敗しました",
            "inbox_tab" to "受信箱",
            "archive_tab" to "保管庫",
            "starred_tab" to "スター付き",
            "no_starred_messages" to "[ スター付きメッセージなし ]",
            "no_starred_desc" to "スター付きメッセージは、すばやくアクセスできるようにここに表示されます。",
            "copied_to_clipboard" to "クリップボードにコピーしました"
        ),
        "hi" to mapOf(
            "delete_thread" to "थ्रेड हटाएं?",
            "delete_thread_desc" to "क्या आप सुनिश्चित हैं कि आप इस बातचीत को हटाना चाहते हैं? इससे सभी संदेश हट जाएंगे और इसे वापस नहीं लाया जा सकता।",
            "dont_warn_again" to "दोबारा न पूछें",
            "delete" to "हटाएं",
            "cancel" to "रद्द करें",
            "default_client_paused" to "डिफ़ॉल्ट क्लाइंट स्थिति: रुकी हुई",
            "default_client_active" to "डिफ़ॉल्ट क्लाइंट स्थिति: सक्रिय",
            "tap_grant_capability" to "डिफ़ॉल्ट एसएमएस ऐप बनाने के लिए यहां टैप करें",
            "permission_required" to "इस ऐप को डिफ़ॉल्ट एसएमएस प्राप्तकर्ता के रूप में कार्य करने के लिए एसएमएस अनुमति की आवश्यकता है।",
            "grant_permissions" to "अनुमतियां प्रदान करें",
            "archive" to "संग्रह करें",
            "unarchive" to "वापस लाएं",
            "no_active_transmissions" to "[ कोई सक्रिय बातचीत नहीं ]",
            "no_active_desc" to "बातचीत शुरू करने के लिए नीचे दिए गए बटन पर टैप करें।",
            "no_archived_dispatches" to "[ कोई संग्रहीत थ्रेड नहीं ]",
            "no_archived_desc" to "प्राप्त संग्रहीत बातचीत यहाँ दिखाई देगी।",
            "peer_dispatch_active" to "सक्रिय कनेक्शन",
            "peer_dispatch_info" to "सक्रिय कनेक्शन • %s",
            "type_dispatch_envelope" to "संदेश लिखें...",
            "send" to "भेजें",
            "delete_selected_messages" to "चयनित संदेश हटाएं?",
            "delete_selected_messages_desc" to "क्या आप चयनित %d संदेशों को स्थायी रूप से हटाना चाहते हैं?",
            "selected_count" to "%d चयनित",
            "delete_message" to "संदेश हटाएं?",
            "delete_message_desc" to "क्या आप इस संदेश को हमेशा के लिए हटा करना चाहते हैं?",
            "new_sms" to "नया एसएमएस",
            "recipient_contacts" to "प्राप्तकर्ता:",
            "enter_number_or_tap" to "नंबर दर्ज करें...",
            "add" to "+ जोड़ें",
            "sms_body" to "एसएमएस बॉडी:",
            "compose_message" to "संदेश लिखें...",
            "empty_recipients_warning" to "कम से कम एक प्राप्तकर्ता आवश्यक है",
            "empty_body_warning" to "पहले संदेश लिखें",
            "contact_no_phone" to "संपर्क में कोई फ़ोन नंबर नहीं है",
            "failed_contact_info" to "संपर्क जानकारी लोड करने में विफल",
            "inbox_tab" to "इनबॉक्स",
            "archive_tab" to "संग्रह",
            "starred_tab" to "तारांकित",
            "no_starred_messages" to "[ कोई तारांकित संदेश नहीं ]",
            "no_starred_desc" to "तारांकित संदेश त्वरित पहुंच के लिए यहां दिखाई देंगे।",
            "copied_to_clipboard" to "क्लिपबोर्ड पर कॉपी किया गया"
        ),
        "ar" to mapOf(
            "delete_thread" to "حذف المحادثة؟",
            "delete_thread_desc" to "هل أنت متأكد من حذف هذه المحادثة؟ سيتم حذف جميع الرسائل بشكل نهائي ولا يمكن التراجع عن ذلك.",
            "dont_warn_again" to "عدم التنبيه مجدداً",
            "delete" to "حذف",
            "cancel" to "إلغاء",
            "default_client_paused" to "حالة التطبيق الافتراضي: متوقف مؤقتاً",
            "default_client_active" to "حالة التطبيق الافتراضي: نشط",
            "tap_grant_capability" to "اضغط هنا لتعيين التطبيق الافتراضي للرسائل",
            "permission_required" to "يحتاج هذا التطبيق للعمل كمستلم افتراضي للرسائل على جهازك. يُرجى منح أذونات الرسائل.",
            "grant_permissions" to "منح الأذونات",
            "archive" to "أرشفة",
            "unarchive" to "إلغاء الأرشفة",
            "no_active_transmissions" to "[ لا توجد مراسلات نشطة ]",
            "no_active_desc" to "اضغط على زر الإرسال لبدء محادثة جديدة.",
            "no_archived_dispatches" to "[ لا توجد مراسلات مؤرشفة ]",
            "no_archived_desc" to "ستظهر المراسلات المؤرشفة هنا.",
            "peer_dispatch_active" to "الاتصال نشط",
            "peer_dispatch_info" to "اتصال نشط • %s",
            "type_dispatch_envelope" to "اكتب رسالة...",
            "send" to "إرسال",
            "delete_selected_messages" to "حذف الرسائل المحددة؟",
            "delete_selected_messages_desc" to "هل تود حذف الـ %d رسائل المحددة نهائياً؟",
            "selected_count" to "تم تحديد %d",
            "delete_message" to "حذف الرسالة؟",
            "delete_message_desc" to "هل تريد حذف هذه الرسالة نهائياً؟",
            "new_sms" to "رسالة SMS جديدة",
            "recipient_contacts" to "قائمة المستلمين:",
            "enter_number_or_tap" to "أدخل الرقم أو انقر الرمز...",
            "add" to "+ إضافة",
            "sms_body" to "نص الرسالة:",
            "compose_message" to "اكتب نص الرسالة هنا...",
            "empty_recipients_warning" to "مستلم واحد على الأقل مطلوب",
            "empty_body_warning" to "اكتب نص الرسالة أولاً",
            "contact_no_phone" to "جهات الاتصال لا تملك رقم هاتف",
            "failed_contact_info" to "فشل في تحميل معلومات الاتصال",
            "inbox_tab" to "الرئيسية",
            "archive_tab" to "الأرشيف",
            "starred_tab" to "المميزة بنجمة",
            "no_starred_messages" to "[ لا توجد رسائل مميزة بنجمة ]",
            "no_starred_desc" to "ستظهر الرسائل المميزة بنجمة هنا للوصول السريع.",
            "copied_to_clipboard" to "تم النسخ إلى الحافظة"
        ),
        "pt" to mapOf(
            "delete_thread" to "EXCLUIR CONVERSA?",
            "delete_thread_desc" to "Tem certeza que deseja excluir esta conversa? Isso apagará todas as mensagens contidas e é irreversível.",
            "dont_warn_again" to "Não avisar novamente",
            "delete" to "EXCLUIR",
            "cancel" to "CANCELAR",
            "default_client_paused" to "STATUS DO CLIENTE PADRÃO: PAUSADO",
            "default_client_active" to "STATUS DO CLIENTE PADRÃO: ATIVO",
            "tap_grant_capability" to "Toque aqui para definir o aplicativo padrão de SMS",
            "permission_required" to "Este aplicativo requer permissão para atuar como o gerenciador padrão de SMS.",
            "grant_permissions" to "CONCEDER PERMISSÕES",
            "archive" to "ARQUIVAR",
            "unarchive" to "DESARQUIVAR",
            "no_active_transmissions" to "[ SEM TRANSMISSÕES ATIVAS ]",
            "no_active_desc" to "Toque no botão de envio abaixo para iniciar uma nova conversa.",
            "no_archived_dispatches" to "[ SEM MENSAGENS ARQUIVADAS ]",
            "no_archived_desc" to "Seu histórico de comunicações arquivadas aparecerá aqui.",
            "peer_dispatch_active" to "TRANSMISSÃO DIRETA ATIVA",
            "peer_dispatch_info" to "TRANSMISSÃO DIRETA • %s",
            "type_dispatch_envelope" to "DIGITE SUA MENSAGEM...",
            "send" to "ENVIAR",
            "delete_selected_messages" to "EXCLUIR SELECIONADAS?",
            "delete_selected_messages_desc" to "Apagar permanentemente as %d mensagens selecionadas?",
            "selected_count" to "%d SELECIONADAS",
            "delete_message" to "EXCLUIR MENSAGEM?",
            "delete_message_desc" to "Deseja excluir permanentemente esta mensagem?",
            "new_sms" to "NOVO SMS",
            "recipient_contacts" to "DESTINATÁRIO(S):",
            "enter_number_or_tap" to "DIGITE O NÚMERO OU TOQUE NO ÍCONE...",
            "add" to "+ INSERIR",
            "sms_body" to "CORPO DA MENSAGEM:",
            "compose_message" to "Digite sua mensagem...",
            "empty_recipients_warning" to "Forneça pelo menos um destinatário",
            "empty_body_warning" to "Escreva o texto da mensagem primeiro",
            "contact_no_phone" to "O contato não possui número de telefone",
            "failed_contact_info" to "Falha ao carregar informações do contato",
            "inbox_tab" to "CAIXA",
            "archive_tab" to "ARQUIVOS",
            "starred_tab" to "FAVORITOS",
            "no_starred_messages" to "[ SEM MENSAGENS FAVORITAS ]",
            "no_starred_desc" to "As mensagens com estrela aparecerão aqui para acesso rápido.",
            "copied_to_clipboard" to "COPIADO PARA A ÁREA DE TRANSFERÊNCIA"
        ),
        "ru" to mapOf(
            "delete_thread" to "УДАЛИТЬ ДИАЛОГ?",
            "delete_thread_desc" to "Вы уверены, что хотите удалить этот диалог? Это действие удалит всю историю сообщений без возможности восстановления.",
            "dont_warn_again" to "Больше не предупреждать",
            "delete" to "УДАЛИТЬ",
            "cancel" to "ОТМЕНА",
            "default_client_paused" to "СТАТУС ПО УМОЛЧАНИЮ: ПРИОСТАНОВЛЕН",
            "default_client_active" to "СТАТУС ПО УМОЛЧАНИЮ: АКТИВЕН",
            "tap_grant_capability" to "Нажмите для назначения приложения СМС по умолчанию",
            "permission_required" to "Этому приложению необходимо стать обработчиком SMS по умолчанию. Требуются разрешения на работу с сообщениями.",
            "grant_permissions" to "ПРЕДОСТАВИТЬ РАЗРЕШЕНИЯ",
            "archive" to "В АРХИВ",
            "unarchive" to "ИЗ АРХИВА",
            "no_active_transmissions" to "[ НЕТ АКТИВНЫХ ДИАЛОГОВ ]",
            "no_active_desc" to "Нажмите кнопку создания внизу, чтобы начать переписку.",
            "no_archived_dispatches" to "[ НЕТ АРХИВИРОВАННЫХ СООБЩЕНИЙ ]",
            "no_archived_desc" to "Ваши архивные диалоги будут храниться здесь.",
            "peer_dispatch_active" to "КАНАЛ СВЯЗИ АКТИВЕН",
            "peer_dispatch_info" to "КАНАЛ СВЯЗИ • %s",
            "type_dispatch_envelope" to "ВВЕДИТЕ СООБЩЕНИЕ...",
            "send" to "ОТПР.",
            "delete_selected_messages" to "УДАЛИТЬ ВЫБРАННЫЕ?",
            "delete_selected_messages_desc" to "Удалить выбранные сообщения (%d шт.) безвозвратно?",
            "selected_count" to "ВЫБРАНО %d",
            "delete_message" to "УДАЛИТЬ СООБЩЕНИЕ?",
            "delete_message_desc" to "Вы хотите удалить это сообщение навсегда?",
            "new_sms" to "НОВОЕ SMS",
            "recipient_contacts" to "ПОЛУЧАТЕЛИ:",
            "enter_number_or_tap" to "ВВЕДИТЕ НОМЕР ИЛИ ВЫБЕРИТЕ КОНТАКТ...",
            "add" to "+ ДОБ.",
            "sms_body" to "ТЕКСТ SMS:",
            "compose_message" to "Введите ваше сообщение...",
            "empty_recipients_warning" to "Укажите хотя бы одного получателя",
            "empty_body_warning" to "Введите текст сообщения",
            "contact_no_phone" to "У контакта нет номера телефона",
            "failed_contact_info" to "Не удалось загрузить данные контакта",
            "inbox_tab" to "ВХОДЯЩИЕ",
            "archive_tab" to "АРХИВ",
            "starred_tab" to "ОТМЕЧЕННЫЕ",
            "no_starred_messages" to "[ НЕТ ОТМЕЧЕННЫХ СООБЩЕНИЙ ]",
            "no_starred_desc" to "Здесь будут отображаться отмеченные сообщения для быстрого доступа.",
            "copied_to_clipboard" to "СКОПИРОВАНО В БУФЕР ОБМЕНА"
        ),
        "it" to mapOf(
            "delete_thread" to "ELIMINA CONVERSAZIONE?",
            "delete_thread_desc" to "Sei sicuro di voler eliminare questa conversazione? Questa azione eliminerà tutti i messaggi contenuti ed è irreversibile.",
            "dont_warn_again" to "Non mostrare più",
            "delete" to "ELIMINA",
            "cancel" to "ANNULLA",
            "default_client_paused" to "STATUT CLIENT PREDEFINITO: IN PAUSA",
            "default_client_active" to "STATUT CLIENT PREDEFINITO: ATTIVO",
            "tap_grant_capability" to "Tocca qui per impostarlo come principale di SMS sul cellulare",
            "permission_required" to "Questa app richiede i permessi per fungere da gestore predefinito di messaggi SMS.",
            "grant_permissions" to "CONCEDI I PERMESSI",
            "archive" to "ARCHIVIA",
            "unarchive" to "RIPRISTINA",
            "no_active_transmissions" to "[ NESSUNA TRASMISSIONE ATTIVA ]",
            "no_active_desc" to "Tocca il pulsante in basso per iniziare una conversazione.",
            "no_archived_dispatches" to "[ NESSUN DISSACCIO ARCHIVIATO ]",
            "no_archived_desc" to "I tuoi registri archiviati risiederanno qui.",
            "peer_dispatch_active" to "CONNESSIONE DIRETTA ATTIVA",
            "peer_dispatch_info" to "CONNESSIONE DIRETTA • %s",
            "type_dispatch_envelope" to "SCRIVI MESSAGGIO...",
            "send" to "INVIA",
            "delete_selected_messages" to "ELIMINA SELEZIONATI?",
            "delete_selected_messages_desc" to "Eliminare permanentemente i %d messaggi selezionati?",
            "selected_count" to "%d SELEZIONATI",
            "delete_message" to "ELIMINA MESSAGGIO?",
            "delete_message_desc" to "Desideri cancellare permanentemente questo messaggio?",
            "new_sms" to "NUOVO SMS",
            "recipient_contacts" to "DESTINATARIO(I):",
            "enter_number_or_tap" to "INSERISCI NUMERO O TOCCA L'ICONA CON...",
            "add" to "+ AGGIUNGI",
            "sms_body" to "TESTO SMS:",
            "compose_message" to "Scrivi il tuo messaggio...",
            "empty_recipients_warning" to "Destinatario richiesto",
            "empty_body_warning" to "Inserire corpo del messaggio prima",
            "contact_no_phone" to "Il contatto non ha un numero di telefono",
            "failed_contact_info" to "Impossibile caricare il contatto",
            "inbox_tab" to "INBOX",
            "archive_tab" to "ARCHIVIO",
            "starred_tab" to "SPECIALI",
            "no_starred_messages" to "[ NESSUN MESSAGGIO SPECIALE ]",
            "no_starred_desc" to "I messaggi speciali appariranno qui per un accesso rapido.",
            "copied_to_clipboard" to "COPIATO NEGLI APPUNTI"
        )
    )

    fun get(key: String, vararg args: Any): String {
        val lang = java.util.Locale.getDefault().language
        val langMap = translations[lang] ?: translations["en"]!!
        val template = langMap[key] ?: translations["en"]!![key] ?: key
        return try {
            String.format(java.util.Locale.getDefault(), template, *args)
        } catch (e: Exception) {
            template
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
            val messages = queryMessagesByIds(context, deletedMsgIds)
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
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "Back",
                    tint = PureWhite
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = "RECENTLY DELETED",
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
                                deleteThread(context, thread.threadId)
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
