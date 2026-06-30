# Arirang — Agent Notes

Compact, repo-specific context for OpenCode sessions. When in doubt, trust the Gradle build scripts over prose.

## Project shape

Three Gradle modules in `settings.gradle.kts`:

- `:app` — Xposed/LSPosed module **and** manager UI (`asia.nana7mi.arirang`).
  - Hook entrypoints live under `app/src/main/java/asia/nana7mi/arirang/hook/`.
  - Writes the native submodule config to app files at runtime (`arirang-submodule/config.json`).
- `:arirang-selfcheck` — standalone companion APK (`asia.nana7mi.arirang.selfcheck`), launcher activity is `SelfCheckActivity`.
- `:arirang-submodule` — native Zygisk/DRM fallback layer. Builds with CMake + NDK, produces a Magisk/KernelSU/APatch flashable zip.
  - No Android application plugin; uses the `base` plugin and custom tasks.

## Exact commands

| Goal | Command |
|------|---------|
| Build main app debug APK | `./gradlew :app:assembleDebug` |
| Build self-check debug APK | `./gradlew :arirang-selfcheck:assembleDebug` |
| Build submodule zip | `./gradlew :arirang-submodule:packageModule` → `arirang-submodule/build/outputs/arirang-submodule.zip` |
| Build just native artifacts | `./gradlew :arirang-submodule:buildNative` |
| Install submodule and reboot (Magisk/KSU/APatch auto) | `./gradlew :arirang-submodule:installModuleAndReboot` |
| Install submodule with KernelSU Next | `./gradlew :arirang-submodule:installKernelSuNextAndReboot` |
| Install without reboot | `./gradlew :arirang-submodule:installModule` |
| Install main app debug onto device | `./gradlew :app:installDebug` |
| Run AGP lint | `./gradlew :app:lintDebug :arirang-selfcheck:lintDebug` |

There are currently **no unit or instrumentation tests**; `app:testDebugUnitTest` exists but has nothing to run.

## Toolchain / environment

- Dependencies managed via `gradle/libs.versions.toml` version catalog.
- Gradle wrapper uses **Gradle 9.4.1**.
- AGP 9.2.1, Kotlin 2.3.20, Compose BOM 2026.03.01.
- `compileSdk = 37`, `targetSdk = 36`, `minSdk = 34`.
- Java / Kotlin target: **JVM 17**.
- Native build: **NDK 23.1.7779620**, arm64-v8a, Android API 31.
- NDK lookup order: `ANDROID_NDK_HOME` → `android.ndkpath` Gradle property → `$ANDROID_HOME/ndk/23.1.7779620` → `ndk.dir` in `local.properties`.
- `local.properties` in this checkout sets `sdk.dir=/home/dddqmmx/Android/Sdk`; it is listed in `.gitignore` so other checkouts must recreate it.

## Native submodule details

- CMake requires these defines (the Gradle task injects them automatically):
  - `ARIRANG_APPLICATION_ID=asia.nana7mi.arirang`
  - `ARIRANG_SUBMODULE_CONFIG_DIR=arirang-submodule`
  - `ARIRANG_SUBMODULE_CONFIG_FILE=config.json`
- Built artifacts:
  - `libarirang_zygisk.so` → staged into the zip as `zygisk/arm64-v8a.so`
  - `libarirang_drm_hook.so` → staged as `lib/libarirang_drm_hook.so`
  - `arirang_injector` → staged as `bin/arirang_injector`
- Runtime staging: `post-fs-data.sh` copies the DRM hook `.so` to a tmpfs landing dir at `/dev/.arirang`, bind-mounts it over an unused vendor `.so`, and stages the spoofed `widevineDrmId` from the app config. `service.sh` later ptrace-dlopens it into the Widevine HAL daemon.
- `arirang-submodule/package.sh` is **not maintained for the current build**; it does not pass the required CMake application/config defines and only packages the Zygisk `.so`. Use the Gradle task for normal builds.
- Device install tasks use `adb`; you can target a specific device or root method with:
  - `./gradlew ... -Parirang.device=<serial>` or env `ARIRANG_DEVICE`
  - `./gradlew ... -Parirang.root=magisk|ksu|kernelsu|ap|apatch` or env `ARIRANG_ROOT`

## Hook design constraint (CRITICAL)

- **NEVER inject hooks into arbitrary third-party applications.** Hooks, data interception, and data rewriting must stay inside **system-level components and framework layers**. Violating this degrades app performance and interferes with normal runtime behavior.
- Xposed scope is therefore strictly limited to System/Android framework, `com.android.phone`, and `com.google.android.gms` (defined in `app/src/main/res/values-en/arrays.xml`, `xposedscope`).

## App/self-check quirks

- `:app` is a single APK that is both the Xposed module and the settings UI.
- App locale whitelist (resources shrinker filter): `en`, `zh-rCN`, `ja`.
- `BuildConfig.SUBMODULE_CONFIG_DIR` and `BuildConfig.SUBMODULE_CONFIG_FILE` are generated from root-project extra properties in `build.gradle.kts`. Do not hard-code them in Kotlin without updating the Gradle side too.
- Release minification/shrinking is enabled; `app/proguard-rules.pro` keeps hook classes under `asia.nana7mi.arirang.hook.**` and Xposed API classes.
- `my-release-key.jks` exists at repo root but is **not referenced by the build file**; release signing is handled by CI secrets.
- Known hook code issues are tracked in `HOOK_CODE_REVIEW_TODO.md`. Read it before changing hook logic; it catalogs parcel leaks, cycle risk, type mismatch, and timeout problems found in manual review.

## Verification on device

- Native submodule runtime tags: `ArirangZygisk`, `arirang_service`, `arirang_post_fs_data`, `ArirangDrmHook`.
- To verify the Widevine `deviceUniqueId` path is spoofed, open the self-check app and trigger `MediaDrm.getPropertyByteArray(PROPERTY_DEVICE_UNIQUE_ID)`; confirm logcat reports `spoofed deviceUniqueId byte[]`.
- See `arirang-submodule/doc/drm_hook_research.md` for the current vtable-based DRM hook design and reference-device notes.

## CI

- GitHub Actions workflow at `.github/workflows/android.yml` triggers on tag pushes (`v*`) and `workflow_dispatch`.
- Builds all debug + release APKs and the submodule zip in one job; release APKs are signed via GitHub secrets (`SIGNING_KEY`, `ALIAS`, `KEY_STORE_PASSWORD`, `KEY_PASSWORD`).
- Creates a GitHub release on tag push, attaching all artifacts.

## No automated quality gates yet

- No configured formatter/ktlint/detekt/spotless and no lint baseline.
- No pre-commit hooks.
