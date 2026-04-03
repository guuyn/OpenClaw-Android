# OpenClaw-Android

OpenClaw Android client for AI-powered mobile automation.

## Project Structure

```
app/src/main/java/ai/openclaw/android/
├── MainActivity.kt           # Main entry point
├── GatewayService.kt         # OpenClaw gateway communication
├── MyAccessibilityService.kt # UI automation service
├── feishu/                   # Feishu integration
├── model/                    # Data models
├── agent/                    # Agent logic
├── accessibility/            # Accessibility utilities
└── skills/                   # Skill implementations
```

## Requirements

- Android SDK 35 (Android 16)
- Kotlin 1.9.25
- Gradle 8.9

## Build

```bash
./gradlew assembleDebug
```

## License

MIT