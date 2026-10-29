# Third-party notices

Corewatch's APK (`releaseRuntimeClasspath`) bundles only the open-source libraries listed in the first table below. All of them permit redistribution in source and binary form under the Apache License, Version 2.0, provided that copyright and license notices are retained.

## Runtime Libraries Shipped in `Corewatch.apk`

| Component / Library | Maven Coordinates (`releaseRuntimeClasspath`) | License & Copyright Notice |
| :--- | :--- | :--- |
| **AndroidX Core, Activity, Lifecycle, Window, SavedState & Collections** | `androidx.activity:activity*:1.13.0`, `androidx.annotation:annotation*:1.9.1`, `androidx.arch.core:core*:2.2.0`, `androidx.autofill:autofill:1.0.0`, `androidx.collection:collection*:1.5.0`, `androidx.concurrent:concurrent-futures:1.1.0`, `androidx.core:core*:1.18.0`, `androidx.customview:customview-poolingcontainer:1.0.0`, `androidx.documentfile:documentfile:1.0.0`, `androidx.dynamicanimation:dynamicanimation:1.0.0`, `androidx.emoji2:emoji2:1.4.0`, `androidx.graphics:graphics-path:1.0.1`, `androidx.interpolator:interpolator:1.0.0`, `androidx.lifecycle:lifecycle*:2.10.0`, `androidx.navigationevent:navigationevent*:1.0.0`, `androidx.profileinstaller:profileinstaller:1.4.0`, `androidx.savedstate:savedstate*:1.4.0`, `androidx.startup:startup-runtime:1.1.1`, `androidx.tracing:tracing:1.2.0`, `androidx.transition:transition:1.6.0`, `androidx.versionedparcelable:versionedparcelable:1.1.1`, `androidx.window:window*:1.5.0` | **Apache-2.0** — Copyright (C) The Android Open Source Project |
| **Jetpack Compose UI, Foundation, Animation, Runtime & Material 3** | `androidx.compose:compose-bom:2026.03.01`, `androidx.compose.animation:animation*:1.10.6`, `androidx.compose.foundation:foundation*:1.10.6`, `androidx.compose.material3:material3*:1.4.0`, `androidx.compose.material:material-icons-core*:1.7.8`, `androidx.compose.material:material-icons-extended*:1.7.8`, `androidx.compose.material:material-ripple*:1.10.6`, `androidx.compose.runtime:runtime*:1.10.6`, `androidx.compose.ui:ui*:1.10.6` | **Apache-2.0** — Copyright (C) The Android Open Source Project |
| **Kotlin Standard Library, Coroutines & Serialization Core** | `org.jetbrains.kotlin:kotlin-stdlib:2.3.20`, `org.jetbrains.kotlin:kotlin-stdlib-common:2.3.20`, `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0`, `org.jetbrains.kotlinx:kotlinx-coroutines-core*:1.9.0`, `org.jetbrains.kotlinx:kotlinx-serialization-core*:1.7.3` | **Apache-2.0** — Copyright (c) 2010–2026 JetBrains s.r.o. and Kotlin Programming Language contributors |
| **JetBrains Java Annotations** | `org.jetbrains:annotations:23.0.0` | **Apache-2.0** — Copyright (c) 2000–2022 JetBrains s.r.o. |
| **Guava `ListenableFuture`** (transitive via `androidx.concurrent`) | `com.google.guava:listenablefuture:1.0` | **Apache-2.0** — Copyright (C) The Guava Authors |
| **JSpecify Nullness Annotations** (transitive via AndroidX) | `org.jspecify:jspecify:1.0.0` | **Apache-2.0** — Copyright (c) The JSpecify Authors |

---

## Development & Test-Only Dependencies (Not Shipped in `Corewatch.apk`)

The following libraries are used exclusively during local development, unit/UI/fuzz testing, and code coverage reporting (`testImplementation` / `debugImplementation`) and are **not** packaged into release builds:

| Library | Maven Coordinates | License |
| :--- | :--- | :--- |
| **JUnit 4** | `junit:junit:4.13.2` | Eclipse Public License 1.0 (`EPL-1.0`) |
| **Robolectric** | `org.robolectric:robolectric:4.16` | MIT License / Apache-2.0 |
| **AndroidX Test & Compose UI JUnit4** | `androidx.test:core:1.7.0`, `androidx.test.ext:junit:1.3.0`, `androidx.test.espresso:espresso-core:3.7.0`, `androidx.compose.ui:ui-test-junit4:1.10.6`, `androidx.compose.ui:ui-test-manifest:1.10.6`, `androidx.compose.ui:ui-tooling:1.10.6` | Apache-2.0 |
| **Kotlinx Coroutines Test** | `org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2` | Apache-2.0 |
| **JaCoCo Code Coverage Agent** | `org.jacoco:org.jacoco.agent:0.8.13` | Eclipse Public License 2.0 (`EPL-2.0`) |

---

## Regenerating the Dependency Tree

To inspect or regenerate the exact runtime dependency tree:

```bash
./gradlew :app:dependencies --configuration releaseRuntimeClasspath
```

## Apache License, Version 2.0

All runtime dependencies in `Corewatch.apk` are licensed under the Apache License, Version 2.0. The full text of the license is available in [LICENSE](LICENSE).
