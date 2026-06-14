# Arirang DRM Hook — Research & Migration Notes

> Working device: Pixel 8 Pro (`husky`), Android 16 (API 36), KernelSU Next root.
> Target process: `android.hardware.drm@1.4-service.widevine` (pid varies).

## 1. Current state of the DRM hook

`arirang_drm_hook.cpp` (loaded into the Widevine DRM HAL daemon via `LD_PRELOAD`)
intercepts `getPropertyByteArray("deviceUniqueId")` by overwriting the first 16 bytes
of the target function with an absolute `BR` trampoline. The implementation lives in
`src/main/cpp/hook/inline_hook.cpp`.

Why this is a hack / unstable:

* It directly rewrites AArch64 machine instructions.
* It assumes the first 16 bytes of the function are safe to overwrite (no
  PC-relative instructions).
* It cannot reason about PAC (Pointer Authentication) prologues such as
  `paciasp` / `autiasp`. On ARMv8.3+ devices these instructions sign the return
  address; overwriting them breaks the authentication and crashes the daemon.
* It is version-specific: function prologues, compiler choices and linker
  optimizations differ between OEMs and Android releases.

Hard-coded work-arounds such as

```cpp
bool instruction_is_paciasp(uint32_t instr) {
    return instr == 0xd503233fu; // <-- explicitly forbidden
}
```

or hard-coded vtable name tables are brittle and not acceptable.

## 2. Goal

Replace instruction-level hooking with a **data-level, generic hook** that:

1. Locates the `getPropertyByteArray` implementation without hard-coded names.
2. Redirects calls by patching **function-pointer tables / vtables / method tables**
   instead of machine code.
3. Works on absolute-pointer tables and on binaries that use Itanium relative
   vtables (32-bit signed offsets).
4. Survives Android/compiler/PAC differences because it never touches an
   instruction.

## 3. Target binaries on the reference device

| File | Path | Role |
|------|------|------|
| Widevine HIDL plugin | `/vendor/lib64/libwvhidl.so` | HIDL `IDrmPlugin` implementation (`WVDrmPlugin`) |
| Widevine engine | `/vendor/lib64/mediadrm/libwvdrmengine.so` | CDM core, generates the real device unique ID |
| HAL daemon | `/vendor/bin/hw/android.hardware.drm@1.4-service.widevine` | Binder/HIDL service host |

`libwvhidl.so` is **dynamically stripped** but the symbol table is still present.
The HIDL method symbol is exported:

```
_ZN5wvdrm8hardware3drm4V1_48widevine11WVDrmPlugin20getPropertyByteArrayERK...
    -> file vaddr 0x15d2bc
```

A `vtable` symbol for `WVDrmPlugin` is also exported:

```
_ZTVN5wvdrm8hardware3drm4V1_48widevine11WVDrmPluginE
    -> file vaddr 0x1e5150, size 880
```

Runtime verification (process memory read via `/proc/<pid>/mem` as root) shows
that the slot at `vtable + 0xf0` points to the resolved
`getPropertyByteArray` implementation.

## 4. Candidate approaches considered

### 4.1 Keep inline hook and special-case PAC/instructions

Rejected. This still edits instructions and is exactly the fragility the task
asks us to remove.

### 4.2 Hook `CryptoSession::GetInternalDeviceUniqueId`

The real ID originates in `libwvdrmengine.so`:

```
_ZN5wvcdm13CryptoSession25GetInternalDeviceUniqueIdEPNSt3__112basic_string...
```

This would intercept the value at the source, but the function is not virtual
and is called by direct branch, so we would still need an instruction-level hook
or a fragile PLT/GOT scan. It is a useful future enhancement but not a stable
replacement for the current `getPropertyByteArray` hook.

### 4.3 GOT/PLT hook

`getPropertyByteArray` is defined in `libwvhidl.so` itself and called through
virtual dispatch, not through the PLT. Generic GOT patching therefore cannot
reach this path on the reference device.

### 4.4 Vtable / method-table hook (selected)

C++ virtual dispatch uses tables of function pointers stored in `.data.rel.ro`
(or `.data`). These tables are **data**, not code. Patching a slot:

* requires only standard memory protection changes;
* is invariant to function prologues, instruction encoding and PAC;
* works on any C++ ABI that uses vtables, including Itanium and relative vtables.

This is the approach we will implement.

## 5. Research findings on the reference device

A standalone test binary (`/data/local/tmp/test_vtable_hook`) was built, pushed
and executed as root. It performs the following steps:

1. `dl_iterate_phdr` to walk `.dynsym` and locate `getPropertyByteArray`.
2. Scan the target library's readable, non-executable pages for slots that
   reference the function.
3. Test `mprotect(PROT_READ | PROT_WRITE)` on a matching slot to prove the
   table can be rewritten.

Result:

```
resolved 1 symbols for getPropertyByteArray
  _ZN5wvdrm...WVDrmPlugin20getPropertyByteArray... @ 0x...12bc
REL32 match: slot=0x...d2c8 rel=770036 -> ...
ABS match:  slot=0x...1240 -> ...
PATCH test OK for slot 0x...1240
```

* One absolute-pointer slot is present. It is the real vtable entry.
* One `rel32` match also appears, but it lies inside `.eh_frame` (unwind
  information) and is a false positive. This means a blind rel32 scan must be
  constrained.
* The absolute-pointer scan is already sufficient on the reference device.

Runtime verification in the actual DRM daemon via `/proc/<pid>/mem` confirmed
that the vtable slot contains the live `getPropertyByteArray` pointer.

## 6. Selected solution

1. **Symbol resolution** — keep the existing `symbol_resolver` substring search.
   It finds `getPropertyByteArray` from `.dynsym` without hard-coded names.

2. **Vtable hook** — make `hook/vtable_hook.cpp` the primary hook engine for DRM:
   * Patch any 8-byte slot whose value equals the target function address
     (absolute-pointer vtables).
   * Also support 4-byte signed relative offsets for future Itanium relative
     vtables, but only inside a controlled vtable range to avoid false
     positives.

3. **DRM driver** — rewrite `arirang_drm_hook.cpp` so that:
   * AIDL and HIDL `getPropertyByteArray` variants are hooked via vtable.
   * The original function pointer stored by the vtable patcher becomes the
     trampoline — no custom generated machine code is needed.
   * `inline_hook` is no longer used for the DRM path.

4. **Generic vtable discovery (optional first step)** — to avoid scanning whole
   data sections, add a helper that locates `_ZTV...` symbols matching a class
   name pattern (e.g. substring `WVDrmPlugin` while excluding `Factory`,
   `CryptoSession`, `PropertySet`). This keeps the search narrow without
   hard-coding exact versioned mangled names.

## 7. Open problems / risks

* Some firmware builds may strip **all** dynamic symbols, including vtable
  symbols. In that case we must fall back to scanning sections or to function
  signature / string-reference discovery. This is not covered in the initial
  pass but the architecture should allow adding more symbol-discovery backends
  later.
* Relative vtables must be patched only inside real vtable ranges. Blind rel32
  scans hit `.eh_frame` and `.gnu_debugdata`. We will mitigate this by either
  using exported vtable symbol bounds or by scanning only pages that also
  contain other virtual-method pointers from the same class.
* The hook `.so` runs inside the DRM daemon context. `mprotect` of
  `.data.rel.ro` must be allowed by SELinux. The reference device allows it;
  if a future device does not, we may need to relax/extend `sepolicy.rule`.
* Only HIDL 1.4 was verified on the reference device. AIDL and older HIDL
  versions must still be reachable through symbol mangling heuristics already
  present in `arirang_drm_hook.cpp`.

## 8. Verification plan

1. Build the submodule with the new vtable-based DRM hook.
2. Run `./gradlew :arirang-submodule:installKernelSuNextAndReboot`.
3. After reboot, check `logcat` for `ArirangDrmHook` / `arirang_drm_hook:` tags.
4. Trigger a `MediaDrm.getPropertyByteArray(PROPERTY_DEVICE_UNIQUE_ID)` call
   (e.g. via the Arirang self-check app or a tiny test APK) and confirm the
   returned bytes match the configured spoof value, not the real Widevine ID.
5. If the hook fails, collect `logcat` and `/proc/<drm-pid>/maps`, then iterate.

## 9. Verification

The new vtable-based DRM hook was installed via
`./gradlew :arirang-submodule:installKernelSuNextAndReboot` on the reference
Pixel 8 Pro (Android 16, KernelSU Next). After reboot:

|`logcat` excerpt|Meaning|
|---|---|
|`I ArirangDrmHook: vtable hook success for HIDL _ZN5wvdrm...getPropertyByteArray...`|The hook found the HIDL `getPropertyByteArray` symbol through `.dynsym` and patched its vtable slot without touching instructions.|
|`I ArirangZygisk: arirang_drm_hook: spoof bytes loaded len=16`|The configured spoof ID was loaded from `/dev/.arirang/widevine_id`.|
|`I ArirangZygisk: arirang_drm_hook: spoofed deviceUniqueId byte[] (HIDL)` (×2)|Opening the Arirang self-check app triggered real `MediaDrm.getPropertyByteArray(MediaDrm.PROPERTY_DEVICE_UNIQUE_ID)` calls and the hook returned the spoofed ID instead of the real one.|

The Widevine HAL daemon (`android.hardware.drm@1.4-service.widevine`, pid 1435
at test time) did not crash, restart, or log any SELinux/`abort` afterwards.
The hook is stable on the reference device.

## 10. Changelog

| Date | Milestone |
|------|-----------|
| 2026-06-15 | Pulled `libwvhidl.so` / `libwvdrmengine.so`; verified exported symbols and live vtable slot. |
| 2026-06-15 | Built and ran `test_vtable_hook` on device; confirmed ABS vtable patching is feasible. |
| 2026-06-15 | Wrote this research doc; selected vtable hook as the replacement for inline hook. |
| 2026-06-15 | Implemented `vtable_hook.cpp`/`arirang_drm_hook.cpp` rewrite; built and installed via KernelSU Next. |
| 2026-06-15 | Verified on device: vtable hook installs and `deviceUniqueId` is spoofed. |
