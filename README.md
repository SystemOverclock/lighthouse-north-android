# Lighthouse North SDK for Android

Add push notifications to your Android app with built-in LGPD consent evidence, heartbeat, and easy opt-out.

## Features

- 📱 **Push registration** — device token + consent evidence in one call
- 🔔 **Heartbeat** — keeps device alive on the platform (24h, auto-restart)
- 🚪 **Opt-out** — one method, always free, never throws
- 🛡️ **LGPD by design** — collects only `platform`, `token`, `contactRef`, `consent`. Nothing else
- 🔐 **Android Keystore** — token stored encrypted, never in plaintext SharedPreferences
- 🔒 **No secrets in logs** — tokens are always masked
- 📦 **No Firebase bundled** — uses your app's Firebase (compileOnly)

## Installation

Add JitPack to your `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

Then add the dependency in your app's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.github.SystemOverclock:lighthouse-north-android:v0.2.1")
}
```

## Quick Start

### 1. Get your App Key

In your Lighthouse North dashboard: **Apps → Keys**. The key looks like `ak_...`.

> Keep it in `local.properties` or `BuildConfig`, never committed to version control.

### 2. Initialize

```kotlin
val client = LighthouseNorthClient.getInstance(context)
client.init(
    appKey = "ak_...",
    apiBaseUrl = "https://lighthousenorth.com.br",
)
```

### 3. Register for Push

Call this from a user action (button tap), after Firebase returns a token:

```kotlin
FirebaseMessaging.getInstance().token.addOnSuccessListener { fcmToken ->
    client.registerForPush(
        fcmToken = fcmToken,
        contactRef = "user-123",       // your user ID (optional)
        policyVersion = "2026-09",     // consent policy version
    ) { result ->
        if (result.isSuccess) client.startHeartbeat()
    }
}
```

### 4. Handle Opt-out

Always show an opt-out option in your app settings:

```kotlin
client.optOut { result -> /* device removed */ }
```

## Requirements

- `minSdk 21` · Kotlin 2.1+ · Java 11
- Firebase Cloud Messaging configured in your app

## Privacy

This SDK collects exactly: device platform, FCM token, your contact reference, and consent version. No IP, no user agent, no advertising ID, no app data. Tokens are encrypted with Android Keystore. See our [Privacy Policy](https://lighthousenorth.com.br/privacidade).

## License

MIT
