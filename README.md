# Arirang

Arirang is named after a smartphone brand from North Korea.

This is a powerful Xposed module for Android designed to enhance user privacy through fine-grained control over sensitive system information and runtime hooks. It allows spoofing of device identifiers, location, SIM information, and app visibility.

## Philosophy

Arirang is designed around a system-level privacy protection model.

Unlike many traditional Xposed privacy modules, Arirang does not aim to inject hooks into arbitrary third-party applications whenever possible.  
Instead, the project attempts to keep hooks, data interception, and data rewriting inside system-level components and framework layers.

The goal of this design is to:
- Avoid unnecessary impact on application performance
- Minimize interference with normal application runtime behavior

## 🔌 Optional Native Submodule

Arirang provides an optional native Zygisk extension module named
`arirang-submodule`.

This submodule is designed as a capability extension layer for functionality
that cannot be reliably implemented through LSPosed or framework-level Java
hooks alone.

Depending on the feature, the submodule may be:
- Required to implement certain low-level behaviors
- Used to strengthen spoofing consistency and anti-detection capabilities
- Used to extend privacy protection into native or process-local environments

The goal of this design is to keep most functionality inside the system
framework whenever possible, while still allowing deeper native integration
where necessary.

## ⚠️ Warning

This software is in an early development stage and may cause system instability, crashes, or unexpected behavior.

To achieve certain features, it uses highly aggressive system-level techniques. As a result, it may never become fully stable or suitable for daily use.

This project does **not** prohibit the use of AI-generated code.

During early prototyping and experimental development, a considerable amount of code was generated or assisted by large language models (LLMs).  
Some parts of the codebase may therefore contain:
- Inconsistent implementations
- Redundant abstractions
- Experimental structures
- Non-optimal patterns

As the project matures, these sections are gradually being rewritten, simplified, or replaced with manually reviewed implementations.

Use at your own risk.

## 🚀 Features

- **Clipboard Protection (Available)**  
  Monitor and intercept clipboard access requests with real-time confirmation dialogs.

- **Real-time Permission Prompt (Available)**  
  Intercept clipboard access attempts and explicitly allow or deny each request.

- **SIM Mocking (Experimental / Submodule Required)**
  Provides SIM information rewriting and masking through the optional Zygisk submodule.
  This feature is experimental and may be unstable.

- **Location Spoofing (In Development)**  
  Provide mock GPS coordinates to selected applications.

- **Package List Management (In Development)**  
  Hide installed applications (Invisible / Whitelist modes).

- **Device Info Masking (Experimental / Submodule Required)**  
  Modify hardware identifiers and system properties.

- **Wi-Fi Info Masking (Planned)**  
  Hide or modify Wi-Fi information such as SSID, BSSID, MAC address, and network details.

- **Nearby Devices List Masking (Planned)**  
  Hide or modify nearby Wi-Fi, Bluetooth, and other discoverable device lists.

- **Privacy Self-Check (Available)**  
  Inspect what device information is visible to applications and verify whether privacy protection features are working correctly.

- **Modern UI**  
  Built with Material Design 3 and Dynamic Colors support.

- **Multi-language Support**

## 📸 Screenshots

| Main | Clipboard | Clipboard Dialog |
| --- | --- | --- |
| <img src="screenshorts/main.png" width="240" alt="Main screen"> | <img src="screenshorts/clipboard.png" width="240" alt="Clipboard protection screen"> | <img src="screenshorts/clipboard-dialog.png" width="240" alt="Clipboard confirmation dialog"> |

| Device Info | SIM Mocking | Privacy Self-Check |
| --- | --- | --- |
| <img src="screenshorts/device.png" width="240" alt="Device info masking screen"> | <img src="screenshorts/sim.png" width="240" alt="SIM mocking screen"> | <img src="screenshorts/self-check.png" width="240" alt="Privacy self-check screen"> |

## 🛠 Requirements

- Rooted Android device
- **LSPosed** or compatible Xposed framework
- Magisk, KernelSU / KernelSU Next, or APatch (required for advanced submodule features)
- Zygisk
- Android 16 (recommended)

## 📦 Installation

1. Install the latest `Arirang` APK  
2. Open your Xposed Manager (e.g., LSPosed)  
3. Enable the **Arirang** module  
4. Select scope:
   - System (required)
   - Phone (optional, for SIM simulation)
5. Reboot your device or restart target apps  

#### Install `arirang-submodule` (Optional but Recommended)

Some advanced process-level features require the optional native Zygisk helper module.

1. Download `arirang-submodule.zip`
2. Flash the ZIP through:
  - Magisk
  - KernelSU / KernelSU Next
  - APatch
3. Reboot the device

## 🛡 Disclaimer

This project is for **testing and educational purposes only**.

The developers are not responsible for any damage, data loss, or misuse caused by this software.

## 🤝 Contributing

Contributions, issues, and feature requests are welcome.
