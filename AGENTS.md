# Contributor Guide

## Project Structure
- Root Gradle config lives in `build.gradle.kts`, `settings.gradle.kts`, and `gradle.properties`.
- Android app module is in `app/`.
- Kotlin source is in `app/src/main/java/com/youtubetowatch/`:
  - `MainActivity.kt` handles setup UI and toggle state.
  - `YouTubeDetectorService.kt` contains AccessibilityService detection and redirect logic.
- Resources are in `app/src/main/res/` (`layout/`, `drawable/`, `values/`, `xml/`).
- App manifest is `app/src/main/AndroidManifest.xml`.
- Generated outputs under `app/build/` are build artifacts and should not be edited or committed.

## Build and Test Commands
- `./gradlew assembleDebug`: Build a debug APK.
- `./gradlew installDebug`: Install debug build to a connected device/emulator.
- `./gradlew lint`: Run Android lint checks.
- `./gradlew test`: Run local JVM unit tests (if present).
- `./gradlew connectedAndroidTest`: Run instrumentation tests on device/emulator.
- `./gradlew clean`: Clean build outputs when diagnosing build issues.

## Style Guidelines
- Language stack is Kotlin + Android XML.
- Use 4-space indentation and keep methods short, readable, and single-purpose.
- Keep constants and preference keys in `companion object` blocks.
- Prefer descriptive names for state/control flags (for example cooldown, debounce, scan limits).
- Put user-facing strings in `app/src/main/res/values/strings.xml` for consistency and localization readiness.
- Avoid expensive work inside accessibility callbacks; preserve existing debounce/throttle patterns.

## Testing Expectations
- Add unit tests in `app/src/test/` for pure logic and helper behavior.
- Add instrumentation tests in `app/src/androidTest/` for Android/framework interactions.
- For redirect behavior changes, include manual verification notes:
  - accessibility enabled/disabled states,
  - redirect toggle behavior,
  - no redirect loop when already on Watch Later.
- Run `lint`, `test`, and a debug build before opening a PR.

## PR and Commit Guidelines
- Keep PRs focused to one logical change.
- Include a clear description of what changed, why, and how it was validated.
- Attach screenshots/video for UI changes and steps for reproducing behavior changes.
- Use clear, imperative commit messages (for example: `Prevent redirect when already on Watch Later`).
- Avoid mixing refactors with functional changes unless necessary.
