<p align="center">
  <img src="docs/images/icon.svg" width="112" alt="Corewatch icon: a cyan telemetry waveform on a dark slate badge" />
</p>

<h1 align="center">Corewatch</h1>

<p align="center">
  <b>Compact system telemetry, CPU/GPU/NPU compute monitor, thermal/battery wattage inspector, Variable Refresh Rate (VRR) &amp; frame-jank auditor, split Wi-Fi 7 &amp; 2.5GbE/10GbE Wired Ethernet + Bluetooth audio codec/peripheral inspector, multi-tier USB &amp; Type-C dock/peripheral topology auditor, LMKD/PSI memory monitor, and "Caffeine" keep-awake utility for Googlebooks.</b><br>
  Real-time thermal headroom, CPU model identification &amp; per-core P/E CPU frequencies/C-state residency, live GPU (Render + Media engine) &amp; NPU hardware accelerator counters, USB-C PD battery wattage (<code>W = V × I</code>), Variable Refresh Rate (VRR) 5-minute time-in-state residency &amp; VRR-aware frame jank profiler, dedicated side-by-side Wi-Fi 7 (<code>802.11be</code>) &amp; dynamic Wired Ethernet (<code>2.5 GbE</code> / <code>1 GbE</code> / <code>10 GbE</code>) link + per-interface throughput cards, Bluetooth audio codec/dropped-frame telemetry, multi-tier xHCI USB &amp; Thunderbolt dock topology + speed/power bottleneck detection, memory/<code>zram</code> swap/LMKD/PSI pressure, configurable sampling rates (<code>250ms | 500ms | 1s | 2s</code>), and a translucent Always-On-Top 340×398dp Mini-HUD: one universal download for Intel and Snapdragon Googlebooks.
</p>

<p align="center">
  <a href="../../releases/latest/download/Corewatch.apk"><b>⬇ Download Corewatch.apk</b></a>
  &nbsp;·&nbsp; <a href="#what-it-is">Features</a>
  &nbsp;·&nbsp; <a href="#mini-hud-and-workbench">Mini-HUD &amp; Workbench</a>
  &nbsp;·&nbsp; <a href="#caffeine-and-power">Caffeine &amp; Power</a>
  &nbsp;·&nbsp; <a href="#made-for-the-googlebook">Googlebook Design</a>
  &nbsp;·&nbsp; <a href="#keyboard-shortcuts">Shortcuts</a>
  &nbsp;·&nbsp; <a href="#limitations">Limitations</a>
  &nbsp;·&nbsp; <a href="#install">Install</a>
  &nbsp;·&nbsp; <a href="#privacy">Privacy</a>
  &nbsp;·&nbsp; <a href="#how-its-built">Architecture</a>
  &nbsp;·&nbsp; <a href="#licenses">Licenses</a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Googlebook_OS-Android_15+-133F43" alt="Googlebook OS, Android 15+" />
  <img src="https://img.shields.io/badge/developed_on-a_Googlebook-E0407E?style=flat" alt="Developed on a Googlebook" />
  <img src="https://img.shields.io/badge/developed_for-Googlebooks-1F6F66" alt="Developed for Googlebooks" />
  <img src="https://img.shields.io/badge/license-Apache_2.0-555555" alt="Apache 2.0 license" />
</p>

<p align="center">
  <sub>A personal passion project, proudly developed on and for a Googlebook in its Linux Terminal.<br>
  Not affiliated with, endorsed by, or sponsored by Google or any employer (<a href="#about-this-project">more</a>).</sub>
</p>

<p align="center">
  <img src="docs/images/workbench_light.png" width="900" alt="Corewatch Full Workbench in Light Theme showing the Thermals & Compute tab with live thermal, wattage, and CPU pills in the desktop caption bar, per-core P/E CPU frequency bars, and GPU & NPU hardware accelerator cards" />
  <br>
  <sub><b>Thermals &amp; Compute</b> workbench in Alabaster Light theme: live caption-bar telemetry pills, per-core P/E CPU frequencies, and dedicated GPU &amp; NPU hardware accelerator cards.</sub>
</p>

---

## What it is

**Corewatch** (`com.droid.corewatch`) brings the high-density hardware telemetry of **macOS Activity Monitor / iStat Menus**, **CoconutBattery**, and **Amphetamine** to Android laptops and Googlebooks. Built in modern Kotlin with Jetpack Compose and Material 3, Corewatch gives power users instant visibility into thermal headroom, per-core CPU frequencies, GPU & NPU hardware accelerators, USB-C PD charging wattage, memory/`zram` swap pressure, and external monitor negotiation—without oversized mobile typography:

* **Dual-layout desktop UI & Translucent Always-On-Top PiP Mini-HUD (`Ctrl+M`):**
  * **Full Workbench (`1120dp × 760dp`):** Live thermal/wattage/CPU telemetry badges drawn directly into Android 15's desktop caption bar with seven diagnostic tabs (`Thermals & Compute`, `Battery & W`, `Memory & Swap`, dynamically singular/plural `Display` / `Displays`, `Network`, `Bluetooth`, and `USB & Peripherals`), a **configurable polling rate selector (`250ms | 500ms | 1s | 2s`)**, and quick actions immediately below.
  * **Translucent Always-On-Top Mini-HUD (`340dp × 398dp`):** Ultra-dense floating Picture-in-Picture (`TYPE_APPLICATION_OVERLAY`) 5-tile sparkline dashboard (**CPU**, **GPU & NPU**, **Thermal**, **Battery**, and **RAM & `zram` Swap**) with **adjustable translucency (`88%` / `75%` / `100%`)**, drag-to-reposition header, and one-click `"↗ Full"` restore, using crisp `11sp`/`12sp` tabular monospace typography (`fontFeatureSettings = "tnum"`).
* **Thermal, per-core CPU, GPU & NPU hardware accelerator telemetry:**
  * Displays real-time **Thermal Status** (`Nominal`, `Light Throttling`, `Moderate Throttling`, `Severe Throttling`, `Critical Thermal`), predictive **Thermal Headroom** (`PowerManager.getThermalHeadroom(10)`), battery temperature (`°C` / `°F`), and world-readable `/sys/class/thermal/` and `/sys/class/hwmon/` sensors.
  * Identifies the exact **CPU Model Name** (e.g. `Intel Core Ultra 7 355` from `/proc/cpuinfo` or `Build.SOC_MODEL`) alongside live per-core CPU frequency bars and 60-second sparklines reading `/sys/devices/system/cpu/cpu*/cpufreq/scaling_cur_freq` and hardware C-state idle residency (`/sys/devices/system/cpu/cpu*/cpuidle/state*/time`), automatically classifying **Performance (`P-Core`)** and **Efficiency (`E-Core`)** clusters on hybrid Intel Core Ultra and Qualcomm Snapdragon processors.
  * **GPU & NPU Accelerator Counters:** Tracks live **GPU** active duty cycle (from `/sys/class/drm/card0/device/tile0/gt0/gtidle/idle_residency_ms`, Intel `i915` `rc6_residency_ms`, or Qualcomm Adreno `/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage`), GuC SLPC operating vs. slice clocks (`cur_freq`, `act_freq`, `rpe_freq`, `rp0_freq`), **Intel Xe Media Engine (`tile0/gt1/freq0`) clock**, power profile, and hardware throttle reasons (`PL1`, `PL2`, `PL4`, `PROCHOT`, `THERMAL`), alongside **NPU** telemetry via unprivileged `/dev/accel0` DRM/Accel ioctls (`DRM_IOCTL_VERSION` & `DRM_IOCTL_IVPU_GET_PARAM` for PCI device/revision, max DPU clock rate, firmware version, zero-wakeup active context tracking via `DRM_IVPU_PARAM_CONTEXT_ID`, and engine heartbeat) and `/sys/class/accel/accel0/device/` counters.
* **Live battery wattage (`W = V × I`), USB-C PD profiler & drain tracker:**
  * Computes signed real-time power flow in Watts from `EXTRA_VOLTAGE` and `BATTERY_PROPERTY_CURRENT_NOW` / `BATTERY_PROPERTY_CURRENT_AVERAGE` (auto-normalizing microamps vs. milliamps across fuel-gauge drivers), displaying e.g. **`+46.6 W (USB-C PD / AC)`** or **`-8.4 W (Discharging — Est. 6h 15m remaining)`**.
  * Reports battery health, cycle count (`EXTRA_CYCLE_COUNT`), voltage (`mV`), current (`mA`), capacity (`%`), energy counter (`Wh`), and a 60-second session power curve.
* **"Caffeine" Keep-Awake controller (`Ctrl+K`) & Quick Settings Tile:**
  * One-click toggle in the caption bar and in the system Quick Settings tray (`CaffeineTileService`) to prevent the Googlebook from dimming or sleeping during compilations, large file transfers, or docked presentations.
  * Configurable timer modes (**30m**, **1h**, **Indefinitely**) plus smart auto-triggers (*Keep awake while charging on AC* and *Keep awake while an external display is connected*) with an automatic **`< 15%` low-battery safety cutoff**.
* **Variable Refresh Rate (VRR) 5-minute time-in-state, VRR-aware frame jank & external display auditor:**
  * Queries `DisplayManager.getDisplays()` and unprivileged `/dev/dri/card0` KMS properties (`DRM_IOCTL_MODE_GETCONNECTOR` & `DRM_IOCTL_MODE_OBJ_GETPROPERTIES`) to inspect the built-in eDP panel (`BOE NB140B91-ML1`, `12 bpc`) and every connected external monitor (`HDMI` / `USB-C DP Alt Mode`).
  * Reports **Variable Refresh Rate (VRR) & Cadence** telemetry: hardware `vrr_capable` and CRTC `VRR_ENABLED` states, Android Adaptive Refresh Rate (`hasArrSupport`), dynamic refresh range & discrete steps (e.g. **`20–120 Hz (20, 24, 30, 40, 60, 120 Hz)`**), live SurfaceFlinger render frame rate vs. physical panel VSync cadence (`Render 60 Hz · VSync 120 Hz`), **rolling 5-minute refresh-rate time-in-state residency bars & switch counter**, HDR capabilities (`Dolby Vision`, `HDR10`, `HDR10+`, `HLG`), and a **30Hz Dock Bottleneck Warning** whenever a `>= 1440p` external monitor is negotiated below `50Hz`.
  * **Dropped & Janky Frames (5m · VRR-Aware):** Evaluates per-frame `Window.OnFrameMetricsAvailableListener` deadlines (`FrameMetrics.DEADLINE`, CPU stage durations, and `GPU_DURATION`) against the active VRR cadence over a rolling 5-minute window, breaking down **Dropped Frames %**, **Janky Frames %**, a 60-second Jank % sparkline, and **Jank Causes** (`Long CPU`, `Long GPU`, `Buffer Stuffing`, `Missed VSync`).
* **Dedicated Network Tab — Wi-Fi 7 (`802.11be`), Dynamic Wired Ethernet (`10 GbE` / `2.5 GbE` / `1 GbE`), Wi-Fi Regulatory Capabilities & Verbose AP Scanner (`Ctrl+5`):**
  * **Dedicated Wi-Fi Radio & Link (`wlan*`):** Uses non-intrusive install-time `ACCESS_NETWORK_STATE` and `ACCESS_WIFI_STATE` permissions (zero `INTERNET` permission) to inspect `wlan*` even while docked on Wired Ethernet (`Standby (Ethernet Primary)` vs. `Primary Default Route`), reporting **Wi-Fi Standard** (`Wi-Fi 7 (802.11be)`, `Wi-Fi 6/6E (802.11ax)`, `Wi-Fi 5 (802.11ac)`), **Band & Channel** (e.g. `5 GHz · Ch 149 (5745 MHz)` or `6 GHz`), **RSSI (`dBm`) & Signal Quality %** with a 60-second RSSI sparkline, **TX / RX PHY Link Speeds (`Mbps`)**, **WPA3-SAE / OWE Security**, **Wi-Fi 7 MLO link count**, **per-interface `wlan0` `TrafficStats` `Mbps` & `pkt/s`**, `IPv4`/`IPv6` dual-stack & `MTU`, and **Wi-Fi MAC Frame Counters** (`TX Good/s`, `RX Good/s`, `TX Retries/s`, `TX Dropped/s`).
  * **Dynamic Wired Ethernet Link (`eth*` / `en*`) & Aggregate Throughput:** Dynamically surfaces a dedicated **Wired Ethernet Link** card alongside **Wi-Fi Radio & Link** only when a physical USB-C / Thunderbolt / RJ45 Ethernet link is connected (collapsing cleanly so Wi-Fi spans full width when undocked), filtering out virtual VM/container/VPN bridges (`avf_tap_*`, `veth*`, `docker*`, `tun*`) and reporting **IEEE 802.3 Speed Tier** (`10 GbE (10GBASE-T)`, `5 GbE (5GBASE-T)`, `2.5 GbE (2500BASE-T)`, `1 GbE (1000BASE-T)`, `100 Mbps (100BASE-TX)`), a **100 Mbps Dock Bottleneck Warning** when a USB 2.0 hub or cable caps a real hardware dock at `100BASE-TX`, **per-interface `eth*` `TrafficStats` `Mbps` & `pkt/s`** with a 60-second Ethernet throughput sparkline, `Dual-Stack (IPv4 + IPv6)`, `Private DNS (DoT)`, `MTU`, and `Wake-on-LAN` support—above an aggregate **Network Throughput (`TrafficStats` RX / TX)** card displaying the active `Default Route`.
  * **Device Wi-Fi Radio & Regulatory Capabilities:** Compares hardware radio capabilities alongside your active link—showing supported **IEEE 802.11 generations** (`Wi-Fi 7 11be`, `Wi-Fi 6E/6 11ax`, `Wi-Fi 5 11ac`, `Wi-Fi 4 11n`, `11a/b/g`, `WiGig 11ad`), **supported bands** (`2.4 / 5 / 6 / 60 GHz`), **max supported TX/RX PHY link speeds**, **security capabilities** (`WPA3-SAE`, `SAE-H2E`, `SAE-PK`, `OWE`, `WPA3-Ent 192-bit`, `EAP-TLS 1.3`, `TOFU`, `DPP`, `WPA2`), **radio concurrency** (`DBS`, `MLO T2LM`, `Make-Before-Break`, `STA+AP`), **active regulatory country code & provenance** (`dumpsys wifi` driver regdomain, `WifiManager`, `802.11d` AP beacon, telephony, or locale fallback), and a 3-band **`2.4 GHz` / `5 GHz (+DFS & TDWR)` / `6 GHz (U-NII-5..8 + PSC)` allowed & usable STA channel table** (via `NEARBY_WIFI_DEVICES` with `neverForLocation`—zero Location permission needed), including automatic **6 GHz Regulatory Block Warnings** when `wireless-regdb`, Intel LAR, or an unresolved `'00'` world domain disables 6 GHz channels on 6 GHz-capable hardware.
  * **Surrounding Wi-Fi Access Points (Verbose Beacon Scanner):** Unlike Android Settings (which lists only SSIDs), displays a filterable (`All` / `2.4 GHz` / `5 GHz` / `6 GHz`), sortable (`Signal dBm` / `Band & Ch` / `SSID`) beacon table showing **SSID**, **BSSID** (privacy-masked `aa:bb:cc:••:••:ff` by default with a 1-click `Full` toggle), **band, channel (`[DFS]` on 5 GHz, `[PSC]` on 6 GHz), primary & center frequency, channel bandwidth (`20 / 40 / 80 / 160 / 80+80 / 320 MHz`), Wi-Fi generation (`Wi-Fi 7 / 6E / 6 / 5 / 4`), AKM security & roaming badges (`WPA3-SAE`, `SAE-H2E`, `OWE`, `PMF`, `11r`, `11k`, `11v`, `MLO`, `TWT`, `11mc/11az`), `802.11d` Country IE, and `802.11e` QBSS station count & channel utilization %** (opt-in Location permission only when requested by the user; never exported).
* **Dedicated Bluetooth Tab — Audio Codec, Per-Profile Dropped Frames & Peripherals (`Ctrl+6`):**
  * **Bluetooth Radio & Negotiated Audio Codec:** Reports adapter radio state, controller hardware capabilities (`LE Audio (LC3)`, `Auracast`, `LE 2M PHY`, `LE Coded PHY`, max concurrent audio sinks), active audio output routing (`AudioManager.getDevices`), negotiated **Bluetooth A2DP / LE Audio codec** (`SBC`, `AAC`, `aptX`, `aptX HD`, `LDAC`, `LC3 (LE Audio)`, `Opus`), sample rate (`44.1`–`96.0 kHz`), bit depth (`16`/`24`/`32-bit`), channel mode, bitrate/quality (`990 kbps` LDAC, `243 kbps` SBC Bitpool 37, etc.), and headset selectable codecs.
  * **Bluetooth Audio Dropped Frames & Buffer Underflows:** Dedicated 3-profile telemetry card tracking **A2DP Media** (`enc drops`, `TxQ drops`, `dropouts`, `underflows`, and `AVDTP Sink Delay ms`), **HFP / SCO Hands-Free Voice** (`pkt drops`, `underflows`, and speech codec `WBS mSBC 16 kHz` / `SWB LC3 32 kHz`), and **LE Audio (`CIS / BIS`)** (`underflows`, `ISO drops`, and controller `ISO Credits × Buffer Bytes`).
  * **Connected & Paired Bluetooth Peripherals:** Inspects bonded `BLE GATT / HID` and `Dual BR/EDR + LE` peripherals, negotiated profile badges, and live peripheral battery percentages.
* **USB & Type-C Topology, Dock Bottleneck & Peripheral Inspector (`Ctrl+7`):**
  * **Unprivileged Multi-Tier xHCI & Dock Tree (`/sys/bus/usb/devices`):** Reconstructs the live physical USB bus hierarchy across USB 2.0, USB 3.2 Gen 1/2/2×2, and USB4 / Thunderbolt xHCI controllers, multi-tier dock sub-hubs, and downstream external peripherals out-of-the-box on retail builds with zero ADB permissions—defaulting to **External Only** so attached docks, NICs, DACs, storage drives, and input gear stand out immediately, with a 1-click **Include Internal (`Ctrl+Shift+I`)** toggle to reveal fixed motherboard USB devices (integrated UVC webcam, Bluetooth controller, security MCU).
  * **Link Speed, Lane Width, Power Budget & Bottleneck Detection:** Displays negotiated speed (`1.5 / 12 / 480 Mbps`, `5 / 10 / 20 / 40 Gbps`), `tx_lanes × rx_lanes`, `bcdUSB` descriptor revision, `bMaxPower` (`mA`) vs. self-powered/bus-powered hub status, USB-IF interface classes, and bound Linux kernel drivers (`xhci_hcd`, `hub`, `r8152`, `cdc_ncm`, `uas`, `usb-storage`, `snd-usb-audio`, `uvcvideo`, `usbhid`, `ch341`, `ftdi_sio`, `cp210x`), automatically flagging **SuperSpeed Downgrades** (USB 3.x capable drive or NIC capped at `480 Mbps` on a USB 2.0 cable/port), **Standalone USB-C Billboard (`0x11`) DP/TBT Alt-Mode Fallbacks**, and **Bus-Powered Hub Over-Current Risks** (`> 500 mA` on USB 2.0 / `> 900 mA` on USB 3.x).
  * **Android Subsystem Correlation, Type-C Port Roles & Hotplug Log:** Enriches USB nodes with live `UsbManager`, `InputManager` (input sources + peripheral battery %), `AudioManager` (USB DAC/headset max PCM kHz, bit depth, channels), `CameraManager` (external UVC stream count), and `StorageManager` (removable USB volume mount state), provides 1-click companion-hub (`USB2 ↔ USB3`) and cross-tab (`Displays` / `Network` / `Bluetooth`) jump chips, unlocks per-port Type-C power/data roles (`DFP`/`UFP`, `Source`/`Sink`, DP Alt-Mode) when optional `android.permission.DUMP` is granted, and maintains a bounded 64-event in-memory session hotplug & bottleneck log.
* **Memory + `zram` swap + LMKD / PSI pressure & 1-click Markdown diagnostic copy (`Ctrl+Shift+C`):**
  * Parses `/proc/meminfo` (`MemTotal`, `MemAvailable`, `Cached`, `Buffers`, `SwapTotal`, `SwapFree`, `Dirty`), `ActivityManager.MemoryInfo` (**LMKD / AMS low-memory threshold** & `lowMemory` flag), **rolling 5-minute LMKD (Low Memory Killer Daemon) invocations** (via `EventLog` `am_kill` / `am_low_memory` when `READ_LOGS` is granted, or `ActivityManager.getHistoricalProcessExitReasons` + `onTrimMemory` fallback), and Linux `/proc/pressure/memory` PSI (`some` / `full` 10s averages) alongside live network throughput (`TrafficStats` RX/TX `Mbps`) and internal storage capacity.
  * Generates a clean, anonymized Markdown hardware & thermal diagnostic table on the clipboard for pasting directly into community forum threads or bug reports.

---

<h2 id="mini-hud-and-workbench">Mini-HUD (Translucent Always-On-Top PiP) &amp; Full Workbench</h2>

Corewatch transitions seamlessly between a multi-column desktop workbench and a translucent Always-On-Top Picture-in-Picture (PiP) Mini-HUD:

* **Translucent Always-On-Top Mini-HUD (`Ctrl+M`):** Toggling Mini-HUD (`Ctrl+M` or the title-bar Mini-HUD button) spawns a floating, Always-On-Top `340dp × 398dp` overlay (`TYPE_APPLICATION_OVERLAY` when "Display over other apps" is granted, or a translucent compact freeform window otherwise) with **clickable opacity cycling (`88%` → `75%` → `100%`)**, drag-anywhere positioning, and five stacked 60-second sparkline cards (**CPU**, **GPU & NPU**, **Thermal**, **Battery Wattage**, and **RAM & `zram` Swap**) that fit cleanly without scrolling. Clicking **`↗ Full`** immediately restores the Full Workbench window.
* **Zero-allocation ring buffers:** Time-series histories use fixed-capacity primitive circular buffers (`FloatRingBuffer`), rendering 60-second `Canvas` line and area sparklines at 1Hz without allocating `Path` objects on the UI thread.
* **Jitter-free tabular typography:** Every numeric metric uses OpenType tabular numerals (`tnum`) so live MHz frequencies, wattages, and throughput counters never shift horizontally as values update.

<p align="center">
  <img src="docs/images/mini_hud.png" width="900" alt="Corewatch Translucent Always-On-Top Mini-HUD PiP overlay in Dark Mode and Light Mode at 88% opacity showing all 5 stacked sparkline cards (CPU, GPU & NPU, Thermal, Battery, RAM & zram Swap) over the desktop" />
  <br>
  <sub>Translucent Always-On-Top Mini-HUD PiP overlay (<code>88%</code> opacity, toggleable <code>88% / 75% / 100%</code>) in Dark Mode (left) and Light Mode (right) fitting all 5 telemetry tiles without scrolling.</sub>
</p>

<p align="center">
  <img src="docs/images/workbench_memory.png" width="900" alt="Corewatch Memory & Swap Workbench tab showing Physical RAM (/proc/meminfo), zram / Swap Pressure, Internal User Storage, and LMKD Invocations & PSI Pressure" />
  <br>
  <sub><b>Memory &amp; Swap</b> tab showing physical RAM breakdown (<code>/proc/meminfo</code>), <code>zram</code> swap pressure, internal storage capacity, and rolling 5-minute <b>LMKD Invocations &amp; PSI Pressure</b>.</sub>
</p>

---

<h2 id="caffeine-and-power">Caffeine Keep-Awake &amp; USB-C Power Telemetry</h2>

Long compilations, container builds, and docked multi-monitor sessions shouldn't be interrupted by aggressive mobile screen-off timers or silent USB-C hub power drops:

* **Caption-bar & Quick Settings integration:** Toggle Caffeine from Corewatch's title bar (`Ctrl+K`) or add the **Corewatch Caffeine** tile to Android's system Quick Settings panel for instant access even when Corewatch is closed.
* **Rule-based keep-awake automation:** Enable **Auto-enable on AC / USB-C PD charging** or **Auto-enable when external monitor is attached** so your Googlebook stays awake automatically when docked at your desk and resumes normal battery-saving sleep the moment you unplug.
* **Low-battery protection:** Even if Indefinite mode or an external-display rule is active, Corewatch automatically releases its wake lock if battery charge drops below **15%** while discharging.

---

<h2 id="made-for-the-googlebook">Made for the Googlebook</h2>

Mobile system monitor apps were designed for narrow portrait phone screens with oversized cards and no awareness of desktop windowing. Corewatch is engineered specifically for Googlebook laptops and desktop setups:

* **Native Android 15+ desktop caption bar:** Corewatch opts into freeform caption-bar inset handling (`WindowInsets.Type.captionBar()`), dynamically keeping navigation tabs anchored on the left and status/action buttons clear of the system minimize/maximize/close controls across any window resize.
* **Keyboard-first workflow:** Every tab, mode switch, pause/resume toggle, counter reset, and clipboard export has a dedicated keyboard shortcut. Press `F1` or `?` anytime to view the interactive shortcut cheatsheet.
* **Expressive Dark, Light & System themes:** High-contrast Carbon Slate Dark theme by default for low glare in a corner HUD, plus crisp Alabaster Light and automatic System theme modes.

<p align="center">
  <img src="docs/images/workbench_overview.png" width="900" alt="Corewatch Full Workbench in Carbon Slate Dark Theme showing the Thermals & Compute tab under live CPU, GPU (2500 MHz turbo), and NPU (1944 / 2000 MHz turbo) compute load" />
  <br>
  <sub>Carbon Slate Dark theme on the <b>Thermals &amp; Compute</b> tab with live thermal headroom, per-core P/E CPU clusters, and 60-second GPU &amp; NPU accelerator telemetry under active compute load.</sub>
</p>

<p align="center">
  <img src="docs/images/workbench_displays.png" width="900" alt="Corewatch Displays tab showing connected internal eDP and external 4K displays, 5-minute Variable Refresh Rate (VRR) time-in-state residency bars, and the 5-minute VRR-aware Dropped & Janky Frames card" />
  <br>
  <sub><b>Display / Displays</b> tab (dynamically singular or plural based on connected display count) inspecting hardware <b>Variable Refresh Rate (VRR) 5-minute time-in-state residency</b> and <b>Dropped &amp; Janky Frames (5m · VRR-Aware)</b>.</sub>
</p>

<p align="center">
  <img src="docs/images/workbench_network.png" width="900" alt="Corewatch Network tab showing side-by-side Wi-Fi Radio & Link (Wi-Fi 7 802.11be on wlan0 in Standby) and dynamic Wired Ethernet Link (2.5 GbE 2500BASE-T on eth1 as Primary Default Route) cards, aggregate Network Throughput, and Device Wi-Fi Radio & Regulatory Capabilities" />
  <br>
  <sub><b>Network</b> tab (<code>Ctrl+5</code>) while docked: side-by-side <b>Wi-Fi Radio &amp; Link</b> (<code>wlan0</code> · <code>Wi-Fi 7 (802.11be)</code> · <code>-38 dBm</code> · <code>Standby</code>) and dynamic <b>Wired Ethernet Link</b> (<code>eth1</code> · <code>2.5 GbE (2500BASE-T)</code> · <code>Primary Default Route</code> · <code>Dual-Stack</code> · <code>Private DNS</code>), aggregate <b>TrafficStats RX/TX</b> throughput, and <b>Device Wi-Fi Radio &amp; Regulatory Capabilities</b>.</sub>
</p>

<p align="center">
  <img src="docs/images/workbench_bluetooth.png" width="900" alt="Corewatch Bluetooth tab showing dedicated cards for Bluetooth Radio & Audio Codec, Bluetooth Audio Dropped Frames & Buffer Underflows (A2DP, HFP/SCO, LE Audio), and Connected & Paired Bluetooth Peripherals" />
  <br>
  <sub><b>Bluetooth</b> tab (<code>Ctrl+6</code>): dedicated cards for <b>Bluetooth Radio &amp; Audio Codec</b> (<code>LE Audio LC3</code> · <code>Auracast</code> · <code>LE 2M/Coded PHY</code>), <b>Bluetooth Audio Dropped Frames &amp; Buffer Underflows</b> (<code>A2DP</code> · <code>HFP/SCO</code> · <code>LE Audio CIS/BIS</code>), and <b>Connected &amp; Paired Bluetooth Peripherals</b>.</sub>
</p>

<p align="center">
  <img src="docs/images/workbench_usb_peripherals.png" width="900" alt="Corewatch USB & Peripherals tab showing live Type-C port roles (PORT0..PORT2 Host Source/Sink PD In), multi-tier xHCI 20 Gbps and 10 Gbps dock hub topology tree, External Only vs Include Internal toggle, and Node & Endpoint Inspector with USB 3.2 Gen 1 5 Gbps link lanes, 288 mA power budget, UsbManager correlation, and kernel driver r8152" />
  <br>
  <sub><b>USB &amp; Peripherals</b> tab while docked: live <b>Type-C Port Roles &amp; Alt-Mode Telemetry</b> (<code>PORT0..PORT2</code> · <code>Host · Sink (PD In)</code> · <code>+46.7 W</code>), multi-tier <b>xHCI 20 Gbps / 10 Gbps Dock Topology</b> tree (defaulting to <b>External Only</b> with a 1-click <code>Include Internal</code> toggle), and side-by-side <b>Node &amp; Endpoint Inspector</b> showing negotiated lanes, <code>bMaxPower</code> draw, <code>UsbManager</code> correlation, and bound Linux kernel driver (<code>drv=r8152</code>).</sub>
</p>

---

<h2 id="keyboard-shortcuts">Desktop Keyboard Shortcuts</h2>

| Shortcut | Action |
| :--- | :--- |
| `Ctrl+M` | Toggle between **Full Workbench** and **Compact Mini-HUD** mode |
| `Ctrl+1` .. `Ctrl+7` | Switch tabs: `1` Thermals & Compute, `2` Battery & Power, `3` Memory & Storage, `4` Display(s), `5` Network, `6` Bluetooth, `7` USB & Peripherals |
| `Ctrl+K` | Toggle **Caffeine Keep-Awake** active state (`Off` ↔ `Indefinite`) |
| `Space` | Pause / Resume live telemetry sampling and graph scrolling to inspect a spike |
| `Ctrl+Shift+C` | Copy anonymized Markdown System & Thermal Diagnostic Snapshot to clipboard |
| `Ctrl+Shift+S` | Trigger an active Wi-Fi scan on the **Network** tab |
| `Ctrl+Shift+B` | Toggle surrounding AP BSSID privacy masking (`aa:bb:cc:••:••:ff` ↔ full MAC) |
| `Ctrl+Shift+I` | Toggle internal motherboard USB devices (`External Only` ↔ `Include Internal`) on **USB & Peripherals** |
| `F5` / `Ctrl+R` | Reset session min/max/average telemetry counters, sparkline history, and USB hotplug log |
| `F1` / `?` | Open the keyboard shortcuts cheatsheet dialog |

---

<h2 id="limitations">Known Limitations</h2>

Because Corewatch runs as a standard, unprivileged open-source Android application without root access or platform OS signing keys, a few Android SELinux (`untrusted_app`) security boundaries apply:

* **Vendor-Specific Thermal Zones & Fan RPMs:** Corewatch always reports official Android thermal status (`PowerManager.getCurrentThermalStatus()`), predictive thermal headroom (`PowerManager.getThermalHeadroom()`), battery temperature (`BatteryManager.EXTRA_TEMPERATURE`), and all world-readable `/sys/class/thermal/` and `/sys/class/hwmon/` nodes. On hardware where specific embedded-controller (EC) fan tachometers or vendor thermal zones are restricted by SELinux policy, Corewatch gracefully falls back to the standard `PowerManager` and `BatteryManager` thermal envelope telemetry.
* **Per-Process CPU Breakdown:** Android 10+ restricts `/proc/[pid]/stat` enumeration across other applications' processes for untrusted packages. Corewatch therefore focuses on hardware-level per-core CPU frequency scaling (`/sys/devices/system/cpu/cpu*/cpufreq/`), system-wide memory and `zram` swap pressure (`/proc/meminfo`), and total device network throughput (`TrafficStats`).
* **Optional ADB Diagnostics (`DUMP` & `READ_LOGS`):** All CPU/GPU/NPU compute, thermal, USB-C PD wattage, KMS VRR display, Wi-Fi 7 PHY, Bluetooth codec/peripheral, and `/sys/bus/usb/devices` multi-tier USB/dock topology telemetry works out-of-the-box on standard retail (`user`) builds with zero ADB setup. Android isolates per-profile Bluetooth stack encoder/PLC/ISO drop counters, per-port Type-C `UsbPortManager` power/data role status (`android.permission.DUMP`), and system-wide LMKD `EventLog` kills (`android.permission.READ_LOGS`) behind `development` permissions; when ungranted, Corewatch falls back cleanly (`ActivityManager` historical exit reasons + `/proc/pressure/memory` PSI for LMKD, and 1-click **Copy ADB Command** chips on the Bluetooth and USB & Peripherals tabs).

---

<h2 id="install">Install</h2>

**[⬇ Download Corewatch.apk](../../releases/latest/download/Corewatch.apk)** (universal `1.1 MB` R8-minified APK with 16KB page-aligned `x86_64` and `arm64-v8a` ioctl bridges for Intel and Snapdragon Googlebooks).

1. **Download the APK:** Click the link above to download **`Corewatch.apk`**.
2. **Open the file:** Click the download notification in Chrome, or open `Corewatch.apk` in Wayfinder / Files from your `Downloads` folder.
3. **Allow install:** If Android asks to permit installing unknown apps, click **Settings**, enable **Allow from this source**, and tap **Install**.
4. **Optional Quick Settings Tile:** Open the system Quick Settings tray, click the edit (pencil) icon, and drag the **Corewatch Caffeine** tile into your active tiles for one-click keep-awake control from anywhere.

> **Updating:** Install newer versions of `Corewatch.apk` directly over your existing installation. Your Caffeine auto-trigger preferences will remain preserved.

---

<h2 id="privacy">Privacy &amp; Security</h2>

* **Zero internet permissions, zero telemetry, zero ads:** Corewatch does **not** request `android.permission.INTERNET` and contains no analytics, crash reporters, advertising SDKs, or third-party tracking scripts. All hardware and RF telemetry stays 100% in memory on your device and can never be transmitted over a network.
* **Minimal, transparent permission footprint:** Requests `android.permission.WAKE_LOCK` (for the user-controlled Caffeine controller), install-time `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` & `CHANGE_WIFI_STATE` (for Wi-Fi 7 PHY link speed, RSSI, MAC retry/drop counters, and manual AP scan triggers), `NEARBY_WIFI_DEVICES` (`android:usesPermissionFlags="neverForLocation"`, used without Location access to query allowed/usable `2.4 / 5 / 6 GHz` regulatory channels), `BLUETOOTH_CONNECT` (for inspecting negotiated A2DP/LE Audio codecs and connected Bluetooth peripheral battery levels, without `BLUETOOTH_SCAN`), **strictly opt-in** `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` (requested *only* if you click **Grant Location for AP Scan** in the Surrounding Wi-Fi Access Points card because Android gates `WifiManager.getScanResults()` behind Location), and optional `android.permission.SYSTEM_ALERT_WINDOW` (used solely to float the translucent Always-On-Top Mini-HUD PiP overlay when Mini-HUD mode is toggled).
* **Anonymized diagnostic snapshots & zero USB serial persistence:** Corewatch never reads `/sys/bus/usb/devices/*/serial` or `UsbDevice.getSerialNumber()`, keeps its 64-event USB hotplug log strictly in RAM, and exports only aggregate counts, hardware model, CPU architecture, OS version, thermal/power/memory metrics, regulatory band counts, and display modes via `Ctrl+Shift+C`—never surrounding Wi-Fi SSIDs, BSSIDs, USB serial numbers, MAC/IP addresses, account names, or personal file paths. All strings are sanitized against Markdown table and control-character injection.
* **Strict backup exclusions:** App preferences are excluded from cloud backups and device-to-device transfers (`data_extraction_rules.xml` & `backup_rules.xml`).
* **Audited, fuzz-tested & high code coverage:** Backed by automated unit tests, headless Robolectric Compose UI tests across desktop and Mini-HUD window sizes (**89.1% overall line coverage**, **98.2% class coverage**), and property-based fuzzers (**140,000+ iterations** across `/proc/meminfo`, `cpufreq`, `thermal_zone`/`hwmon`, `/sys/bus/usb/devices` + `dumpsys usb`, battery wattage math, display mode auditing, and Markdown escaping). See [SECURITY.md](SECURITY.md) for vulnerability reporting.

---

<h2 id="how-its-built">How it's built</h2>

Corewatch is written in 100% modern Kotlin with Jetpack Compose:

```mermaid
flowchart LR
    subgraph Sources["Unprivileged Android 15/16, Sysfs & DRM/Accel Telemetry"]
        PowerMgr["PowerManager\n(Thermal Headroom & Status)"]
        BattMgr["BatteryManager\n(Current uA, Voltage mV,\nTemp, Cycle Count, Health)"]
        Sysfs["Sysfs / Procfs Readers\n(cpufreq, cpuidle C-states,\nxe/i915 GPU, /proc/meminfo, zram)"]
        AccelIoctl["DRM / Accel Ioctl Bridge\n(/dev/dri/renderD128, /dev/accel0,\nDRM_IOCTL_IVPU_GET_PARAM)"]
        DispMgr["DisplayManager & TrafficStats\n(Connected Monitors, Hz, HDR,\nNetwork RX/TX Mbps)"]
    end

    subgraph Corewatch["Corewatch Desktop App (Kotlin + Jetpack Compose)"]
        Sampler["Low-Overhead Coroutine Sampler\n(1Hz FloatRingBuffer History,\nZero Allocations on UI Thread)"]
        FullUI["Full Workbench Window\n(Thermals & Compute, Battery/USB-C W,\nMemory/Swap, Displays/Net)"]
        MiniHUD["Translucent Mini-HUD PiP\n(340x398dp 5-Tile Sparkline Overlay)"]
        Caffeine["Caffeine Keep-Awake Controller\n(WakeLock + Quick Settings Tile +\nAC/External Display Auto-Triggers)"]
        Export["Diagnostic Snapshot Exporter\n(1-Click Anonymized Markdown Table)"]
    end

    PowerMgr --> Sampler
    BattMgr --> Sampler
    Sysfs --> Sampler
    AccelIoctl --> Sampler
    DispMgr --> Sampler
    Sampler --> FullUI
    Sampler --> MiniHUD
    Caffeine --> FullUI
    Caffeine --> MiniHUD
    Sampler --> Export
```

* **Language & Tooling:** Kotlin 2.3, Jetpack Compose BOM `2026.03.01`, Material 3, Gradle 9.1, Android SDK 36 (Android 15+ Desktop Windowing).
* **Pure Data & Telemetry Architecture (`com.droid.corewatch.data`):**
  * `SysfsParsers`: Defensive, bounds-checked parsers for `/proc/meminfo`, `/sys/devices/system/cpu/cpu*/cpufreq/`, `/sys/devices/system/cpu/cpu*/cpuidle/state*/time`, `/sys/class/drm/card0/device/tile0/gt0/` (`xe` / `i915` GPU), `/sys/class/accel/accel0/`, `/sys/class/thermal/`, and `/sys/class/hwmon/`.
  * `AccelIoctlBridge`: Lightweight 16KB page-aligned JNI bridge (`libcorewatch_accel.so` for `x86_64` and `arm64-v8a`) querying `DRM_IOCTL_VERSION` on `/dev/dri/renderD128` and `/dev/accel0`, KMS connector/CRTC VRR properties (`vrr_capable`, `VRR_ENABLED`, `max bpc`) on `/dev/dri/card0`, and zero-wakeup `DRM_IOCTL_IVPU_GET_PARAM` (`DRM_IVPU_PARAM_CONTEXT_ID`, `CORE_CLOCK_RATE`, `FW_API_VERSION`, `DEVICE_ID`) on Intel Lunar Lake / Meteor Lake NPUs.
  * `BatteryPowerCalculator`: Signed wattage (`W = V × I`), unit normalization (`µA`/`mA`, `mV`/`V`), charger profiling, and remaining charge/discharge time estimation.
  * `DisplayAuditor`: Variable Refresh Rate (VRR / eDP Adaptive-Sync / Android ARR) range and live cadence auditing, EDID panel model & color depth (`bpc`), DPI scale, HDR format mapping, and `< 50Hz` dock bottleneck detection.
  * `WirelessTelemetryParsers`: Wi-Fi 7 (`802.11be`) / 6E / 6 / 5 radio & per-band (`2.4 / 5 / 6 GHz`) regulatory channel summarizer (DFS/TDWR & 6 GHz PSC / `CA` / `00` domain diagnostics), `802.11d/e/k/r/v` beacon Information Element (`EID 7, 11, 54, 70, 127`) decoder, IEEE 802.3 Ethernet link-speed classifier, and Bluetooth A2DP/HFP/LE Audio codec & drop-counter parsers.
  * `UsbPeripheralParsers` & `UsbHotplugTracker`: Multi-tier `/sys/bus/usb/devices` topology builder with root-port `removable` inheritance, companion xHCI/hub pairing, SuperSpeed downgrade / Billboard (`0x11`) / bus-powered hub over-current diagnostics, bracket-depth-aware `dumpsys usb` Type-C port parser, and bounded 64-event in-memory hotplug tracker.
  * `CaffeinePolicy`: Pure state evaluator for timed keep-awake modes, AC/external-display auto-triggers, and the `< 15%` low-battery safety cutoff.
  * `DiagnosticReportBuilder`: Anonymized, Markdown-injection-safe hardware and thermal snapshot formatter.

```bash
# Clone and build directly in the Googlebook Linux Terminal
git clone https://github.com/alainmic/corewatch.git
cd corewatch
./gradlew assembleDebug                                          # Builds app/build/outputs/apk/debug/Corewatch-debug.apk
./gradlew testDebugUnitTest createDebugUnitTestCoverageReport    # Runs all unit, UI, and fuzz tests + JaCoCo report
```

---

## Made on a Googlebook

Corewatch was created, built, and tested directly on a Googlebook:

* **Developed in the Linux Terminal:** Kotlin sources, Gradle builds, unit/UI/fuzz test suites, and APK packaging were executed inside the Googlebook's built-in Linux environment.
* **Tested headlessly and live on-device:** Verified both on-device in freeform desktop windowing and headlessly via Robolectric Compose UI tests across full workbench (`1120×760dp`) and compact Mini-HUD (`340×398dp`) window geometries.
* **Universal multi-architecture APK:** Ships with 16KB page-aligned `x86_64` and `arm64-v8a` JNI ioctl bridges (`< 6 KB` each) alongside pure Kotlin fallbacks so a single lightweight APK runs natively on both Intel Core Ultra and Qualcomm Snapdragon Googlebooks.

---

<h2 id="about-this-project">About this project</h2>

**Corewatch** is an independent, open-source passion project developed on and for Googlebooks to give Googlebook users compact, desktop-grade visibility into thermals, per-core CPU frequencies, battery wattage, memory swap pressure, and external display negotiation.

It is **not affiliated with, endorsed by, or sponsored by Google or any employer**. See [CONTRIBUTING.md](CONTRIBUTING.md) if you'd like to contribute.

---

<h2 id="licenses">Licenses</h2>

* **Corewatch source code:** Licensed under the [Apache License, Version 2.0](LICENSE).
* **Third-Party Libraries:** Built using open-source AndroidX, Jetpack Compose, Kotlin, Guava `ListenableFuture`, and JSpecify libraries. Complete license texts and attributions are available in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

<p align="center"><sub>Crafted with passion on a Googlebook, for the Googlebook community.</sub></p>
