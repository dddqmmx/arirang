# Arirang Features & Implementation Details

This document provides in-depth technical documentation for all features currently available or under development in Arirang.

---

## Table of Contents

- [Clipboard Protection](#clipboard-protection)
- [Real-time Permission Prompt](#real-time-permission-prompt)
- [SIM Info Mocking](#sim-info-mocking)
- [Device Info Masking](#device-info-masking)
- [Unique Identifier Spoofing](#unique-identifier-spoofing)
- [Virtual Location](#virtual-location)
- [Wi-Fi Info Masking](#wi-fi-info-masking)
- [Nearby Wi-Fi List Masking](#nearby-wi-fi-list-masking)
- [Bluetooth Info Masking](#bluetooth-info-masking)
- [Package Visibility Management](#package-visibility-management)
- [Sensor List Management](#sensor-list-management)
- [VPN Status Masking](#vpn-status-masking)
- [Language & Time Zone Masking](#language--time-zone-masking)
- [Hook Log Controls](#hook-log-controls)

---

### Clipboard Protection
- **Status**: Available
- **Description**: Monitor and intercept clipboard read requests across applications. Provides background access accounting and real-time confirmation dialogs whenever an application attempts to access the system clipboard.

---

### Real-time Permission Prompt
- **Status**: Available
- **Description**: Intercept clipboard access attempts interactively. Users can explicitly allow or deny each individual request in real time through a floating modal prompt, or configure permanent decisions for trusted applications.

---

### SIM Info Mocking
- **Status**: Experimental
- **Description**: Configure SIM profiles, hide cellular carrier data, and rewrite telephony-visible data.
- **Scope**: Hooks telephony services inside `com.android.phone` rather than client applications, covering:
  - Carrier Country ISO (`gsm.sim.operator.iso-country`)
  - Operator Numeric / MCC+MNC (`gsm.sim.operator.numeric`)
  - Operator Alpha / Carrier Name (`gsm.sim.operator.alpha`)
  - Random SIM generation utility using valid Luhn checksums

---

### Device Info Masking
- **Status**: Experimental
- **Description**: Configure visible device model, brand, manufacturer, product, hardware, board, bootloader, fingerprint, and related Android build fields.
- **Coverage**: Synchronized across `android.os.Build` reflection fields, system property reads, and framework identity layers.

---

### Unique Identifier Spoofing
- **Status**: Experimental
- **Description**: Rewrite hardware and software tracking identifiers:
  - Android ID (Settings.Secure.ANDROID_ID)
  - Google Advertising ID (GAID) via GMS hooks
  - App Set ID
  - IMEI / MEID and TAC values (per-slot)
  - Hardware Serial Number
  - Subscriber ID (IMSI) and SIM Card ICCID
  - Widevine DRM ID

---

### Virtual Location
- **Status**: Experimental
- **Description**: Configure virtual latitude, longitude, altitude, accuracy, speed, bearing, and satellite count.
- **Coverage**:
  - Android Framework Location APIs (`LocationManager`)
  - System Fused Location Provider (FLP)
  - Google Play Services Fused Location API
  - GNSS status reports and NMEA sentence emulation

---

### Wi-Fi Info Masking
- **Status**: Experimental
- **Description**: Configure the current connected Wi-Fi SSID, BSSID, IP address, gateway, and DNS.
- **Coverage**: Intercepted in framework Wi-Fi service paths and `ConnectivityManager` surfaces:
  - `NetworkCapabilities.getTransportInfo()`
  - `WifiNetworkAgentSpecifier`
  - `NetworkInfo.getExtraInfo()`
  - `WifiInfo` accessors and parcel marshaling
  - `DhcpInfo`
  - Fixed fallback values for MAC address, RSSI, frequency, and network ID.

---

### Nearby Wi-Fi List Masking
- **Status**: Experimental
- **Description**: Configure one or more nearby Wi-Fi scan result SSID/BSSID pairs, or return an empty scan list. Customizes or conceals surrounding Wi-Fi network environmental beacons from being used for geo-profiling.

---

### Bluetooth Info Masking
- **Status**: Experimental
- **Description**: Configure local Bluetooth adapter name and MAC address returned to apps. Supports hiding or spoofing the list of bonded (paired) devices and nearby BLE/Bluetooth scan results with per-device name resolution.

---

### Package Visibility Management
- **Status**: Experimental
- **Description**: Filter package queries and installed application lists at the `PackageManagerService` layer in `system_server`.
- **Capabilities**:
  - Global defaults and per-caller custom rules
  - Inheritable Whitelist / Blacklist templates
  - Interception across `getInstalledPackages`, `getInstalledApplications`, `queryIntentActivities`, `resolveService`, and UID-to-package-name lookups.

---

### Sensor List Management
- **Status**: Experimental
- **Description**: Intercept and filter the sensor list exposed to applications via `libsensor` / `SensorService` in `system_server`.
- **Capabilities**:
  - Hide sensitive sensors (accelerometer, gyroscope, step counter, etc.)
  - Rename vendor and sensor model strings to eliminate hardware fingerprinting
  - Reduce reported event precision to mitigate motion-based side-channel profiling.

---

### VPN Status Masking
- **Status**: Experimental
- **Description**: Conceal active VPN and proxy state from applications at the `ConnectivityService` layer inside `system_server`.
- **Coverage**:
  - Strip `TRANSPORT_VPN` from active `NetworkCapabilities`
  - Report replacement transport as Wi-Fi, Cellular, or Ethernet
  - Strip tunnel network interfaces (`tun*`, `ppp*`, `wg*`) from `LinkProperties`
  - Hide system always-on VPN settings
  - Suppress HTTP proxy indicators
  - Supports per-package exemptions for trusted network tools.
- **Known Limitations**:
  - Tunnel interfaces cannot be hidden from native `getifaddrs()` inside the calling app's own process without resident client hooks.

---

### Language & Time Zone Masking
- **Status**: Experimental / Imperfect
- **Description**: Configure per-app system language tags and default time zones.
- **Mechanism**:
  - **Language**: Intercepted via `LocaleManagerService` in `system_server` on Android 13+.
  - **Time Zone**: Implemented via `arirang-submodule` through anonymous memory copy-on-write (CoW) of the `persist.sys.timezone` bionic property mapping during `postAppSpecialize`, followed by clearing Java (`java.util.TimeZone`) and ICU (`android.icu.util.TimeZone`) process-local caches. Zygisk unloads (`DLCLOSE`) immediately after initialization.
- **Known Imperfections & Limitations**:
  1. **Isolated Sandboxed Processes**: Sandboxed services (e.g. separate WebView rendering processes, `uid >= 90000`) are excluded by policy to preserve stability. Web content in external isolated renderers may observe the real time zone.
  2. **Raw System Call Bypass**: If an adversarial app or security SDK bypasses libc/bionic and directly invokes `open("/dev/__properties__/u:object_r:timezone_prop:s0", O_RDONLY)` followed by `read` or `mmap`, it will observe the real shared file on disk.
  3. **Memory Mapping Tell**: In `/proc/self/maps`, the property VMA becomes an anonymous private mapping (`r--p`) rather than a shared file mapping (`r--s`), which can be detected by sophisticated memory structure scanners.
  4. **Direct Zoneinfo File Access**: Does not intercept applications or standalone runtimes that directly read timezone database files under `/system/usr/share/zoneinfo/` or maintain custom time engines.
  5. **Cross-Source Discrepancies**: Spoofing only alters the software timezone setting; it does not change the client public IP (GeoIP), cellular base station NITZ time, or GPS coordinates.

---

### Hook Log Controls
- **Status**: Available
- **Description**: Granular toggle controls to enable or disable LSPosed log output on a per-hook-module basis (Core, Clipboard, GMS, Location, Package List, Settings Provider, SIM, Wi-Fi, Unique Identifiers, and Service Bridge) to reduce logcat noise.
