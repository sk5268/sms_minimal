# Minimal SMS

A high-performance, dark-theme-only Android SMS client built with Jetpack Compose. Designed to be the default SMS app — fast, battery-efficient, and completely bloat-free.

## Features

- **Conversation Inbox** — Threads sorted by recency with unread count badges
- **OTP Auto-Detection** — Parses incoming messages for OTP/verification codes and surfaces a one-tap *Copy Code* action directly on the notification
- **Smart Notifications** — Shows the first 300 characters of each message with expandable BigText style; tapping opens the conversation directly
- **Archive** — Swipe any thread to archive it; toggle between Inbox and Archive via a snappy sliding tab switcher
- **Soft Delete / Recently Deleted** — Threads are soft-deleted and permanently purged after 6 hours; recover them from the *Recently Deleted* folder within that window
- **Send SMS** — Compose new messages or reply inline within a conversation thread
- **Quick Reply from Call Screen** — Supports `RESPOND_VIA_MESSAGE` for declining calls with a text
- **Contact Name Resolution** — Displays contact display names in place of raw numbers when available
- **OLED Dark Theme** — Pure obsidian backgrounds designed for AMOLED power savings

## Screenshots

> _Add screenshots here_

## Architecture

Single-activity app using Jetpack Compose with no navigation library — screens are pure composables swapped in a `when` block based on state:

```
MainActivity
└── SMSAppScreen          ← root state holder
    ├── MainThreadsScreen ← inbox / archive tabs (HorizontalPager)
    ├── ConversationScreen← message thread view
    ├── NewMessageScreen  ← compose new SMS
    └── DeletedThreadsScreen
```

**Key classes:**

| File | Responsibility |
|---|---|
| `MainActivity.kt` | Activity + all composable screens + SMS query logic |
| `SmsReceiver.kt` | BroadcastReceiver — persists incoming SMS, parses OTPs, fires notifications |
| `NotificationActionReceiver.kt` | Handles notification action buttons (Copy OTP, Delete SMS) |
| `MmsReceiver.kt` | WAP push stub (required by default SMS app contract) |
| `HeadlessSmsSendService.kt` | Sends SMS replies from the call decline screen |
| `ArchiveManager.kt` | SharedPreferences-backed archive state |
| `DeleteManager.kt` | SharedPreferences-backed soft deletion with 6-hour TTL |

## Permissions

| Permission | Purpose |
|---|---|
| `RECEIVE_SMS` / `READ_SMS` / `SEND_SMS` | Core SMS functionality |
| `WRITE_SMS` | Required to be the default SMS app |
| `READ_CONTACTS` | Display contact names instead of numbers |
| `POST_NOTIFICATIONS` | Show incoming message notifications |

## Setup

**Prerequisites:** [Android Studio](https://developer.android.com/studio) (Hedgehog or later)

1. Clone the repo
   ```bash
   git clone https://github.com/sk5268/sms_minimal.git
   ```
2. Open the project in Android Studio
3. Let Gradle sync complete
4. Run on a physical device (SMS requires real hardware — emulators cannot receive SMS)
5. Grant all requested permissions and set the app as the **default SMS app** when prompted

### Building a Signed Release APK

Use the included `zz.sh` script — it generates a keystore on first run and builds a signed release APK:

```bash
./zz.sh
```

The script will prompt for keystore credentials interactively on first run, or read them from `KEYSTORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD` environment variables for CI.

## Requirements

- **Min SDK:** 31 (Android 12)
- **Target SDK:** 36
- **Language:** Kotlin
- **UI:** Jetpack Compose + Material 3
