# Arirang

Arirang is named after a smartphone brand from North Korea.

This is a powerful Xposed module for Android designed to enhance user privacy through fine-grained control over sensitive system information and runtime hooks. It allows spoofing of device identifiers, location, SIM information, and app visibility.

## ⚠️ Warning

This software is in an early development stage and may cause system instability, crashes, or unexpected behavior.

To achieve certain features, it uses highly aggressive system-level techniques. As a result, it may never become fully stable or suitable for daily use.

Use at your own risk.

## 🚀 Features

- **Clipboard Protection (Available)**  
  Monitor and intercept clipboard access requests with real-time confirmation dialogs.

- **Real-time Permission Prompt (Available)**  
  Intercept clipboard access attempts and explicitly allow or deny each request.

- **SIM Mocking (In Development)**  
  Customize or hide SIM card details (IMSI, operator info, etc.).

- **Location Spoofing (In Development)**  
  Provide mock GPS coordinates to selected applications.

- **Package List Management (In Development)**  
  Hide installed applications (Invisible / Whitelist modes).

- **Device Info Masking (Planned)**  
  Modify hardware identifiers and system properties.

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
4. Select target applications to hook  
5. Reboot your device or restart target apps  

## ⚙️ Configuration

Open Arirang from your launcher:

- **Clipboard Protection**  
  Configure interception and confirmation behavior

## 🛡 Disclaimer

This project is for **testing and educational purposes only**.

The developers are not responsible for any damage, data loss, or misuse caused by this software.

## 🤝 Contributing

Contributions, issues, and feature requests are welcome.
