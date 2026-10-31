# Security Policy

## Reporting a Vulnerability

Please **do not** open a public GitHub issue for security vulnerabilities. Instead, use GitHub's private vulnerability reporting (**Security** tab → **Report a vulnerability**) on this repository, and include steps to reproduce along with the affected version.

We aim to acknowledge vulnerability reports within 7 days.

## Security Model & Scope

**Corewatch** (`com.droid.corewatch`) is an unprivileged hardware telemetry and keep-awake utility designed with a minimal privilege surface:

- **Zero network exfiltration surface:** Corewatch does **not** request `android.permission.INTERNET` and never transmits data off the device.
- **Minimal, scoped permissions:** Corewatch requests `android.permission.WAKE_LOCK` for user-configured Caffeine keep-awake sessions (enforced with an automatic `< 15%` low-battery safety cutoff), `android.permission.ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, and `CHANGE_WIFI_STATE` for local Wi-Fi link/RSSI/PHY telemetry and manual AP scan triggers, `android.permission.NEARBY_WIFI_DEVICES` (`neverForLocation`) for querying `2.4 / 5 / 6 GHz` regulatory channel availability without Location access, strictly opt-in `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` requested only when the user explicitly unlocks the Surrounding Wi-Fi Access Points card (with BSSIDs masked by default and all SSIDs/BSSIDs excluded from clipboard exports), `android.permission.BLUETOOTH_CONNECT` for paired Bluetooth peripheral and audio codec inspection, optional `android.permission.DUMP` and `android.permission.READ_LOGS` (`development` permissions grantable via `adb shell pm grant`) for Bluetooth stack A2DP/HFP/LE Audio frame-drop counters, Wi-Fi driver regulatory provenance, and LMKD EventLog telemetry, and optional `android.permission.SYSTEM_ALERT_WINDOW` for the translucent Always-On-Top Picture-in-Picture (PiP) Mini-HUD overlay (`FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL`).
- **Backup & transfer isolation:** Application preferences are excluded from cloud backup and device-to-device extraction via `data_extraction_rules.xml` and `backup_rules.xml`.

Areas in scope for security review include:

1. **Defensive kernel node parsing (`SysfsParsers`, `TelemetryCollector`):** Bounded reads (`<= 256` bytes for sysfs attributes, `<= 16 KiB` for `/proc/meminfo`) and exception-free handling of malformed or adversarial values in `/proc/meminfo`, `/sys/devices/system/cpu/`, `/sys/class/thermal/`, and `/sys/class/hwmon/` (verified by 125,000+ property-based fuzz iterations in `CorewatchFuzzTest`).
2. **WakeLock safety (`CaffeineController`, `CaffeineTileService`, `CaffeinePolicy`):** Ensuring timed expirations and the `< 15%` discharging battery cutoff reliably release `PowerManager.WakeLock`.
3. **Diagnostic snapshot sanitization (`DiagnosticReportBuilder`):** Ensuring device/sensor strings copied via `Ctrl+Shift+C` are stripped of control characters and escaped against Markdown table injection, without including personal identifiers, serial numbers, or network addresses.
