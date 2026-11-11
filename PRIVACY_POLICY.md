# Privacy Policy for Corewatch (`com.droid.corewatch`)

**Effective Date:** November 8, 2026

Corewatch ("the App") is a local hardware telemetry, system monitoring, and keep-awake utility for Android devices and Googlebooks.

## 1. Zero Data Collection & Zero Network Transmission
Corewatch **does not collect, store, transmit, share, or sell any personal or device data**.
- The App **does not declare or request the `android.permission.INTERNET` permission** in its manifest. It is technically incapable of opening network sockets or transmitting telemetry off your device.
- There are **no analytics SDKs, no crash-reporting trackers, no advertising networks, and no user accounts**.

## 2. How Permissions Are Used Locally on Your Device
All permissions requested by Corewatch are used strictly on-device to render real-time hardware diagnostics in the user interface:

- **Network & Wi-Fi State (`ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`, `NEARBY_WIFI_DEVICES` with `neverForLocation`):** Used to display local Wi-Fi link speed, RSSI signal strength, channel/band allocation, regulatory channel tables, and wired Ethernet interface speeds.
- **Location (`ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` — Optional, User-Initiated Only):** Android requires runtime Location permission to read the connected Wi-Fi SSID/BSSID and surrounding Wi-Fi access point beacon metadata. Corewatch never determines, logs, or transmits your geographic coordinates. BSSIDs are masked (`aa:bb:cc:••:••:ff`) by default in the UI and are excluded from clipboard diagnostic exports.
- **Bluetooth (`BLUETOOTH_CONNECT` — Optional):** Used to inspect connected Bluetooth audio codec parameters (e.g., LDAC, aptX, LC3) and peripheral battery levels on-device.
- **Display Over Other Apps (`SYSTEM_ALERT_WINDOW` — Optional):** Used only when you explicitly launch the floating Always-On-Top Mini-HUD overlay window.
- **Wake Lock & Quick Settings Tile (`WAKE_LOCK`, `BIND_QUICK_SETTINGS_TILE`):** Used only when you enable the "Caffeine" keep-awake toggle to prevent screen sleep.
- **Optional ADB Diagnostics (`PACKAGE_USAGE_STATS`, `DUMP`, `READ_LOGS`):** Never requested at runtime unless manually granted by an advanced power user via ADB for extended system-wide frame-jank (`GraphicsStats`) or kernel radio diagnostics.

## 3. Diagnostic Clipboard Copy
When you manually press the **"Copy Markdown Report"** button (`Ctrl+Shift+C`), a snapshot of hardware metrics is copied to your local Android clipboard so you can paste it where you choose. Network identifiers (SSIDs, BSSIDs, IP addresses, and MAC addresses) are scrubbed from this report.

## 4. Changes to This Policy
Any updates to this Privacy Policy will be published in this repository (`PRIVACY_POLICY.md`).

## 5. Contact
For questions or issues regarding Corewatch, please open an issue at:
https://github.com/alainmic/corewatch/issues
