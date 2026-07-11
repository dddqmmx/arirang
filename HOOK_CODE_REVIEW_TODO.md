# Hook Code Review TODO

Review scope: `app/src/main/java/asia/nana7mi/arirang/hook/`

Review date: 2026-06-29

## P0 Critical

No P0 issue found in this pass.

## P1 High

- [x] Fix App Set callback `Parcel` corruption and leak.
  - Files: `gms/GmsAppSetHooks.kt:37`, `gms/GmsParcelUtils.kt:24`
  - Impact: `hookCallbackBinderProxy()` reads the original callback `Parcel` to parse `Status`; if any exception happens before `args[1]` is replaced, the original `Parcel` data position is left advanced and the real Binder call continues with corrupted read state. On the success path, the replacement `Parcel` assigned to `args[1]` is never recycled after `BinderProxy.transact` returns.
  - TODO: Make parcel readers restore `dataPosition` in `finally`; track replacement parcels and recycle them in an `afterHookedMethod` callback.

- [x] Add cycle protection to recursive telephony object rewriting.
  - File: `sim/SimTelephonyRewriter.kt:74`
  - Impact: `rewriteNestedTelephonyObject()` recurses through lists, maps, arrays, and then all declared fields of telephony objects without a visited set. A cyclic object graph can cause `StackOverflowError` in `com.android.phone` or `system_server`.
  - TODO: Pass an identity-based visited set through recursive calls and cap recursion depth.

- [x] Stop returning `List` for Bluetooth APIs that may declare `Set`.
  - Files: `bluetooth/BluetoothAdapterHooks.kt:45`, `bluetooth/BluetoothAdapterHooks.kt:128`, `bluetooth/BluetoothMethodMatchers.kt:8`
  - Impact: `returnsListOfBluetoothDevice()` intentionally matches both `List` and `Set`, but the hook returns `List` for non-array collections. If the real method return type is `Set`, Xposed will hand callers an incompatible value and can crash the Bluetooth service or client unmarshalling path.
  - TODO: Branch on `method.returnType`; return `LinkedHashSet`/`emptySet` for `Set`, `List` for `List`, and arrays for array methods.

- [x] Add client-side timeout or async handling for clipboard permission requests.
  - Files: `clipboard/FuckClipboard.kt:61`, `core/ArirangClient.kt:237`
  - Impact: `ClipboardService.getPrimaryClip()` performs a synchronous Binder call into the manager app. `getOrBindService()` has a 300 ms bind wait, but `service.requestClipboardRead()` has no client-side timeout. If the app service is stuck, a system_server Binder thread can block indefinitely.
  - TODO: Move the prompt flow off the hot Binder path or use a bounded request with fail policy; keep the ANR exemption state valid until the bounded operation really finishes.

- [x] Fix SettingsProvider Bluetooth fallback prefs name.
  - File: `settings/FuckSettingsProvider.kt:136`
  - Impact: the fallback reads `"bluetooth_config"`, but the actual prefs file is `BluetoothConfigPrefs.PREFS_NAME` (`"bluetooth_config_prefs"`). If realtime snapshot binding is unavailable, SettingsProvider will not spoof `bluetooth_name`/`device_name` even when Bluetooth spoofing is enabled.
  - TODO: Import and use `BluetoothConfigPrefs.PREFS_NAME`.

## P2 Medium

- [x] Make Wi-Fi scan-result wrapping fail closed to the original result, not `null`.
  - Files: `wifi/WifiServiceHooks.kt:147`, `wifi/WifiMethodMatchers.kt:6`, `wifi/WifiSpoofing.kt:43`
  - Impact: `wrapScanResults()` can return `null` for `ParceledListSlice` return types if class lookup or construction fails, and the hook assigns that `null` as the API result. This can break callers expecting a non-null slice.
  - TODO: Build the replacement inside `runCatching`; if wrapping fails, leave the original method result unchanged or return an empty correctly typed slice.

- [x] Narrow GMS Advertising ID string getter hooks.
  - File: `gms/GmsAdvertisingIdHooks.kt:27`
  - Impact: every no-arg `String` method on the Advertising ID service implementation is replaced with GAID. That can corrupt unrelated service methods if the implementation class contains other string getters.
  - TODO: Prefer Binder transaction-level spoofing or match by method name/signature observed for the target GMS versions.

- [x] Add visited-set protection to location caller resolution.
  - File: `location/LocationCallerResolver.kt:43`
  - Impact: `packageNameFromObject()` recursively follows many fields/methods without cycle detection. Complex GMS/framework objects with back-references can recurse until stack overflow.
  - TODO: Use an identity visited set and a small max depth.

- [x] Resolve per-package location profiles for shared UIDs more deterministically.
  - File: `location/LocationCallerResolver.kt:33`
  - Impact: `getPackagesForUid(uid)?.firstOrNull()` can choose the wrong package for shared UID apps, causing the wrong per-package location profile.
  - TODO: Prefer package identity from call args/attribution/work source; when only UID is available, evaluate all packages or avoid applying per-package overrides.

- [x] Remove sensitive identifiers from logs or gate them behind debug-only logging.
  - Files: `sim/FuckSim.kt:928`, `sim/SimHookConfigStore.kt:120`, `packagelist/FuckPackageList.kt:162`, `packagelist/FuckPackageList.kt:267`, `packagelist/FuckPackageList.kt:310`, `packagelist/FuckPackageList.kt:375`, `core/HookLog.kt:11`
  - Impact: SIM TAC values, package visibility decisions, package names, operator data, and config state are logged by default or via direct `XposedBridge.log`, bypassing module log settings in several places.
  - TODO: Use `HookLog.d` for sensitive diagnostics, default release logging to minimal, mask identifiers consistently, and replace direct `XposedBridge.log` calls.

- [x] Avoid forcing config reload on every telephony system-property read.
  - File: `sim/SimSystemPropertyHooks.kt:190`
  - Impact: `systemPropertyOverride()` calls `configStore.current(force = true)` for every matched `SystemProperties.get`. This bypasses the 300 ms cache and can add Binder/XML reads to a hot property path.
  - TODO: Use cached config for reads; reserve `force=true` for explicit refresh events.

- [x] Do not clear the global Arirang service for every config read failure.
  - File: `core/ArirangClient.kt:331`
  - Impact: any exception while reading one config snapshot sets the shared `sService` to `null`, affecting unrelated modules and increasing rebind churn.
  - TODO: Only clear on `DeadObjectException`/binder death; keep service for parse/config-level failures.

- [x] Review SIM slot normalization semantics.
  - File: `sim/SimHookConfigStore.kt:279`
  - Impact: `normalizeProfiles()` compacts sorted configured slots to `0..N`, so a profile keyed to physical slot `1` can become slot `0`. This can spoof the wrong SIM slot when users configure sparse or intentionally hidden slots.
  - TODO: Preserve configured slot indexes unless there is a separate explicit UI model for compaction.

- [x] Validate configured Wi-Fi/Bluetooth addresses before using them in framework objects.
  - Files: `wifi/WifiConfigStore.kt:93`, `bluetooth/BluetoothConfigStore.kt:88`, `bluetooth/BluetoothDeviceFactory.kt:13`
  - Impact: malformed BSSID/MAC entries can lead to failed fake-device creation or inconsistent scan/connection data.
  - TODO: Validate MAC/BSSID format at parse time and drop invalid entries with a warning.

## P3 Low

- [x] Remove or wire the unreachable app package branch in location hook.
  - File: `location/FuckLocation.kt:113`
  - Impact: `onHook()` contains a `BuildConfig.APPLICATION_ID` branch, but `BaseHookModule` target packages for `FuckLocation` do not include the app package and `matchClient` is false. The branch cannot run.
  - TODO: Remove it or add an explicit target/match flag if app-process location hooks are intended.

- [x] Revisit template inheritance mode handling in package-list visibility.
  - File: `packagelist/PackageListHookConfig.kt:152`
  - Impact: `resolvedTemplatePackages()` returns the blacklist/whitelist mode from only the starting template, while package sets are accumulated from parents. Mixed-mode parent templates may behave unexpectedly.
  - TODO: Define inheritance semantics explicitly and encode them in the resolver.

- [x] Replace hard-coded Bluetooth MAC constants with config or documented policy.
  - Files: `bluetooth/BluetoothConstants.kt:8`, `system/SystemServerHook.kt:157`
  - Impact: local Bluetooth address spoofing always returns `02:00:00:AA:BB:CC`, independent of user config.
  - TODO: Either expose it in config or document that the local adapter address is intentionally fixed.

- [x] Clean up debug scaffolding constants.
  - Files: `wifi/WifiConfigStore.kt:24`, `gms/GmsIdentifierConfig.kt:78`, `location/FuckLocation.kt:35`
  - Impact: disabled hard-coded configs remain in production hook code and increase the chance of accidental enablement in future edits.
  - TODO: Move debug-only config behind build-time debug code or remove it.

## Reviewed Files

Reviewed in sorted path order:

- [x] `activation/XposedActivation.kt`
- [x] `bluetooth/BluetoothAdapterHooks.kt`
- [x] `bluetooth/BluetoothConfigStore.kt`
- [x] `bluetooth/BluetoothConstants.kt`
- [x] `bluetooth/BluetoothDeviceFactory.kt`
- [x] `bluetooth/BluetoothMethodMatchers.kt`
- [x] `bluetooth/BluetoothScanHooks.kt`
- [x] `bluetooth/FuckBluetooth.kt`
- [x] `clipboard/FuckClipboard.kt`
- [x] `core/ArirangClient.kt`
- [x] `core/BaseHookModule.kt`
- [x] `core/HookConfig.kt`
- [x] `core/HookConfigFile.kt`
- [x] `core/HookLog.kt`
- [x] `core/HookManager.kt`
- [x] `core/HookModule.kt`
- [x] `core/RealtimeHookConfig.kt`
- [x] `gms/FuckGms.kt`
- [x] `gms/GmsAdvertisingIdHooks.kt`
- [x] `gms/GmsAppSetHooks.kt`
- [x] `gms/GmsIdentifierConfig.kt`
- [x] `gms/GmsParcelUtils.kt`
- [x] `gms/GmsReflection.kt`
- [x] `location/FuckLocation.kt`
- [x] `location/LocationCallerResolver.kt`
- [x] `location/LocationHookConfig.kt`
- [x] `location/LocationSpoofing.kt`
- [x] `packagelist/FuckPackageList.kt`
- [x] `packagelist/PackageListHookConfig.kt`
- [x] `process/FuckProcess.kt`
- [x] `settings/FuckSettingsProvider.kt`
- [x] `sim/FuckSim.kt`
- [x] `sim/SimHookConfigStore.kt`
- [x] `sim/SimHookModels.kt`
- [x] `sim/SimSubscriptionFactory.kt`
- [x] `sim/SimSystemPropertyHooks.kt`
- [x] `sim/SimTelephonyRewriter.kt`
- [x] `system/SystemServerHook.kt`
- [x] `util/HookJson.kt`
- [x] `util/HookReflection.kt`
- [x] `wifi/FuckWifi.kt`
- [x] `wifi/WifiConfigStore.kt`
- [x] `wifi/WifiConstants.kt`
- [x] `wifi/WifiMethodMatchers.kt`
- [x] `wifi/WifiServiceHooks.kt`
- [x] `wifi/WifiSpoofing.kt`
- [x] `wifi/WifiSystemServiceHooks.kt`
