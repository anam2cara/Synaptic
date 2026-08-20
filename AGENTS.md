# Repository Guidelines

## Project Structure & Module Organization

This is a multi-module Android application organized into several sub-projects:

- `:app` — The main entry point and UI layer.
- `:core` — Shared logic, database (Room), and security (SecurePrefs).
- `:llm` — LLM management and JNI bridge for GGUF inference.
- `:monitor` — System monitoring and logcat utilities.
- `:tools` — Agentic tool execution (Shell, Shizuku, Screen reading).

### Feature Grouping (in `:app`)
Kotlin sources are located in `app/src/main/java/com/synaptic/ai/`, grouped by:
- `ui/` — Chat, Dashboard, Shell, and Tools screens.
- `data/`, `llm/`, `tools/` — Integration points for other modules.

### Resources & Native Code
- Android resources: `app/src/main/res/`.
- Native JNI/C++ bridge: `llm/src/main/cpp/`.
- Packaged native libraries: `app/libs/arm64-v8a/`.
- Static assets: `app/src/main/assets/`.

Keep new code within the existing module structure. Use `:core` for data models and shared utilities.

## Build, Test, and Development Commands

Run commands from the repository root on Windows:

- `./gradlew.bat :app:assembleDebug` — builds the debug APK.
- `./gradlew.bat :app:installDebug` — builds and installs the debug app on a connected device.
- `./gradlew.bat :app:testDebugUnitTest` — runs local JVM unit tests.
- `./gradlew.bat :app:connectedDebugAndroidTest` — runs instrumented tests on a device/emulator.
- `./gradlew.bat clean` — removes Gradle build output before a clean rebuild.

The app targets API 34, supports API 26+, and currently builds native code only for `arm64-v8a`.

## Coding Style & Naming Conventions

Use Kotlin with four-space indentation and idiomatic Android/Kotlin style. Use `PascalCase` for classes, composables, and files containing a primary class (for example, `ChatScreen.kt`); use `camelCase` for functions, properties, and variables. Keep Compose screens in `ui/` and name state holders `*ViewModel`. Preserve package names under `com.synaptic.ai`.

Follow existing Gradle Groovy formatting in `*.gradle` files. Avoid unrelated formatting changes, especially in JNI headers and vendored native libraries.

## Testing Guidelines

JUnit 4 is configured for local tests, and AndroidX JUnit/Espresso are configured for instrumentation tests. Add local tests under `app/src/test/` and device tests under `app/src/androidTest/`; name test classes `*Test` and test methods for the behavior being verified. No coverage threshold is currently enforced. Run the relevant Gradle test task before submitting changes.

## Commit & Pull Request Guidelines

The available history uses short, descriptive checkpoint-style commit subjects, often in Indonesian. Write concise imperative subjects that identify the change, such as `Fix: stabilize CPU inference` or `Add chat persistence`. Keep commits focused.

Pull requests should explain the user-visible and technical impact, link relevant issues when available, list validation commands, and include screenshots or recordings for UI changes. Explicitly call out changes to permissions, native libraries, model packaging, or device-specific behavior.
