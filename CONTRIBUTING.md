# Contributing to Corewatch

Thanks for your interest in contributing to **Corewatch**! Bug reports, hardware compatibility notes, feature ideas, and pull requests are all welcome.

> **Independent Project Notice:** Corewatch is an independent open-source project and is not affiliated with, endorsed by, or sponsored by Google or any employer.

## Ground rules

- By contributing, you agree that your contributions are licensed under the [Apache License, Version 2.0](LICENSE).
- Add new Kotlin source files with the standard SPDX header:

  ```kotlin
  // Copyright 2026 The Corewatch Authors
  // SPDX-License-Identifier: Apache-2.0
  ```

- Never commit signing keys, credentials, proprietary device identifiers, or `local.properties`.

## Development & Verification

1. Build the debug APK and run the full unit, Robolectric Compose UI, and fuzz test suite with JaCoCo coverage before opening a pull request:

   ```bash
   ./gradlew assembleDebug testDebugUnitTest createDebugUnitTestCoverageReport
   ```

2. Maintain **at least 80% line coverage** across all packages and source files (the project currently maintains **97.5% overall line coverage**; see `app/build/reports/coverage/test/debug/index.html`).
3. Keep pure parsing and domain logic (`/proc/meminfo`, `cpufreq`, `thermal_zone`/`hwmon`, battery wattage math, display bottleneck auditing, Caffeine rules, and diagnostic Markdown generation) in `com.droid.corewatch.data` with deterministic unit and property-based fuzz tests.
4. Every user-facing action must have both a mouse/trackpad path and a keyboard shortcut documented in the `F1` shortcuts dialog and `README.md`.

## Reporting bugs

When filing an issue, you can press **`Ctrl+Shift+C`** inside Corewatch to copy an anonymized Markdown Hardware & Thermal Diagnostic Snapshot and paste it directly into your bug report along with steps to reproduce.
