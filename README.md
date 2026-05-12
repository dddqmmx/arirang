# Arirang

Arirang is named after a smartphone brand from North Korea.

This is a powerful Xposed module for Android designed to enhance user privacy through fine-grained control over sensitive system information and runtime hooks. It allows spoofing of device identifiers, location, SIM information, and app visibility.

## Philosophy

Arirang is designed around a system-level privacy protection model.

Unlike many traditional Xposed privacy modules, Arirang does not aim to inject hooks into arbitrary third-party applications whenever possible.  
Instead, the project attempts to keep hooks, data interception, and data rewriting inside system-level components and framework layers.

The goal of this design is to:
- Reduce unnecessary application-side injection
- Minimize compatibility issues with target applications
- Lower behavioral detectability from user applications
- Keep privacy control logic centralized at the system layer

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

- **SIM Mocking (Experimental / Partial)**  
  Partially implemented SIM information rewriting and masking features. Functionality is incomplete and may be unstable.

- **Location Spoofing (In Development)**  
  Provide mock GPS coordinates to selected applications.

- **Package List Management (In Development)**  
  Hide installed applications (Invisible / Whitelist modes).

- **Device Info Masking (Planned)**  
  Modify hardware identifiers and system properties.

- **Wi-Fi Info Masking (Planned)**  
  Hide or modify Wi-Fi information such as SSID, BSSID, MAC address, and network details.

- **Nearby Devices List Masking (Planned)**  
  Hide or modify nearby Wi-Fi, Bluetooth, and other discoverable device lists.

- **Modern UI**  
  Built with Material Design 3 and Dynamic Colors support.

- **Multi-language Support**

## 🛠 Requirements

- Rooted Android device  
- **LSPosed** or compatible Xposed framework  
- Android 15+ (recommended)

## 📦 Installation

1. Install the latest `Arirang` APK  
2. Open your Xposed Manager (e.g., LSPosed)  
3. Enable the **Arirang** module  
4. Select scope:
   - System (required)
   - Phone (optional, for SIM simulation)
5. Reboot your device or restart target apps  

## ⚙️ Configuration

Open Arirang from your launcher:

- **Clipboard Protection**  
  Configure interception and confirmation behavior

After reboot, open Arirang to verify the module is active.

## 🛡 Disclaimer

This project is for **testing and educational purposes only**.

The developers are not responsible for any damage, data loss, or misuse caused by this software.

## 🤝 Contributing

Contributions, issues, and feature requests are welcome.
