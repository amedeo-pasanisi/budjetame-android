# Budjetame (Android)

The most basic Android app in Kotlin, ready to grow into a budget tracker.

Single module, single activity, Jetpack Compose UI, no other dependencies.

## Stack (latest stable, verified 2026-08-31)

| Component        | Version       |
|------------------|---------------|
| Kotlin           | 2.4.10        |
| Android Gradle Plugin | 9.3.2 (built-in Kotlin support — no `kotlin-android` plugin) |
| Gradle           | 9.7.1         |
| compileSdk / targetSdk | 37 (Android 17) |
| minSdk           | 26            |
| JVM target       | 17            |
| Compose BOM      | 2026.08.00    |
| Activity Compose | 1.13.0        |
| Lifecycle        | 2.11.0        |
| Core KTX         | 1.19.0        |

## Project layout

```
app/src/main/java/com/budjetame/android/
├── MainActivity.kt          # Single activity, edge-to-edge, Compose UI
└── ui/theme/Theme.kt        # Material 3 theme (dynamic color on Android 12+)
```

## Requirements

- JDK 17+ (AGP 9.3 minimum)
- Android SDK Platform 37 (`sdkmanager "platforms;android-37"`)

## Build

```bash
# Create local.properties with your SDK path (or set ANDROID_HOME):
#   sdk.dir=/path/to/android-sdk

./gradlew assembleDebug          # build the debug APK
./gradlew test                   # run local unit tests
./gradlew installDebug           # install on a connected device/emulator
```

## Notes

- AGP 9 has **built-in Kotlin**: `org.jetbrains.kotlin.android` is not applied.
  Kotlin compiler options go in the `kotlin { compilerOptions { } }` block.
- The Compose compiler comes from `org.jetbrains.kotlin.plugin.compose`
  (versioned with Kotlin, 2.4.10).
- The launcher icon is a pure vector adaptive icon (no PNG assets).
