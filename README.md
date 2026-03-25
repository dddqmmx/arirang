# Arirang

Arirang is a powerful Xposed module for Android designed to enhance user privacy by providing fine-grained control over sensitive system information and hooks. It allows you to mock device identifiers, location, SIM information, and manage app visibility.

## ⚠️ Warning

This software is currently in an early development stage. It may cause system instability, crashes, or unexpected behavior. Use at your own risk.

## 🚀 Features

- **SIM Mocking**: Customize or hide SIM card details (IMSI, operator info, etc.) from apps. (Not yet available)
- **Location** Spoofing: Provide mock GPS coordinates to specific applications. (Not yet available)
- **Clipboard Protection**: Monitor and intercept clipboard access requests with real-time confirmation dialogs. (Currently available)
- **Package List Management**: Hide specific installed applications from being detected by other apps (Invisible/Whitelist modes). (Not yet available)
- **Device Info Masking**: Modify hardware identifiers and system properties. (Not yet available)
- **Real-time Notifications**: Get notified or prompted when apps attempt to access sensitive data via the HookNotifyService. (Clipboard only)
- **Modern UI**: Built with Material Design 3 and Dynamic Colors support.
- **Multi-language Support**: Easily switch between supported languages.

## 🛠 Requirements

- A rooted Android device.
- **LSPosed** or equivalent Xposed Framework installed.
- Android 15recommended.

## 📦 Installation

1. Download and install the latest `Arirang` APK.
2. Open your Xposed Manager (e.g., LSPosed).
3. Enable the **Arirang** module.
4. Check the recommended applications from the list
5. Reboot your device or restart the target applications.

## ⚙️ Configuration

Open the Arirang app from your launcher to configure global settings and specific hooks:

- **Clipboard Protection**: Monitor and intercept clipboard access requests with real-time confirmation dialogs.

## 🛡 Disclaimer

This project is for **testing and educational purposes only**. Use it responsibly. The developers are not responsible for any misuse or damage caused by this software.

## 🤝 Contributing

Contributions, issues, and feature requests are welcome! Feel free to check the [issues page](link-to-your-issues).
