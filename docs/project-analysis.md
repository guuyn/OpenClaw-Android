# OpenClaw-Android Project Analysis

## Overview

**OpenClaw** is an AI-powered Android automation assistant that combines LLM intelligence with device-level control through Android's Accessibility Service. It connects to Aliyun Bailian (阿里百炼) LLM and provides a modular skill system for extended capabilities.

---

## Core Architecture

| Layer | Component | Purpose |
|-------|-----------|---------|
| **UI** | `MainActivity.kt` | Tabbed interface (Chat / Notifications / Settings) built with Jetpack Compose |
| **Agent** | `AgentSession.kt` | Manages conversation context, tool-call parsing, and multi-step reasoning chains |
| **LLM** | `BailianClient.kt` | Connects to Aliyun Bailian LLM via OpenAI-compatible API (default model: MiniMax-M2.5) |
| **Automation** | `MyAccessibilityService.kt` + `AccessibilityBridge.kt` | Screen reading, element clicking, text input, gestures, screenshots — full device control |
| **Background** | `GatewayService.kt` + `GatewayManager.kt` | Foreground service keeping the assistant alive and orchestrating all components |
| **Skills** | `SkillManager` + 8 built-in skills | Modular plugin system for extended capabilities |
| **Notifications** | `SmartNotificationListener.kt` | Monitors, classifies (URGENT/IMPORTANT/NOISE), and stores notifications |

---

## Project Structure

```
OpenClaw-Android/
├── app/src/main/java/ai/openclaw/android/
│   ├── MainActivity.kt              # Main entry point with Compose UI
│   ├── GatewayService.kt            # Foreground service for background operations
│   ├── MyAccessibilityService.kt     # Accessibility service for UI automation
│   ├── ConfigManager.kt             # Configuration management (encrypted storage)
│   ├── LogManager.kt                # Centralized logging system
│   ├── GatewayManager.kt            # Orchestrates all gateway components
│   ├── accessibility/
│   │   └── AccessibilityBridge.kt    # Converts AI tool calls to accessibility commands
│   ├── agent/
│   │   └── AgentSession.kt          # Conversation context and model interaction
│   ├── model/
│   │   ├── Message.kt               # Conversation message models
│   │   ├── Tool.kt                  # Tool/function definitions
│   │   ├── ToolCall.kt             # Incoming tool request models
│   │   ├── ModelResponse.kt        # Model API response structure
│   │   └── BailianClient.kt        # Aliyun Bailian LLM client
│   ├── notification/
│   │   └── SmartNotificationListener.kt  # Notification monitor and classifier
│   ├── skill/
│   │   ├── SkillManager.kt          # Skill registration and execution
│   │   ├── DynamicSkill.kt          # Dynamic skill support
│   │   ├── PermissionManager.kt     # Runtime permission handling
│   │   └── builtin/
│   │       ├── WeatherSkill.kt      # Weather via wttr.in
│   │       ├── TranslateSkill.kt    # Translation via MyMemory API
│   │       ├── MultiSearchSkill.kt  # Web search via SearXNG
│   │       ├── CalendarSkill.kt     # Calendar read/write
│   │       ├── LocationSkill.kt     # GPS and geocoding
│   │       ├── ContactSkill.kt      # Contact search and dialing
│   │       ├── SMSSkill.kt          # SMS send/read
│   │       └── ReminderSkill.kt     # Time-based reminders
│   └── feishu/                      # Feishu integration (planned)
├── app/src/main/res/                # Android resources
├── build.gradle.kts                 # Root build configuration
├── app/build.gradle.kts             # App module build configuration
└── README.md
```

---

## Built-in Skills (8 total)

| Skill | Tools | Backend | API Key Required |
|-------|-------|---------|-----------------|
| **Weather** | `get_weather` | wttr.in | No |
| **Web Search** | `search` | SearXNG meta-engine | No |
| **Translate** | `translate` | MyMemory API | No |
| **Reminder** | `set_reminder`, `list_reminders`, `cancel_reminder` | Android AlarmManager | No |
| **Calendar** | `list_events`, `add_event` | Android Calendar Provider | No |
| **Location** | `get_location`, `get_address`, `search_places` | GPS + OpenStreetMap | No |
| **Contact** | `search_contacts`, `get_contact`, `call_contact` | Android Contacts Provider | No |
| **SMS** | `send_sms`, `read_sms`, `get_unread_sms` | Android SMS API | No |

---

## Accessibility Automation Tools

The AccessibilityBridge exposes these tools to the LLM agent for device control:

- **Interaction**: `click`, `click_by_id`, `long_click`, `input_text`
- **Navigation**: `swipe`, `press_back`, `press_home`
- **Observation**: `read_screen`, `screenshot`, `find_elements`

---

## Tech Stack

- **Language**: Kotlin
- **UI**: Jetpack Compose + Material 3
- **Min SDK**: 29 (Android 10) / **Target SDK**: 35 (Android 15)
- **Networking**: OkHttp 4.12 + Retrofit 2.11
- **Serialization**: Kotlinx Serialization 1.8.0
- **Async**: Kotlinx Coroutines 1.10.1
- **Storage**: EncryptedSharedPreferences (AndroidX Security Crypto)
- **Rich Display**: Custom A2UI card system for weather, location, reminders, etc.
- **Build**: Gradle 8.9, Kotlin 2.1.0, Java 17
- **No native C/C++ code** — pure Kotlin app

---

## Message Flow

```
User input
  → MainActivity
  → AgentSession
  → BailianClient (LLM)
      ↓
  Tool call needed?
  ├── Yes → SkillManager / AccessibilityBridge
  │         → Execute tool
  │         → Format result
  │         → Feed back to LLM (may chain multiple calls)
  │         → A2UI card response
  └── No  → Direct text response
      ↓
  ChatScreen displays result
```

---

## Permissions

| Permission | Purpose |
|-----------|---------|
| `INTERNET` | API calls to LLM and skill backends |
| `ACCESS_NETWORK_STATE` | Network connectivity checks |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC` | Background assistant service |
| `POST_NOTIFICATIONS` | Service status notifications |
| `RECEIVE_BOOT_COMPLETED` | Auto-start on device boot |
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | Location skill |
| `READ_CALENDAR` / `WRITE_CALENDAR` | Calendar skill |
| `READ_CONTACTS` | Contact skill |
| `SEND_SMS` / `READ_SMS` | SMS skill |
| `SCHEDULE_EXACT_ALARM` | Reminder skill |
| `BIND_NOTIFICATION_LISTENER_SERVICE` | Smart notification listener |
| `BIND_ACCESSIBILITY_SERVICE` | UI automation |

---

## Security

- API keys stored using EncryptedSharedPreferences (AndroidX Security Crypto)
- Network security config enforces HTTPS
- No hardcoded secrets in source code
- Runtime permission checks before skill execution

---

## Notable Design Decisions

- **No API keys required** for most skills — uses free public services
- **Chained tool execution** — the agent can chain multiple tool calls (e.g., get location then get weather for that location)
- **Foreground service** ensures the assistant stays alive in the background
- **Modular skill architecture** — easy to add new capabilities as pluggable components
- **Feishu (飞书) integration** directory exists but appears planned/incomplete
- **External `android_compose` module** (A2UI library) referenced from outside the project directory
- **Event-driven architecture** using StateFlow and coroutines for reactive UI updates
