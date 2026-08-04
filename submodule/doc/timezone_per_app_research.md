# Per-App Time Zone Spoofing — Research Notes

> **Device under test:** Xiaomi 22081212C (`diting`), Android 16 (API 36), KernelSU (`u:r:ksu:s0`)  
> **Root stack:** ReZygisk v1.0.0 + LSPosed v2.1.1 + `arirang-submodule`  
> **Date:** 2026-07-31  
> **Constraint (hard):** no long-lived injection into ordinary third-party apps; Zygisk must `DLCLOSE_MODULE_LIBRARY` out of them (same rule as sensor spoofer / `arirang_zygisk.cpp`). No third-party app-scope Xposed hooks.

Related product surface already exists in the manager:

- Config: `SystemSettingPrefs` / `system_setting` snapshot (`timeZoneId` + `perPackage`)
- Locale half: `FuckAppLocale` (system_server `LocaleManagerService`)
- Time zone half: **not implemented** (this document)

---

## 1. Goal

Give package A timezone `Europe/London`, package B `America/New_York`, leave the real device on `Asia/Shanghai`, without:

1. LSPosed scope on ordinary apps
2. Zygisk `.so` remaining mapped in ordinary apps
3. Changing the global device timezone for SystemUI / Settings / alarms

---

## 2. How Android resolves “default time zone”

### 2.1 Canonical path (libcore)

```
RuntimeInit.commonInit()
  └─ RuntimeHooks.setTimeZoneIdSupplier(
         () -> SystemProperties.get("persist.sys.timezone"))

TimeZone.getDefault()
  └─ getDefaultRef()                    // process-local static
       if defaultTimeZone == null:
         id = RuntimeHooks.getTimeZoneIdSupplier().get()
         defaultTimeZone = TimeZone.getTimeZone(id)
       return defaultTimeZone
```

Sources (PixelOS / AOSP trees on this machine):

| Piece | Path |
|-------|------|
| Supplier install | `frameworks/base/.../RuntimeInit.java` (`setTimeZoneIdSupplier`) |
| Supplier storage | `libcore/.../dalvik/system/RuntimeHooks.java` |
| Cache + getDefault | `libcore/.../java/util/TimeZone.java` (`getDefaultRef` / `setDefault`) |
| Property write | `SystemTimeZone` → `persist.sys.timezone` |
| Set TZ entry | `AlarmManagerService.setTimeZoneImpl` |

Verified on device with `app_process` + dex:

```
1 default=Asia/Shanghai
1 prop=Asia/Shanghai
2 after setDefault(null) → re-read prop → Asia/Shanghai
3 supplier already set; re-set throws UnsupportedOperationException
5 setDefault(America/New_York) → process-local only
6 setDefault(null) again → back to prop
```

Also verified: temporarily `setprop persist.sys.timezone Asia/Tokyo` makes a **new** process report Tokyo; restoring the prop restores Shanghai. Property is live; default cache is process-local.

### 2.2 Downstream consumers (all in-process)

| API | Behavior |
|-----|----------|
| `java.util.TimeZone.getDefault()` | Process cache → supplier → prop |
| `Calendar.getInstance()` / `SimpleDateFormat` | Uses default TZ |
| `java.time.ZoneId.systemDefault()` | `TimeZone.getDefaultRef()` |
| `android.icu.util.TimeZone.getDefault()` | Follows `java.util.TimeZone` (then freezes ICU copy) |
| `SystemProperties.get("persist.sys.timezone")` | Direct shared property area read (bypasses Java cache) |
| `Configuration` | **No timezone field** (confirmed via reflection on device) |

### 2.3 Global timezone change fan-out

```
AlarmManager.setTimeZone / detector
  → SystemTimeZone.setTimeZoneId (writes persist.sys.timezone)
  → TimeZone.setDefault(null)                 // system_server only
  → sendBroadcast(ACTION_TIMEZONE_CHANGED)
       BroadcastController
         → AMS.UPDATE_TIME_ZONE
              → for each running app: IApplicationThread.updateTimeZone()
                   ActivityThread.ApplicationThread.updateTimeZone()
                     → TimeZone.setDefault(null)   // clear per-process cache
```

After clear, the **next** `getDefault()` re-reads the **global** property. There is **no** binder API that pushes a non-null default timezone into an app process—only “clear cache”.

`bindApplication` also does `TimeZone.setDefault(null)` once so a freshly specialized process picks up the current system zone.

### 2.4 Property storage

`/dev/__properties__` is a global shared memory area, not per-app mount-namespaced. You cannot give one app a different `persist.sys.timezone` via mount namespaces the way some modules hide Magisk files.

---

## 3. Why this is not like sensor spoofer

| | **Sensor spoofer (done)** | **Default time zone** |
|--|---------------------------|------------------------|
| App entry | `SensorManager` → Binder | `TimeZone.getDefault()` in-process |
| Data owner | `SensorService` in **system_server** | libcore static + **shared** prop |
| Per-app key | Binder calling identity / `opPackageName` | **None** (no IPC) |
| Hook site that stays out of apps | system_server vtable / onTransact | **Does not exist** for getDefault |
| Zygisk residence | system_server (+ phone) only; apps `DLCLOSE` | Pure system_server **cannot** rewrite app-local static |

**Conclusion:** a pure “hook the service in system_server, apps stay clean” design **cannot** cover `TimeZone.getDefault()` / `ZoneId.systemDefault()` / ICU default. That is a structural platform fact, not an implementation gap.

Locale was solvable in system_server (`LocaleManagerService`) because Android 13+ already has per-package locale plumbing. Time zone has no equivalent.

---

## 4. Attack surface map (what apps actually call)

```
                    ┌─────────────────────────────────────┐
                    │     persist.sys.timezone (global)    │
                    └──────────────┬──────────────────────┘
                                   │ __system_property_* / JNI native_get
                                   ▼
                    ┌─────────────────────────────────────┐
                    │ RuntimeHooks.zoneIdSupplier (once)   │
                    └──────────────┬──────────────────────┘
                                   │ only when cache null
                                   ▼
                    ┌─────────────────────────────────────┐
                    │ TimeZone.defaultTimeZone (per-proc)  │◄── setDefault / updateTimeZone
                    └──────────────┬──────────────────────┘
                                   │
            ┌──────────────────────┼──────────────────────┐
            ▼                      ▼                      ▼
   java.util / Calendar    java.time.ZoneId      android.icu.TimeZone
            │                      │                      │
            └──────────────────────┴──────────────────────┘
                                   │
                         Most app date/time UI

Bypass: SystemProperties.get("persist.sys.timezone")  →  never hits Java cache
Bypass: explicit TimeZone.getTimeZone("Asia/Shanghai") →  not "default"
Bypass: server-side time / network →  out of scope
```

Coverage tiers:

1. **Tier A (must):** `TimeZone.getDefault` / `getDefaultRef` / `setDefault(null)` recovery  
2. **Tier B (should):** ICU default + `ZoneId.systemDefault` (usually follow Tier A if cache is right)  
3. **Tier C (hard apps):** direct `SystemProperties.get("persist.sys.timezone")`  
4. **Out of scope:** wall clock (`currentTimeMillis`), NTP, app-private offsets

---

## 5. Candidate designs (evaluated)

### 5.1 ❌ LSPosed / Xposed hooks inside target apps

Hook `TimeZone.getDefault` in every scoped app.

- Works technically  
- **Violates** project rule and current `xposedscope` (`android`, phone, GMS, … only)  
- Rejected

### 5.2 ❌ Global `setprop persist.sys.timezone`

- One zone for the whole device  
- Breaks SystemUI clock, alarms, auto-TZ detector  
- Not per-app  
- Rejected for the product goal

### 5.3 ❌ system_server-only Java hooks (AlarmManager / Settings / AMS)

Useful only for:

- Observing global TZ changes  
- Optionally suppressing or rewriting **broadcast extras** (`Intent.EXTRA_TIMEZONE`) for some receivers  

Does **not** change `TimeZone.getDefault()` inside apps.  
Can be a **helper**, never the main mechanism.

### 5.4 ✅ Specialize-time seed + immediate `DLCLOSE` (minimal Zygisk contact)

**Idea:** In `preAppSpecialize` / `postAppSpecialize`, if the specializing package has a timezone override:

1. Load config (disk / companion)  
2. JNI: `TimeZone.setDefault(TimeZone.getTimeZone(id))`  
3. JNI: ensure ICU follows (`ExtendedTimeZone.clearDefaultTimeZone` / equivalent after setDefault)  
4. `api->setOption(DLCLOSE_MODULE_LIBRARY)`  

After unload, **no Arirang `.so`** is mapped. Only the process-local Java static remains—same as if the app had called `setDefault` itself.

**Device proof:** `setDefault` is process-local; a second `app_process` still sees Shanghai after another process forced New York.

**Pros**

- Matches “do not keep Zygisk in ordinary apps”  
- No Xposed app scope  
- Covers Tier A/B until something clears the cache  
- Small code surface  

**Cons / failure modes**

| Event | Effect |
|-------|--------|
| `updateTimeZone()` / `ACTION_TIMEZONE_CHANGED` | Cache cleared → falls back to **real** prop |
| App calls `TimeZone.setDefault(null)` | Same |
| App reads `SystemProperties.get("persist.sys.timezone")` | Sees real zone (Tier C miss) |
| Multi-process app (`:push`, `:webview`) | Each process needs its own seed at specialize |
| Config change while app running | No re-apply until process restart (unless extra mechanism) |

**Compliance note:** Zygisk still **enters** the process for specialize callbacks (unavoidable with Zygisk). The project already accepts that for the phone process and for `DLCLOSE` on everyone else. This approach only adds a few JNI calls before the existing unload. It does **not** leave the module resident.

### 5.5 ✅ Property virtualization with out-of-module trampoline (robust, gray)

**Idea (sensor-adjacent native technique, but in the app process briefly):**

1. At specialize (or once in zygote with per-process state):  
   - Allocate anonymous RW/RX pages **not** backed by the module `.so`  
   - Install inline/PLT hook on `__system_property_get` / `__system_property_read_callback` / JNI `SystemProperties.native_get`  
   - When key == `persist.sys.timezone` (and optionally confidence key), return per-process override string stored in the anon page  
2. `TimeZone.setDefault(null)` so next getDefault uses supplier → hooked property  
3. `DLCLOSE_MODULE_LIBRARY` — trampoline + override string remain  

**Pros**

- Survives `updateTimeZone` / cache clears  
- Covers Tier C (direct property reads)  
- Still no long-lived module mapping  

**Cons**

- Native code still **executes inside** ordinary app processes on property read (detection surface larger than pure Java static)  
- Must not break other property reads; keep filter extremely narrow  
- Inline hooks are the same class of risk documented in `hook_file_design.md` / DRM notes  
- Philosophically stricter reading of “no inject” may still reject this  

**Recommendation:** treat as **Phase 2** if Phase 1 (seed-only) is insufficient for target apps.

### 5.6 ⚠️ Zygote-global property hook + PID/UID table

Hook once in zygote; all children inherit. Override table keyed by UID filled at specialize.

- Efficient  
- Higher blast radius (every property get path)  
- Harder to reason about denylist / isolated processes  
- Not preferred over per-process install-at-specialize  

### 5.7 ❌ Replace `RuntimeHooks` supplier per app

`setTimeZoneIdSupplier` throws if already set. Cannot replace after `RuntimeInit`. Rejected.

### 5.8 ❌ Per-app mount / property namespace

Property area is global. Not viable on stock KSU device without kernel changes.

---

## 6. Recommended architecture for Arirang

### Phase 0 — Product truth

UI/config already allow per-package `timeZoneId`. Document that **time zone cannot be pure system_server** the way sensors/locale (partially) can. Implementation will use **specialize-time process state**, not app-scoped Xposed.

### Phase 1 — Ship (specialize seed + DLCLOSE)

**Native (`arirang-submodule`):**

```
preAppSpecialize:
  resolve nice_name / package
  if ordinary app:
    ensure_config_loaded()           // only if package has TZ rule
    if has timeZoneId override:
      jni_seed_default_timezone(env, id)   // TimeZone.setDefault(...)
      mark seeded
    api->DLCLOSE_MODULE_LIBRARY      // ALWAYS for non-phone (unchanged policy)
  if phone: keep as today

postAppSpecialize (phone only): existing SIM/property hooks
pre/postServerSpecialize: existing sensor / build hooks; optional TZ broadcast helper
```

**Config plumbing:**

- Extend submodule `config.json` with system-setting timezone map  
  (`package → timeZoneId`, plus global default), generated from existing `SystemSettingPrefs.buildHookSnapshot` / submodule writer (today system setting is explicitly “nothing reaches native”—that comment must change).

**Java LSPosed (system_server only, optional companion):**

- On `ACTION_TIMEZONE_CHANGED` / `UPDATE_TIME_ZONE`, log or best-effort **kill** only apps that need re-seed? Too aggressive.  
- Better: document “reboot or force-stop app after global TZ change” for Phase 1.  
- Or: AMS hook that after `thread.updateTimeZone()`, we cannot set remote default—skip.

**Self-check:**

- Extend selfcheck with `TimeZone.getDefault().getID()`, `ZoneId.systemDefault()`, `SystemProperties` reflect, formatters.

### Phase 2 — If apps bypass or TZ changes break seed

Implement **narrow** `__system_property_read_callback` / `native_get` virtualization for `persist.sys.timezone` only, trampoline outside module, per-process override set at specialize, then DLCLOSE.

Reuse `inline_hook` carefully; prefer hooking JNI `SystemProperties_getSS` in `libandroid_runtime` **from specialize** only in processes that need spoofing (smaller than zygote-global).

### Phase 3 — Do not do

- App-scope LSPosed for timezone  
- Leaving Zygisk mapped in Chrome/WeChat/etc.  
- Global resetprop of `persist.sys.timezone` as the per-app mechanism  

---

## 7. Comparison with existing Arirang modules

| Feature | Where it hooks | Per-app? | App-resident? |
|---------|----------------|----------|---------------|
| Sensor list/events | system_server `libsensor` | via service path | No |
| Location | system_server / fused / GMS | Binder caller | No (GMS is scoped system-ish) |
| SIM / IMEI | phone + properties | mostly global/slot | phone only |
| Build props | resetprop + system_server/phone native | global | No ordinary apps |
| Locale | system_server `LocaleManagerService` | package argument | No |
| **Time zone (proposed P1)** | **specialize JNI seed** | **package at specialize** | **No (DLCLOSE)** |
| **Time zone (proposed P2)** | **property read trampoline** | **per-process override** | **No .so; yes native stub** |

---

## 8. Implementation checklist (when coding)

1. [ ] Submodule config schema: `system_setting_enabled`, `time_zone_global`, `time_zone_by_package{}`  
2. [ ] App writes those fields into `arirang-submodule/config.json` (update “no native write” comment in `SystemSettingPrefs`)  
3. [ ] `jni_seed_default_timezone` helper next to `spoof_build_fields`  
4. [ ] `preAppSpecialize`: seed + always DLCLOSE for non-phone  
5. [ ] Do **not** enable supplier replacement  
6. [ ] Multi-process: rely on Zygisk per-specialize (each process name may differ—match by UID→packages list if needed)  
7. [ ] Isolated services / Instant apps: define policy (usually skip)  
8. [ ] Selfcheck + manual: install test APK reading all three tiers  
9. [ ] On global TZ change: Phase 1 known gap; Phase 2 closes it  
10. [ ] Never add ordinary packages to `xposedscope` for this feature  

---

## 9. Device experiments log

| # | Experiment | Result |
|---|------------|--------|
| 1 | `getprop persist.sys.timezone` | `Asia/Shanghai` |
| 2 | `app_process` TimeZone.getDefault | `Asia/Shanghai` |
| 3 | `TimeZone.setDefault(Tokyo)` in one process | Only that process; static field shows Tokyo |
| 4 | `setDefault(null)` | Re-fetches supplier → prop |
| 5 | Re-call `RuntimeHooks.setTimeZoneIdSupplier` | `UnsupportedOperationException: already set` |
| 6 | Temporary `setprop` to Tokyo + new process | Default becomes Tokyo; restored Shanghai |
| 7 | `Configuration` fields | No timezone member |
| 8 | Second process after first forced NY | Still Shanghai (process-local) |
| 9 | Property FS | `/dev/__properties__` global SELinux-labeled nodes |
| 10 | IAlarmManager | `setTimeZone` only; no getDefault API |

Artifacts used: `/data/local/tmp/tztest.dex`, `tz_revalidate.dex`, host sources under `pixelos/libcore`, `project/android-source/frameworks_base`.

---

## 10. Superseded recommendation (kept for history)

Earlier draft recommended “JNI `setDefault` seed + optional property trampoline.” That is **insufficient** under a Truman-world bar: any path that leaves Java cache ≠ property, or leaves inline hooks / module code in the app, is detectable. See §12 for the revised final design.

---

## 11. Open questions (updated)

1. Config hot-reload without process death: not required for Truman; force-stop is OK.  
2. Shared UID: one zone per UID (platform already merges).  
3. ~~Trampoline acceptability~~ → **rejected** as primary; CoW property area replaces it.  
4. WebView/Chrome multi-process: each specialize must CoW independently.  
5. maps `r--s` → `r--p` residual: optional camouflage via mount-ns bind (§12.6).  

---

## 12. Truman-world final design (authoritative)

> **Bar:** From inside a spoofed app, every legitimate observation of “what is the device time zone?” must agree. No residual module, no PLT/inline hook, no Java-only lie that disagrees with properties. Other apps and the real system keep the true zone. Zygisk must `DLCLOSE` out of ordinary apps.

### 12.1 Why previous options fail the bar

| Scheme | App-visible crack |
|--------|-------------------|
| Only `TimeZone.setDefault(spoof)` | `SystemProperties.get("persist.sys.timezone")` still real; after `updateTimeZone` / `setDefault(null)` reverts to real |
| Global `setprop` | SystemUI/alarms/other apps all change; not per-app |
| Inline hook on `__system_property_*` left after DLCLOSE | Executable trampoline in maps; checksum / `/proc/self/mem` prologue compare; “something hooked libc” |
| App-scope Xposed | Module classloader, stack traces, LSPosed artifacts |
| system_server-only | Never reached by `getDefault` / property read |

### 12.2 Detection surface map (what “楚门” must unify)

```
App process observations that MUST all return spoofed id S:

  A. java.util.TimeZone.getDefault().getID()
  B. java.time.ZoneId.systemDefault().getId()
  C. android.icu.util.TimeZone.getDefault().getID()
  D. Calendar / DateFormat / SimpleDateFormat default zone
  E. SystemProperties.get("persist.sys.timezone")          // JNI → bionic
  F. __system_property_get / find+read_callback / foreach  // direct bionic
  G. Reading the bytes behind the mapped timezone_prop     // same VMA after CoW
  H. After ACTION_TIMEZONE_CHANGED → updateTimeZone()
       → setDefault(null) → getDefault() again             // must still be S

Must NOT change (outside this process):

  I. Other processes' getprop / getDefault
  J. Real /dev/__properties__/u:object_r:timezone_prop:s0 content on disk/shm
  K. SystemUI clock, AlarmManager, Settings
```

**Device proof (2026-07-31, diting, root):** process-private `MAP_PRIVATE` remap of `timezone_prop`, patch value `Asia/Shanghai` → `Europe/London` (same length) and → `America/New_York` (different length + serial length field):

| Check | Result |
|-------|--------|
| `__system_property_get` in that process | spoofed |
| `read_callback` | spoofed |
| Concurrent `getprop` in shell | still `Asia/Shanghai` |
| Direct open+read of prop **file** from same process | still sees real bytes on the *file* (separate from VMA) — see §12.5 |
| maps line | path unchanged; flag `r--s` → `r--p` |

### 12.3 Core mechanism: per-process property-area CoW

Android maps each SELinux property context file with **`mmap(..., PROT_READ, MAP_SHARED, fd, 0)`** (`prop_area::map_fd_ro`). All processes share the same pages for `u:object_r:timezone_prop:s0` (holds `persist.sys.timezone`).

**At `postAppSpecialize` (only if this package/UID has a timezone override):**

1. Parse `/proc/self/maps` for  
   `/dev/__properties__/u:object_r:timezone_prop:s0`  
   (exclude `appcompat_override/...`).
2. `open` that path `O_RDONLY`, `munmap` the shared range,  
   `mmap(addr, len, PROT_READ|PROT_WRITE, MAP_PRIVATE|MAP_FIXED, fd, 0)`.  
   → private CoW copy; **global shm untouched**.
3. Locate `prop_info` for `persist.sys.timezone` inside the private area  
   (scan for name, or walk trie if we vendor a minimal prop_area walker).  
   Layout (bionic): `serial:u32` + `value[92]` + `name[]` (`sizeof(prop_info)==96` before flexible name).
4. Write spoofed id into `value[]` (NUL-terminated, `len < PROP_VALUE_MAX`).  
   Update `serial` top byte to new length: `(len << 24) | (serial & 0x00fffffe)` (clear dirty bit).
5. `mprotect(addr, len, PROT_READ)` — back to read-only like stock.
6. Optional but recommended for immediate Java consistency:  
   JNI `TimeZone.setDefault(null)` and ICU `ExtendedTimeZone.clearDefaultTimeZone()`  
   so any early cache is dropped; next `getDefault` reads supplier → **private** prop.
7. **`api->setOption(DLCLOSE_MODULE_LIBRARY)`** — no Arirang `.so` left.

No libc instruction patches. No forever-resident trampoline. After DLCLOSE the process looks like a normal app whose property mapping happens to be private.

**Why this is Truman for A–F and H:**

- Every bionic read path loads from the in-memory `prop_info` in the mapped area → sees S.  
- Java supplier and JNI `native_get` use the same bionic → S.  
- `updateTimeZone` / `setDefault(null)` re-reads supplier → still S (private area stable).  
- `bindApplication`’s `TimeZone.setDefault(null)` runs **after** specialize → re-read still S.  
  (Do **not** rely on seeding only Java default before bindApplication.)

### 12.4 Timing (critical)

```
zygote fork
  → preAppSpecialize          // may only decide; prefer work in post*
  → drop privileges / specialize
  → postAppSpecialize         // *** CoW + patch + clear TZ cache + DLCLOSE ***
  → ... eventually ...
  → handleBindApplication
       TimeZone.setDefault(null)   // re-reads property → private S
       LocaleList / Configuration  // unrelated to TZ
  → app onCreate
```

Implement the CoW in **`postAppSpecialize`**, not only `preAppSpecialize`, so the final credentialed address space is what we patch. Still DLCLOSE before returning.

### 12.5 Residual detection surfaces and mitigations

| Residual | Severity | Mitigation |
|----------|----------|------------|
| maps: `r--p` instead of `r--s` on timezone_prop | Low–med (only high-end native detectors) | Optional §12.6 mount-ns shared fake file |
| `open(prop_path)` + raw `read`/`mmap` **new** mapping of the real file | Med if app is root-free but can open prop nodes | On this device app uids can often still open the node; after CoW, **new** `mmap` of the fd would see **real** value. Mitigate by also **replacing the file in the process mount namespace** (§12.6) so even fresh opens get the fake. |
| `persist.sys.timezone_confidence` mismatch | Low | Optionally CoW `timezone_metadata_prop` the same way if apps compare |
| Zone length / DST vs spoofed location GPS | Product-level | Keep location spoof + TZ consistent in UI presets |
| Network time / server | Out of scope | — |
| Cross-app content providers echoing real TZ | Rare | system_server hooks only if needed later |

**§12.6 Hardening (recommended for full Truman):** mount-namespace bind camouflage

Already every app has a mount ns (and KSU denylist unmount patterns). At specialize, for spoofed apps only:

1. `unshare` not required if already unique ns.  
2. Copy `timezone_prop` to e.g. tmpfs `/dev/.arirang/tzprop_<uid>` (or memfd). **Use the mapping's real size (`fstat`/VMA length), not a hardcoded 128 KiB** — bionic's `PA_SIZE` is `128*1024` normally but `1024*1024` when the platform is built with `LARGE_SYSTEM_PROPERTY_NODE` (`prop_area.cpp:45-48`). Copying a fixed 131072 bytes truncates the area on such builds.  
3. Patch the **copy** (same prop_info edit).  
4. `mount --bind` copy over `/dev/__properties__/u:object_r:timezone_prop:s0`.  
5. `munmap` old mapping; `mmap` **MAP_SHARED|PROT_READ** of the path again at same address (or let next property API fault—better explicit remap).  
6. maps shows `r--s` + same path; open/read file returns spoofed; global real file unchanged (bind is local to mount ns).  
7. DLCLOSE.

This is the **preferred production shape** when denylist/mount ns is reliable. Pure CoW (§12.3) is the minimal proven core; bind is the anti-detection shell.

Do **not** leave `/dev/.arirang` paths readable by the app after setup if avoidable (use memfd + bind by fd where possible, or unmount extra copies).

### 12.7 What Zygisk may and may not do

| Allowed | Forbidden |
|---------|-----------|
| Enter process only through Zygisk specialize callbacks | Stay mapped in ordinary apps |
| CoW / mount-ns fixup then DLCLOSE | App-scope LSPosed for TZ |
| system_server hooks that do **not** change global TZ (optional logging) | Global resetprop of `persist.sys.timezone` as per-app means |
| phone process residence (existing policy) | Inline hook left behind as primary mechanism |

Zygisk contact is unavoidable for specialize; **Truman requires zero post-DLCLOSE executable artifact from Arirang**. Data-only private pages / bind mounts are OK—they are indistinguishable from “weird but possible kernel mapping states,” and with §12.6 even maps flags match.

### 12.8 Implementation blueprint (code sites)

```
submodule/config.json  +=  systemSettingEnabled, timeZoneGlobal, timeZoneByPackage{}
app SystemSettingPrefs  →  write those into submodule config (remove “no native” comment)

timezone_prop_cow.cpp (new):
  find_timezone_prop_mapping()
  cow_remap_private() | mountns_bind_fake()
  patch_persist_sys_timezone(id)
  clear_java_default_timezone(JNIEnv*)

arirang_zygisk.cpp postAppSpecialize:
  if (!phone && should_spoof_tz(nice_name, uid, config)):
      install_timezone_illusion(env, id)
  if (!phone):
      DLCLOSE  // always, as today
```

**Length note:** `PROP_VALUE_MAX` is 92; all Olson ids used in the UI fit. Serial length byte must match `strlen(value)`.

**Same-length coincidence:** `Asia/Shanghai` and `Europe/London` are both 13 chars—useful for tests; production must handle arbitrary lengths via serial update (verified with `America/New_York`).

### 12.9 Explicit non-goals

- Spoofing wall-clock (`currentTimeMillis`)  
- Lying about TZ in **other** processes’ IPC responses (unnecessary if app never leaves process for default TZ)  
- Patching `Configuration` (no TZ field)  
- Replacing `RuntimeHooks` supplier (immutable after init)

### 12.10 Final recommendation (replace §10)

**Ship the per-process `timezone_prop` illusion:** at `postAppSpecialize`, give the specializing app a private (CoW and/or mount-ns bind) view of `persist.sys.timezone`, patch only that view, clear Java/ICU default cache, then `DLCLOSE`. All in-process APIs then form a closed consistent world—the Truman condition—while the device-global property and every other process remain on the real zone. Reject app Xposed scope, reject resident property hooks, reject global setprop as the per-app mechanism.

Self-check acceptance gate for a spoofed package:

1. `TimeZone.getDefault().getID() == S`  
2. `ZoneId.systemDefault().getId() == S`  
3. Reflect `SystemProperties.get("persist.sys.timezone") == S`  
4. After sending `ACTION_TIMEZONE_CHANGED` / forcing `updateTimeZone`, still `== S`  
5. Shell `getprop persist.sys.timezone` still real  
6. `/proc/<pid>/maps` has no `arirang` / zygisk module path  
7. (Stretch) maps flag `r--s` with §12.6  

---

## 13. Source-ground-truth verification (bionic + sepolicy)

> Verified 2026-08-02 against the AOSP/PixelOS trees on the build host
> (`/home/dddqmmx/pixelos`). Source is more authoritative than a single device
> for the *correctness / does-it-generalize* question; the device run in §15
> adds empirical + kernel + enforcing-SELinux confirmation. Every load-bearing
> claim of §12 now has a citation.

### 13.1 The read mapping is `MAP_SHARED, PROT_READ` → CoW remap is a real private copy

`bionic/libc/system_properties/prop_area.cpp`:

```
126:  void* const map_result = mmap(nullptr, pa_size_, PROT_READ, MAP_SHARED, fd, 0);   // map_prop_area() (readers)
 99:  void* const memory_area = mmap(nullptr, pa_size_, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0); // map_prop_area_rw() (init only)
```

Readers (every app) map the context file **`MAP_SHARED, PROT_READ`**. All
processes therefore share the same physical pages. A per-process
`mmap(addr, len, PROT_READ|PROT_WRITE, MAP_PRIVATE|MAP_FIXED, fd, 0)` produces a
copy-on-write private view: our writes never touch the shared file/pages, so
every other process and the on-disk/shm area keep the real zone. This is the
kernel guarantee §12.3 relies on — now confirmed, not assumed.

### 13.2 The context base pointer is cached → `MAP_FIXED` at the same address stays transparently valid

`bionic/libc/system_properties/context_node.cpp`:

```
47:  if (pa_) {            // cached: mapped once per context_node, then reused
...
56:    pa_ = prop_area::map_prop_area(filename.c_str());
59:  return pa_;
```

bionic maps each SELinux-context file **once** and caches the base pointer
`pa_`. `__system_property_find()` returns a `prop_info*` **into that cached
area**. Because we remap at the *identical virtual address* with `MAP_FIXED`,
the cached `pa_` and every previously-returned `prop_info*` remain valid and now
resolve into our private copy. No bionic re-lookup is needed and none can
notice the swap. This is why the illusion survives without any code hook.

### 13.3 `prop_info` layout and serial encoding — patch recipe is exact

`bionic/.../include/system_properties/prop_info.h` + `sys/system_properties.h`:

```
static_assert(sizeof(prop_info) == 96, ...);   // serial@0 (4B), value@4 (92B), name@96
#define PROP_VALUE_MAX 92
#define PROP_NAME_MAX  32
kLongFlag = 1<<16;   // only for values > 92 chars (never a timezone id)
```

`system_properties.cpp`:

```
52:  #define SERIAL_DIRTY(serial)     ((serial)&1)
53:  #define SERIAL_VALUE_LEN(serial) ((serial) >> 24)
...
321:  int new_serial = (len << 24) | ((serial + 1) & 0xffffff);   // bionic's OWN writer
```

So the patch is: write the id into `value[]` (NUL-terminated) and set
`serial = (strlen(id) << 24) | ((old_serial + 1) & 0x00ffffff)` with bit 0
(dirty) clear. **This is byte-for-byte what bionic's own `__system_property_update`
writes** (line 321), so the result is indistinguishable from a legitimate
property write. All Olson ids fit in 92 bytes (longest in the tz database,
`America/Argentina/Buenos_Aires`, is 30 chars), so the long-property path is
never involved.

### 13.4 Reader coherence — a static patched serial reads in one clean pass

`ReadMutablePropertyValue` (`system_properties.cpp:186-212`) loads `serial`; if
`SERIAL_DIRTY` it futex-waits; else it copies `SERIAL_VALUE_LEN(serial)` bytes,
re-reads serial, and loops until stable. Our private copy is **static** (nothing
in the app writes persist props — that is `property_service`'s job, in a
*different* process, against the *real* file), and our serial has the dirty bit
clear, so:

- `__system_property_get` → `ReadMutablePropertyValue` → spoofed, one pass.
- `__system_property_read` → same helper → spoofed.
- `__system_property_read_callback` (`:240-256`) → non-dirty branch calls
  `callback(cookie, pi->name, pi->value, serial)` directly → spoofed.

An important corollary: because the real property lives in the shared file and
our view is detached, a later **global** timezone change (`property_service`
rewriting the real file) never reaches the spoofed app — it stays pinned to the
spoofed zone until process death. That closes the §5.4 "cache cleared → reverts
to real" failure mode structurally, and matches the §12.3 claim that
`updateTimeZone()` / `setDefault(null)` can only re-read our private area.

### 13.5 The tz value's SELinux context

`system/sepolicy/private/property_contexts`:

```
1050: persist.sys.timezone            u:object_r:timezone_prop:s0          exact string
1055: persist.sys.timezone_confidence u:object_r:timezone_metadata_prop:s0 exact uint
```

Target file: `/dev/__properties__/u:object_r:timezone_prop:s0`. The confidence
value lives in a *different* context file and the policy comment notes those
"do not need to be read by other processes," so §12.5's confidence-mirror
hardening is low priority. (Production code should still not hardcode the
context name — resolve it dynamically by finding the VMA that contains the
`prop_info*` from `__system_property_find`, §15.)

---

## 14. SELinux clearance — the make-or-break fact for in-app CoW

The whole design turns on one question: **at `postAppSpecialize` the process has
already transitioned to the app domain (`untrusted_app`, `isolated_app`, …). Can
that domain `open` + `mmap` the timezone context file?**

`system/sepolicy` (present in every recent `plat_sepolicy.cil`, e.g. api
`202404:21494`, `202504:14152`):

```
(allow domain timezone_prop (file (read getattr map open)))
```

`domain` is the attribute that **all** SELinux domains carry, so
`untrusted_app`/`isolated_app` inherit `read getattr map open` on `timezone_prop`
files. Confirmed implications:

- `open("/dev/__properties__/u:object_r:timezone_prop:s0", O_RDONLY)` from inside
  an ordinary app is policy-legal (it has to be — that is how a cold context is
  lazily mapped in-app, and how `getprop` works from an app).
- `mmap(..., MAP_PRIVATE, fd)` needs only `map` + `read`, both granted. The CoW
  write goes to anonymous pages, so no `write` on the file is required.
- Therefore the §12.3 pure-CoW core runs **without any SELinux denial** in the
  app domain. No `magiskpolicy`/sepolicy patch is needed for it.

Caveat to confirm on-device (§15): the **mount-namespace bind** hardening
(§12.6) additionally needs the ability to `mount` inside the app's ns. That is
not an app-domain permission; it must be performed by the module while still
privileged, or by the root daemon via `setns`, *before* DLCLOSE and *before*
dropping caps. The pure-CoW core has no such requirement — another reason it is
the minimal proven mechanism and the bind is the optional anti-detection shell.

---

## 15. Reproducible verification harness

### 15.1 Native CoW proof (`tzcow_test`)

A self-contained arm64 test that performs the exact §12.3 sequence in one
process and reads back through every bionic path. Source lives at
`submodule/doc/verify/tzcow_test.cpp` (copied from the build-host scratch used
here). It:

1. reads persist.sys.timezone via all three bionic APIs (BEFORE),
2. `__system_property_find` → locates the containing VMA in `/proc/self/maps`
   (context-name-agnostic),
3. `mmap(MAP_PRIVATE|MAP_FIXED)` that VMA from a fresh `O_RDONLY` fd,
4. patches `value` + `serial` (recipe §13.3), `mprotect` back to `PROT_READ`,
5. re-reads all three APIs (AFTER) — must be the spoofed id,
6. `system("getprop …")` — an exec'd child with fresh libc must still read
   **real**, proving the CoW is strictly process-local.

Build (host NDK r23b):

```
NDK=$ANDROID_HOME/ndk/23.1.7779620
$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android31-clang++ \
    -O2 -Wall tzcow_test.cpp -o tzcow_test
```

Run (device, root — needed only so the standalone binary may `open` the node;
the real module inherits the app-domain grant of §14):

```
adb push tzcow_test /data/local/tmp/ && adb shell chmod 755 /data/local/tmp/tzcow_test
adb shell su -c '/data/local/tmp/tzcow_test America/New_York'
# concurrently, from another shell, prove global is untouched:
adb shell getprop persist.sys.timezone      # -> real (Asia/Shanghai)
```

Expected: steps [1]/[6] show real→spoofed for `__system_property_get`,
`__system_property_read`, `read_callback`; step [7] `getprop` child shows real.

### 15.2 SELinux enforcement spot-check (device)

```
adb shell su -c 'sesearch -A -s untrusted_app -t timezone_prop -c file' 2>/dev/null \
  || adb shell su -c 'cat /sys/fs/selinux/policy' > policy.bin   # then sesearch on host
```

Confirms the `domain … timezone_prop … open map read` allow is present and
enforcing on the actual device policy, not just the source tree.

### 15.3 Java / ICU downstream (device, app_process + dex)

Reuse `/data/local/tmp/tztest.dex` (§9) but run it in a process that has already
had the CoW applied to confirm `TimeZone.getDefault()`, `ZoneId.systemDefault()`,
`android.icu.util.TimeZone.getDefault()`, and `SimpleDateFormat` all follow the
patched property after `TimeZone.setDefault(null)`. (In the real module the CoW
runs at `postAppSpecialize`; for the standalone proof, do the native remap first,
then invoke the dex logic in the same process.)

### 15.4 Status of this run

- §13/§14 (**bionic + sepolicy source**): verified 2026-08-02 on the host tree,
  citations above.
- `tzcow_test` (fd-based CoW) and `tzcow_anon` (anonymous CoW) built for arm64
  (NDK r23b) and staged under `submodule/doc/verify/`.
- On-device execution: **done 2026-08-02** on the reference device (`diting`,
  22081212C, Android 16). Full results, including a discovered pre-existing
  failure case that empirically proves the SELinux-label requirement, are in
  **§17**.

---

## 16. Bottom line — what "non-invasive like the sensor spoofer" can and cannot mean here

The sensor spoofer is zero-contact **because `SensorService` lives in
`system_server`**: the module hooks the *service*, the app process is never
touched, and apps receive spoofed data over normal Binder IPC. Locale is
similar (`LocaleManagerService`, per-package plumbing exists).

**Default timezone has no such service.** `TimeZone.getDefault()` /
`ZoneId.systemDefault()` / ICU default all resolve **in-process** from a
**global** property, with **no IPC** and **no per-app key** anywhere in the
platform (§2, §3). Exhaustively, an app's answer is fixed by exactly two things,
and neither can be made per-app from outside the app:

1. the process-local Java/ICU default cache — settable only by code in that
   process;
2. the bytes the app reads for `persist.sys.timezone` — which come from a
   globally-shared mapping. Giving one app different bytes requires a private
   remap **or** a per-app mount-ns bind, both of which take effect only from
   inside that process (an external bind cannot retroactively alter the mapping
   the app already inherited from zygote; writing the shared pages via
   `/proc/pid/mem` would corrupt *every* process and is itself invasive).

**Therefore: a truly zero-contact, sensor-spoofer-style per-app timezone is
impossible on stock Android.** This is a platform-structural fact, not a gap in
Arirang.

The closest achievable — and what §12 specifies — is the **minimum-footprint**
mechanism, which honours every stated constraint:

- **No third-party app injection / no resident Zygisk:** the module performs a
  one-shot, **data-only** edit during the Zygisk specialize callback that fires
  in every forked process anyway, then `DLCLOSE_MODULE_LIBRARY`. After that call
  **no Arirang code, hook, trampoline, or `.so` remains mapped** in the app
  (verify: `/proc/<pid>/maps` has no arirang/zygisk path). What remains is a
  private data page (or a mount-ns-local file) — indistinguishable from a benign
  unusual mapping state, with zero ongoing interception. This is a strictly
  smaller footprint than the phone-process residence and the DRM inline hooks
  the project already ships.
- **No app-scope LSPosed/Xposed**, **no global `setprop`**, **no persistent
  property hook** — all explicitly rejected (§12.7, §5).

The honest framing for the product: timezone spoofing is *seeded* once at
process birth via a data-only property-view edit and then runs with **no
resident agent**, rather than being *served* live from `system_server` the way
sensors are. That is the maximal fidelity to "non-invasive" that the platform
permits for this signal.

---

## 17. Live device verification (2026-08-02, `diting` / 22081212C, Android 16)

Root shell context `u:r:ksu:s0`, SELinux **Enforcing**, real
`persist.sys.timezone = Asia/Shanghai`. Two native probes (`submodule/doc/verify/`)
were cross-compiled (NDK r23b) and run on-device.

### 17.1 Result A — fd-based CoW (`tzcow_test`, run as root)

`mmap(MAP_PRIVATE|MAP_FIXED, fd)` over the prop-area VMA, patch, `mprotect` RO:

| Path | BEFORE | AFTER |
|------|--------|-------|
| `__system_property_get` | `Asia/Shanghai` (len 13) | **`America/New_York` (len 16)** |
| `__system_property_read` | `Asia/Shanghai` | **`America/New_York`** |
| `__system_property_read_callback` | `Asia/Shanghai` serial `0x0d000000` | **`America/New_York` serial `0x10000002`** |
| exec'd child `getprop` (fresh libc) | — | **`Asia/Shanghai` (real)** |

Empirically confirms, on the actual kernel: (a) `MAP_PRIVATE` remap flips **all
three** bionic read paths; (b) the struct layout — the probe read
`name@offset96 == "persist.sys.timezone"`, and serial `0x0d000000`→len 13,
`0x10000002`→len 16, i.e. `serial>>24 == strlen`, exactly §13.3; (c) the spoof
is **strictly process-local** — an exec'd `getprop` child re-maps the real global
and reads `Asia/Shanghai`. The stock VMA is `r--s`, 131072 bytes (this device is
**not** `LARGE_SYSTEM_PROPERTY_NODE`; the §12.6 copy must still `fstat` the size,
never hardcode).

### 17.2 Result B — anonymous CoW (`tzcow_anon`, run as root) — the label-independent variant

A better in-app primitive discovered during this run. Instead of opening the
context file, it: snapshots the process's **own inherited** `PROT_READ` mapping,
replaces the VMA with `mmap(MAP_PRIVATE|MAP_ANONYMOUS|MAP_FIXED, -1, 0)`, restores
the bytes, patches, `mprotect` RO.

Result: `Asia/Shanghai` → **`Europe/London`** on both `__system_property_get`
and `read_callback`; child `getprop` still real. **It opens no file**, so it
needs none of `open`/`map`/`read` on `timezone_prop` and no root — only reading
one's own memory and making anonymous pages, which every app domain can do
unconditionally. Trade-off: the VMA loses its pathname in `/proc/self/maps`
(shows as anonymous rather than `r--p …/timezone_prop:s0`) — a different, arguably
larger, cosmetic tell than fd-CoW's `r--s`→`r--p`. Choose per detection model;
for functional correctness in a possibly-mislabeled environment it is the safest.

### 17.3 Discovered failure case — a real, broken pre-existing attempt on the device

`/proc/1/mounts` (i.e. **init's** namespace, global) already contained:

```
tmpfs /dev/__properties__/u:object_r:timezone_prop:s0 tmpfs rw,seclabel,... 0 0
```

i.e. `/dev/timezone_prop_fake` (a valid prop-area copy — `PROP` magic + version
`0xfc6ed0ab`) **bind-mounted globally** over the real timezone context file.
Its SELinux label is the tmpfs default **`u:object_r:device:s0`**, not
`timezone_prop`. Consequences observed in the kernel audit log:

```
avc: denied { read } ... name="timezone_prop_fake" tcontext=u:object_r:device:s0
     tclass=file scontext=u:r:priv_app:s0        app=com.google.android.googlequicksearchbox
     … scontext=u:r:isolated_app:s0
     … scontext=u:r:gmscore_app:s0               app=com.google.android.gms
     … scontext=u:r:runas_app:s0                 app=asia.nana7mi.arirang.selfcheck
```

Every **app** domain is denied `read` on the mislabeled fake, so any app process
that lazily (re)maps the timezone context gets **nothing**. Verified directly:
running `tzcow_anon` in the app-derived `runas_app` domain (via
`run-as <pkg> ./tzcow_anon`, uid 10256) printed
`__system_property_get("persist.sys.timezone") = "" (len 0)` and
`__system_property_find → NULL` — the app **cannot even find** the property. The
bind is **not** created by any committed Arirang script (`grep` of
`submodule/`, `/data/adb/**` finds nothing) — it is a leftover manual experiment.

**This is the single most important implementation lesson, proven in the field:**

> A property-area view exposed to apps **must carry `u:object_r:timezone_prop:s0`**.
> The default tmpfs `device` label makes `(allow domain timezone_prop file …)`
> inapplicable (wrong type), so every app domain AVC-denies `read` and the whole
> timezone signal breaks — worse than no spoof.

It also re-proves two design rejections empirically: a **global** bind (§5.2) is
the wrong shape (it hit *all* apps, not one), and exposing a **file** at all
(fd-CoW / mount-ns bind) drags in SELinux labeling that the **anonymous-memory**
primitive (§17.2) avoids by construction.

### 17.4 App-domain reachability confirmed

`run-as` on the debuggable selfcheck app put the probe in `runas_app`
(app-derived, carries the `domain` attribute) at uid 10256. Exec from the app
data dir succeeded (once statically linked); the anonymous-memory operations ran
without any SELinux denial. The only failure was the *property lookup itself*,
fully attributable to §17.3's broken bind — not to the CoW mechanism. On a clean
device (correct `timezone_prop` label, so zygote maps it and children inherit
it), the app maps the real area and the anonymous-CoW patch applies. The clean
end-to-end app-domain read is the one item still to demonstrate, and it is gated
on removing the leftover bind (a global system change — left to the device owner).

### 17.5 Corrected implementation guidance (supersedes the §12.3 "open the file" default)

1. **Primary primitive: anonymous CoW (§17.2).** In `postAppSpecialize`, for a
   package with a resolved override: `__system_property_find` to fault-in and
   locate the timezone VMA, snapshot it, `MAP_PRIVATE|MAP_ANONYMOUS|MAP_FIXED`
   replace, restore+patch (`value` + `serial=(len<<24)|counter`, dirty bit
   clear), `mprotect` RO, clear Java/ICU default, **DLCLOSE**. No file open, no
   SELinux file perms, label-proof, strictly process-local (§17.1 child test).
2. **Only if a file-backed view is required** (e.g. to also cover fresh
   `open()`+`mmap` of the path by the app, or exec'd children within the ns —
   the §12.5 residuals): use the mount-ns bind, but **label the fake
   `u:object_r:timezone_prop:s0`** via `setfscreatecon()` before creating it, or
   `chcon`/`restorecon` to that type, and bind it **only inside the target app's
   private mount namespace**, never globally. §17.3 is the cautionary tale for
   getting either of those wrong.
3. Never rely on the default tmpfs `device` label. Never bind globally.

---

*End of research notes. Implementation next step (revised by §17): add a
`timezone_prop_cow` helper whose primary primitive is the **anonymous CoW**
(§17.2/§17.5) — `__system_property_find` to fault-in and locate the VMA, snapshot
it, `MAP_PRIVATE|MAP_ANONYMOUS|MAP_FIXED` replace, restore + patch `value`+`serial`,
`mprotect` RO, clear the Java/ICU default — call it from `postAppSpecialize` only
for packages with a resolved override, then DLCLOSE as today (no file open, no
SELinux file perms, no resident code). Reserve the mount-ns bind for the §12.5
residuals and only with the fake labeled `u:object_r:timezone_prop:s0` in the
app's private ns (never the `device` label, never global — see the §17.3 failure
case). Wire `SystemSettingPrefs.buildHookSnapshot` into the submodule
`config.json` (`systemSettingConfigSnapshot`) — today it is deliberately not
written to native. Keep LSPosed out of ordinary apps.*
