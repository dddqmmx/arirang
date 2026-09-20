# Native Submodule (`arirang-submodule`)

`arirang-submodule` is an optional Zygisk native companion extension for Arirang.

---

## Architectural Purpose

While the core of Arirang is an LSPosed module that intercepts framework operations inside `system_server` and provider processes, certain platform behaviors either:
1. Do not cross an IPC boundary (they resolve purely in-process inside the calling application), or
2. Are queried via native libc/Bionic interfaces before Java code runs or outside the reach of Java reflection.

`arirang-submodule` acts as the native layer bridging these gaps without violating Arirang's core philosophy.

---

## Core Design Principles

### 1. Minimal In-App Residency
Arirang strictly adheres to the rule: **do not keep resident code or ongoing hooks inside arbitrary third-party applications**.
- Ordinary applications only experience Zygisk execution during process birth (`postAppSpecialize`).
- Once necessary process-local state setup is completed, Zygisk calls `api->setOption(DLCLOSE_MODULE_LIBRARY)`.
- After specialize returns, `/proc/<pid>/maps` contains no Arirang `.so`, executable trampolines, or hooking framework artifacts.

### 2. Provider-Side Interception Over Client-Side Hooking
Whenever possible, interception is performed at the service provider:
- Telephony hooks live inside `com.android.phone` (which legitimately hosts telephony state).
- Sensor hooks live inside `system_server`'s `libsensor` / `SensorService`.
- Build and system property overrides are managed through `resetprop` or system-server hooks.

### 3. Anonymous Copy-on-Write (CoW) Memory Virtualization
For in-process platform properties like `persist.sys.timezone`:
- Android maps SELinux property context nodes (`/dev/__properties__/u:object_r:...`) with `MAP_SHARED, PROT_READ` across all processes.
- Instead of installing inline hooks or PLT trampolines in libc (which are easily detected via memory checksums or `/proc/self/mem` checks), `arirang-submodule` replaces the property VMA with a private anonymous mapping (`MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED`) at the exact same virtual address.
- It patches the `prop_info` structure's `value` and atomic `serial` top-byte length field in-place, then re-protects the page to `PROT_READ`.
- This ensures all standard Bionic readers (`__system_property_get`, `__system_property_find`, `read_callback`) and libcore suppliers seamlessly read the spoofed value while global shared memory remains untouched.
