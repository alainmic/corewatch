// Copyright 2026 The Corewatch Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.corewatch.ui

import android.app.Application
import android.graphics.Rect
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.droid.corewatch.MainActivity
import com.droid.corewatch.data.BatteryPowerCalculator
import com.droid.corewatch.data.BluetoothAudioDropStats
import com.droid.corewatch.data.BluetoothTelemetrySnapshot
import com.droid.corewatch.data.CaffeineConfig
import com.droid.corewatch.data.CaffeineDuration
import com.droid.corewatch.data.CoreCluster
import com.droid.corewatch.data.CpuCoreReading
import com.droid.corewatch.data.DisplayAuditor
import com.droid.corewatch.data.DisplayModeSpec
import com.droid.corewatch.data.EthernetTelemetrySnapshot
import com.droid.corewatch.data.NetworkThroughput
import com.droid.corewatch.data.SystemTelemetrySnapshot
import com.droid.corewatch.data.ThermalLevel
import com.droid.corewatch.data.ThermalSensorReading
import com.droid.corewatch.data.WifiTelemetrySnapshot
import com.droid.corewatch.service.CaffeineController
import com.droid.corewatch.service.CaffeineTileService
import com.droid.corewatch.ui.theme.CorewatchTheme
import com.droid.corewatch.ui.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1280dp-h800dp-land")
class CorewatchUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun workbenchTabsAndMiniHudAndCaffeineAndDiagnosticCopyWorkEndToEnd() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = CorewatchViewModel(app)

        composeRule.setContent {
            CorewatchTheme(dark = true) {
                CorewatchAppRoot(vm = vm)
            }
        }

        // 1. Default tab is Overview & Thermals
        composeRule.onNodeWithText("Thermal Status & Headroom").assertIsDisplayed()
        composeRule.onNodeWithText("Per-Core CPU Frequencies (Performance vs. Efficiency Clusters)").assertIsDisplayed()

        // 2. Switch to Tab 2 (Battery & Power) and test Caffeine Keep-Awake controls
        composeRule.onNodeWithTag("tab_1").performClick()
        composeRule.onNodeWithText("Live Battery Power Flow (W = V × I)").assertIsDisplayed()
        composeRule.onNodeWithTag("caffeine_dur_Min30").performClick()
        assertEquals(CaffeineDuration.Min30, vm.state.value.caffeineConfig.duration)
        assertTrue(vm.state.value.snapshot.caffeineEvaluation.isActive)

        // Toggle Caffeine auto-rules checkboxes
        composeRule.onNodeWithTag("caffeine_auto_ac").performClick()
        assertTrue(vm.state.value.caffeineConfig.autoOnAcCharging)
        composeRule.onNodeWithTag("caffeine_auto_ext").performClick()
        assertTrue(vm.state.value.caffeineConfig.autoOnExternalDisplay)

        // 3. Switch to Tab 3 (Memory & Storage)
        composeRule.onNodeWithTag("tab_2").performClick()
        composeRule.onNodeWithText("Physical RAM (/proc/meminfo)").assertIsDisplayed()
        composeRule.onNodeWithText("zram / Swap Pressure").assertIsDisplayed()
        composeRule.onNodeWithText("Internal User Storage (/data)").assertIsDisplayed()
        composeRule.onNodeWithTag("lmkd_monitor_card").assertIsDisplayed()

        // 4. Switch to Tab 4 (Display / Displays), Tab 5 (Network), and Tab 6 (Bluetooth)
        assertEquals("Display", CorewatchTab.Displays.labelForDisplayCount(1, compact = false))
        assertEquals("Displays", CorewatchTab.Displays.labelForDisplayCount(2, compact = false))
        composeRule.onNodeWithTag("tab_3").performClick()
        assertEquals(CorewatchTab.Displays, vm.state.value.activeTab)
        composeRule.onNodeWithTag("frame_jank_card").assertIsDisplayed()

        composeRule.onNodeWithTag("tab_4").performClick()
        assertEquals(CorewatchTab.Network, vm.state.value.activeTab)
        composeRule.onNodeWithTag("network_tab").assertIsDisplayed()
        composeRule.onNodeWithTag("wifi_link_card").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("ethernet_link_card").assertDoesNotExist()
        composeRule.onNodeWithTag("bluetooth_card").assertDoesNotExist()
        composeRule.onNodeWithTag("network_throughput_card").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Network Throughput (TrafficStats RX / TX)").assertIsDisplayed()
        composeRule.onNodeWithTag("wifi_regulatory_card").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("surrounding_wifi_aps_card").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithTag("tab_5").performClick()
        assertEquals(CorewatchTab.Bluetooth, vm.state.value.activeTab)
        composeRule.onNodeWithTag("bluetooth_tab").assertIsDisplayed()
        composeRule.onNodeWithTag("wifi_link_card").assertDoesNotExist()
        composeRule.onNodeWithTag("bluetooth_card").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("bluetooth_drops_card").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("bluetooth_peripherals_card").performScrollTo().assertIsDisplayed()

        // 5. Copy Markdown Diagnostic Snapshot AND verify switching tabs works while the notification bar is visible
        composeRule.onNodeWithTag("btn_copy_markdown").performClick()
        composeRule.onNodeWithTag("status_banner").assertIsDisplayed()
        assertTrue(vm.state.value.statusBanner!!.contains("Copied Markdown"))

        composeRule.onNodeWithTag("tab_0").performClick()
        assertEquals(CorewatchTab.OverviewThermals, vm.state.value.activeTab)
        composeRule.onNodeWithText("Thermal Status & Headroom").assertIsDisplayed()
        composeRule.onNodeWithTag("status_banner").assertIsDisplayed()

        composeRule.onNodeWithTag("tab_1").performClick()
        assertEquals(CorewatchTab.BatteryPower, vm.state.value.activeTab)
        composeRule.onNodeWithText("Live Battery Power Flow (W = V × I)").assertIsDisplayed()
        composeRule.onNodeWithTag("status_banner").assertIsDisplayed()

        composeRule.onNodeWithTag("tab_2").performClick()
        assertEquals(CorewatchTab.MemoryStorage, vm.state.value.activeTab)
        composeRule.onNodeWithText("Physical RAM (/proc/meminfo)").assertIsDisplayed()
        composeRule.onNodeWithTag("status_banner").assertIsDisplayed()

        composeRule.onNodeWithTag("tab_3").performClick()
        assertEquals(CorewatchTab.Displays, vm.state.value.activeTab)
        composeRule.onNodeWithTag("frame_jank_card").assertIsDisplayed()
        composeRule.onNodeWithTag("status_banner").assertIsDisplayed()

        composeRule.onNodeWithTag("tab_4").performClick()
        assertEquals(CorewatchTab.Network, vm.state.value.activeTab)
        composeRule.onNodeWithText("Network Throughput (TrafficStats RX / TX)").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("status_banner").assertIsDisplayed()

        composeRule.onNodeWithTag("tab_5").performClick()
        assertEquals(CorewatchTab.Bluetooth, vm.state.value.activeTab)
        composeRule.onNodeWithText("Bluetooth Radio & Audio Codec").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("status_banner").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("Dismiss notification").performClick()

        // 6. Toggle Compact Mini-HUD mode, test Mini-HUD buttons (including opacity cycle), and expand back to Full Workbench
        composeRule.onNodeWithTag("btn_mini_hud").performClick()
        assertTrue(vm.state.value.isMiniHud)
        composeRule.onNodeWithTag("mini_hud_root").assertIsDisplayed()
        composeRule.onNodeWithText("Corewatch Mini-HUD").assertIsDisplayed()
        composeRule.onNodeWithTag("mini_hud_opacity").performClick()
        assertEquals(0.75f, vm.state.value.miniHudAlpha, 0.01f)
        composeRule.onNodeWithTag("mini_hud_opacity").performClick()
        assertEquals(1.0f, vm.state.value.miniHudAlpha, 0.01f)
        composeRule.onNodeWithTag("mini_hud_opacity").performClick()
        assertEquals(0.88f, vm.state.value.miniHudAlpha, 0.01f)
        composeRule.onNodeWithTag("mini_hud_caffeine").performClick()
        composeRule.onNodeWithTag("mini_hud_copy").performClick()
        vm.setSnapshotForTesting(SystemTelemetrySnapshot())
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("btn_expand_workbench").performClick()
        assertFalse(vm.state.value.isMiniHud)

        // 7. Open and close F1 Keyboard Shortcuts dialog
        composeRule.onNodeWithTag("btn_shortcuts").performClick()
        composeRule.onNodeWithText("Corewatch Keyboard Shortcuts").assertIsDisplayed()
        composeRule.onNodeWithText("Close").performClick()
        assertFalse(vm.state.value.showShortcutsDialog)
    }

    @Test
    fun captionMetricsRemainBalancedOnWindowResizeAndNeverOverlapMinimizeButton() {
        val density = Density(density = 1.625f, fontScale = 1.0f)

        // 1. Minimal window (627px = 385dp wide): left icon rect [0..125], right buttons rect [390..627]
        val minMetrics = computeCaptionMetrics(
            density = density,
            layoutDir = LayoutDirection.Ltr,
            capTopPx = 72,
            statusTopPx = 0,
            widthPx = 627,
            boundingRects = listOf(Rect(0, 0, 125, 72), Rect(390, 0, 627, 72)),
        )
        assertTrue("Left inset must stay compact (<= 104dp)", minMetrics.start <= 104.dp)
        assertTrue("Right inset must protect window buttons (>= 153dp)", minMetrics.end >= 153.dp)

        // 2. Regression test: window resized from 627px up to 2189px while boundingRects still holds
        // the transitional [390..627] rect from the previous layout pass.
        // Previously, 508 < 2189/2 misclassified [390..627] as a left rect (start = 385dp, end = 0dp)!
        val transitionalResizeMetrics = computeCaptionMetrics(
            density = density,
            layoutDir = LayoutDirection.Ltr,
            capTopPx = 72,
            statusTopPx = 0,
            widthPx = 2189,
            boundingRects = listOf(Rect(0, 0, 125, 72), Rect(390, 0, 627, 72)),
        )
        assertTrue(
            "Stale right-control rect must never inflate left title bar space (was ${transitionalResizeMetrics.start})",
            transitionalResizeMetrics.start <= 104.dp,
        )
        assertTrue(
            "Right inset must never drop to 0dp and overlap the minimize button (was ${transitionalResizeMetrics.end})",
            transitionalResizeMetrics.end >= 153.dp,
        )

        // 3. Steady-state wide window (2189px) in LTR and RTL
        val wideLtr = computeCaptionMetrics(
            density = density,
            layoutDir = LayoutDirection.Ltr,
            capTopPx = 72,
            statusTopPx = 0,
            widthPx = 2189,
            boundingRects = listOf(Rect(0, 0, 125, 72), Rect(1930, 0, 2189, 72)),
        )
        assertTrue(wideLtr.start in 70.dp..104.dp)
        assertTrue(wideLtr.end >= 153.dp)

        val wideRtl = computeCaptionMetrics(
            density = density,
            layoutDir = LayoutDirection.Rtl,
            capTopPx = 72,
            statusTopPx = 0,
            widthPx = 2189,
            boundingRects = listOf(Rect(0, 0, 125, 72), Rect(1930, 0, 2189, 72)),
        )
        assertEquals(wideLtr.end, wideRtl.start)
        assertEquals(wideLtr.start, wideRtl.end)

        // 4. Empty boundingRects fallback when captionBar > 0
        val fallbackMetrics = computeCaptionMetrics(
            density = density,
            layoutDir = LayoutDirection.Ltr,
            capTopPx = 72,
            statusTopPx = 0,
            widthPx = 2189,
            boundingRects = emptyList(),
        )
        assertTrue(fallbackMetrics.start in 75.dp..77.dp)
        assertTrue(fallbackMetrics.end >= 153.dp)
    }

    @Test
    fun titleBarTabsRemainUnclippedAndClickableAcrossMediumAndSplitCaptionWidths() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = CorewatchViewModel(app)
        var simulatedWidth = androidx.compose.runtime.mutableStateOf(758.dp)
        val desktopCap = CaptionMetrics(height = 44.dp, start = 80.dp, end = 197.dp)

        composeRule.setContent {
            val state = vm.state.collectAsState().value
            CorewatchTheme(dark = true) {
                androidx.compose.foundation.layout.Box(
                    modifier = androidx.compose.ui.Modifier.size(simulatedWidth.value, 600.dp),
                ) {
                    CorewatchTitleBar(state = state, vm = vm, cap = desktopCap)
                }
            }
        }

        // At 758dp window width with desktop caption insets (usableCaptionWidth = 473dp),
        // all 7 ultra-compact tabs ("Compute", "Battery", "Memory", "Display(s)", "Net", "BT", "USB") must be visible and clickable
        composeRule.onNodeWithTag("btn_copy_markdown").performClick()
        assertTrue(vm.state.value.statusBanner!!.contains("Copied Markdown"))
        composeRule.onNodeWithTag("tab_1").assertIsDisplayed().performClick()
        assertEquals(CorewatchTab.BatteryPower, vm.state.value.activeTab)
        composeRule.onNodeWithTag("tab_2").assertIsDisplayed().performClick()
        assertEquals(CorewatchTab.MemoryStorage, vm.state.value.activeTab)
        composeRule.onNodeWithTag("tab_3").assertIsDisplayed().performClick()
        assertEquals(CorewatchTab.Displays, vm.state.value.activeTab)
        composeRule.onNodeWithTag("tab_4").assertIsDisplayed().performClick()
        assertEquals(CorewatchTab.Network, vm.state.value.activeTab)
        composeRule.onNodeWithTag("tab_5").assertIsDisplayed().performClick()
        assertEquals(CorewatchTab.Bluetooth, vm.state.value.activeTab)
        composeRule.onNodeWithTag("tab_6").assertIsDisplayed().performClick()
        assertEquals(CorewatchTab.UsbPeripherals, vm.state.value.activeTab)
        composeRule.onNodeWithTag("tab_0").assertIsDisplayed().performClick()
        assertEquals(CorewatchTab.OverviewThermals, vm.state.value.activeTab)

        // At 560dp window width with desktop caption insets (usableCaptionWidth = 275dp < 465dp),
        // splitTwoRowHeader activates so all 7 tabs and pills remain visible and clickable on row 2
        composeRule.runOnUiThread {
            simulatedWidth.value = 560.dp
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("tab_1").assertIsDisplayed().performClick()
        assertEquals(CorewatchTab.BatteryPower, vm.state.value.activeTab)
        composeRule.onNodeWithTag("tab_3").assertIsDisplayed().performClick()
        assertEquals(CorewatchTab.Displays, vm.state.value.activeTab)
        composeRule.onNodeWithTag("tab_4").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(CorewatchTab.Network, vm.state.value.activeTab)
        composeRule.onNodeWithTag("tab_5").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(CorewatchTab.Bluetooth, vm.state.value.activeTab)
        composeRule.onNodeWithTag("tab_6").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(CorewatchTab.UsbPeripherals, vm.state.value.activeTab)
        composeRule.onNodeWithTag("pill_thermal").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("pill_wattage").assertIsDisplayed()
    }

    @Test
    fun temperatureUnitsThemeCyclePauseResumeResetAndDisplayBottleneckWarningRenderCorrectly() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = CorewatchViewModel(app)

        val customDisplays = listOf(
            DisplayAuditor.auditDisplay(
                displayId = 0,
                rawName = "Built-in Screen",
                activeModeId = 1,
                activeWidth = 2880,
                activeHeight = 1800,
                activeRefreshHz = 120f,
                logicalDpi = 260,
                physicalXDpi = 244f,
                physicalYDpi = 244f,
                hdrTypeIds = intArrayOf(2),
                modes = listOf(DisplayModeSpec(1, 2880, 1800, 120f, true)),
            ),
            DisplayAuditor.auditDisplay(
                displayId = 2,
                rawName = "Dell U2723QE",
                activeModeId = 2,
                activeWidth = 3840,
                activeHeight = 2160,
                activeRefreshHz = 30f,
                logicalDpi = 160,
                physicalXDpi = 163f,
                physicalYDpi = 163f,
                hdrTypeIds = intArrayOf(),
                modes = listOf(
                    DisplayModeSpec(1, 3840, 2160, 60f, false),
                    DisplayModeSpec(2, 3840, 2160, 30f, true),
                ),
            ),
        )

        composeRule.setContent {
            val dark = vm.state.value.themeMode != ThemeMode.Light
            CorewatchTheme(dark = dark) {
                CorewatchAppRoot(vm = vm)
            }
        }

        // Toggle temperature unit (°C -> °F -> °C) via thermal pill and button
        composeRule.onNodeWithTag("pill_thermal").performClick()
        assertTrue(vm.state.value.useFahrenheit)
        composeRule.onNodeWithText("Switch to °C").performScrollTo().performClick()
        assertFalse(vm.state.value.useFahrenheit)

        // Pause / Resume telemetry + Reset session history + Cycle theme modes
        composeRule.onNodeWithTag("btn_pause_resume").performClick()
        assertTrue(vm.state.value.isPaused)
        composeRule.onNodeWithTag("btn_pause_resume").performClick()
        assertFalse(vm.state.value.isPaused)

        composeRule.onNodeWithTag("btn_reset_history").performClick()
        assertTrue(vm.state.value.statusBanner!!.contains("reset"))

        // Cycle telemetry sampling rate (1s -> 2s -> 250ms -> 500ms -> 1s)
        composeRule.onNodeWithTag("btn_sampling_rate").performClick()
        assertEquals(SamplingInterval.SLOW_2S, vm.state.value.samplingInterval)
        composeRule.onNodeWithTag("btn_sampling_rate").performClick()
        assertEquals(SamplingInterval.FAST_250MS, vm.state.value.samplingInterval)
        composeRule.onNodeWithTag("btn_sampling_rate").performClick()
        assertEquals(SamplingInterval.FAST_500MS, vm.state.value.samplingInterval)
        composeRule.onNodeWithTag("btn_sampling_rate").performClick()
        assertEquals(SamplingInterval.NORMAL_1S, vm.state.value.samplingInterval)

        composeRule.onNodeWithTag("btn_theme").performClick()
        assertEquals(ThemeMode.Light, vm.state.value.themeMode)
        composeRule.onNodeWithTag("btn_theme").performClick()
        assertEquals(ThemeMode.System, vm.state.value.themeMode)
        composeRule.onNodeWithTag("btn_theme").performClick()
        assertEquals(ThemeMode.Dark, vm.state.value.themeMode)

        // Exercise all ThermalLevel visual branches and discharging battery state
        for (level in ThermalLevel.entries) {
            vm.setSnapshotForTesting(
                SystemTelemetrySnapshot(
                    thermalLevel = level,
                    cpuCores = listOf(
                        CpuCoreReading(0, 3_600_000L, 400_000L, 4_800_000L, "schedutil", CoreCluster.Performance),
                        CpuCoreReading(4, 1_800_000L, 400_000L, 3_500_000L, "schedutil", CoreCluster.Efficiency),
                    ),
                    thermalSensors = listOf(ThermalSensorReading("tz0", "CPU Package", 72.5f, "thermal_zone")),
                    battery = BatteryPowerCalculator.computeSnapshot(
                        levelPercent = 45,
                        isCharging = false,
                        pluggedType = 0,
                        rawVoltage = 11_400,
                        rawCurrentNow = -1_250_000L,
                        rawTemperatureTenthsC = 335,
                        cycleCount = 88,
                        healthCode = 2,
                    ),
                    displays = customDisplays,
                ),
            )
            composeRule.waitForIdle()
        }

        // Verify Displays & Network tab renders the 4K@30Hz dock bottleneck warning
        composeRule.onNodeWithTag("tab_3").performClick()
        composeRule.onNodeWithTag("display_card_2").assertIsDisplayed()
        composeRule.onNodeWithText("Dell U2723QE (#2)").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun keyboardShortcutsNavigateTabsToggleModesAndCopySnapshot() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = CorewatchViewModel(app)

        composeRule.setContent {
            CorewatchTheme(dark = true) {
                CorewatchAppRoot(vm = vm)
            }
        }

        val root = composeRule.onRoot()
        root.performKeyInput {
            withKeyDown(Key.CtrlLeft) { pressKey(Key.Two) }
        }
        assertEquals(CorewatchTab.BatteryPower, vm.state.value.activeTab)

        root.performKeyInput {
            withKeyDown(Key.CtrlLeft) { pressKey(Key.Three) }
        }
        assertEquals(CorewatchTab.MemoryStorage, vm.state.value.activeTab)

        root.performKeyInput {
            withKeyDown(Key.CtrlLeft) { pressKey(Key.Four) }
        }
        assertEquals(CorewatchTab.Displays, vm.state.value.activeTab)

        root.performKeyInput {
            withKeyDown(Key.CtrlLeft) { pressKey(Key.Five) }
        }
        assertEquals(CorewatchTab.Network, vm.state.value.activeTab)

        root.performKeyInput {
            withKeyDown(Key.CtrlLeft) { pressKey(Key.Six) }
        }
        assertEquals(CorewatchTab.Bluetooth, vm.state.value.activeTab)

        root.performKeyInput {
            withKeyDown(Key.CtrlLeft) { pressKey(Key.Seven) }
        }
        assertEquals(CorewatchTab.UsbPeripherals, vm.state.value.activeTab)

        root.performKeyInput {
            withKeyDown(Key.CtrlLeft) {
                withKeyDown(Key.ShiftLeft) { pressKey(Key.I) }
            }
        }
        assertTrue(vm.state.value.showInternalUsbDevices)

        root.performKeyInput {
            withKeyDown(Key.CtrlLeft) { pressKey(Key.One) }
        }
        assertEquals(CorewatchTab.OverviewThermals, vm.state.value.activeTab)

        root.performKeyInput {
            withKeyDown(Key.CtrlLeft) { pressKey(Key.K) }
        }
        assertEquals(CaffeineDuration.Indefinite, vm.state.value.caffeineConfig.duration)

        root.performKeyInput {
            withKeyDown(Key.CtrlLeft) { pressKey(Key.M) }
        }
        assertTrue(vm.state.value.isMiniHud)

        root.performKeyInput {
            withKeyDown(Key.CtrlLeft) { pressKey(Key.M) }
        }
        assertFalse(vm.state.value.isMiniHud)

        root.performKeyInput { pressKey(Key.Spacebar) }
        assertTrue(vm.state.value.isPaused)

        root.performKeyInput { pressKey(Key.F5) }
        assertTrue(vm.state.value.statusBanner!!.contains("reset"))

        root.performKeyInput {
            withKeyDown(Key.CtrlLeft) { pressKey(Key.R) }
        }
        assertTrue(vm.state.value.statusBanner!!.contains("reset"))

        root.performKeyInput {
            withKeyDown(Key.CtrlLeft) {
                withKeyDown(Key.ShiftLeft) { pressKey(Key.C) }
            }
        }
        assertTrue(vm.state.value.statusBanner!!.contains("Copied Markdown"))

        root.performKeyInput { pressKey(Key.F1) }
        assertTrue(vm.state.value.showShortcutsDialog)
    }

    @Test
    fun caffeineControllerTileServiceAndMainActivityLifecycleWorkEndToEnd() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        CaffeineController.init(app)
        CaffeineController.setDuration(app, CaffeineDuration.Indefinite, 1_000L)
        CaffeineController.syncWakeLock(app, shouldKeepAwake = true)
        CaffeineController.syncWakeLock(app, shouldKeepAwake = false)
        CaffeineController.setDuration(app, CaffeineDuration.Off, 2_000L)

        // Test CaffeineTileService lifecycle
        val tileController = Robolectric.buildService(CaffeineTileService::class.java).create()
        val service = tileController.get()
        service.onStartListening()
        service.onClick()
        service.onClick()

        // Test MainActivity launch and PiP Overlay lifecycle
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(activity.hasWindowFocus() || !activity.isFinishing)
                activity.handleMiniHudModeTransition(true, canDrawOverlays = true)
                activity.dragPipOverlayBy(12f, -8f)
                activity.updatePipOverlayAlpha(0.75f)
                activity.expandFromPipOverlay()
                activity.showPipOverlay()
                activity.dismissPipOverlay()
            }
        }
    }

    @Test
    fun narrowWindowAutoSwitchesToMiniHudAndExpandButtonExitsImmediately() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = CorewatchViewModel(app)
        var simulatedWidth = androidx.compose.runtime.mutableStateOf(380.dp)

        composeRule.setContent {
            val state = vm.state.collectAsState().value
            CorewatchTheme(dark = true) {
                androidx.compose.foundation.layout.Box(
                    modifier = androidx.compose.ui.Modifier.size(simulatedWidth.value, 480.dp),
                ) {
                    if (state.isMiniHud) {
                        PipOverlayRoot(
                            vm = vm,
                            onExpandToFull = { vm.setMiniHud(false) },
                            onDragOverlay = { _, _ -> },
                        )
                    } else {
                        CorewatchAppRoot(vm = vm)
                    }
                }
            }
        }

        // Narrow window (< 480.dp) automatically enters Mini-HUD
        composeRule.waitForIdle()
        assertTrue(vm.state.value.isMiniHud)
        composeRule.onNodeWithTag("mini_hud_root").assertIsDisplayed()

        // Clicking Full (btn_expand_workbench) immediately exits Mini-HUD on the first click even while narrow
        composeRule.onNodeWithTag("btn_expand_workbench").performClick()
        assertFalse(vm.state.value.isMiniHud)
    }

    @Test
    fun sparklinePointsAndCanvasDrawHandleEmptySingleAndMultiSampleSeries() {
        assertTrue(computeSparklinePoints(emptyList(), 200f, 60f, 2f).isEmpty())
        assertTrue(computeSparklinePoints(listOf(10f, 20f), 1f, 60f, 2f).isEmpty())

        val single = computeSparklinePoints(listOf(50f), 200f, 60f, 2f, minFloor = 0f, maxCeiling = 100f)
        assertEquals(2, single.size)
        assertEquals(0f, single[0].x, 0.01f)
        assertEquals(200f, single[1].x, 0.01f)
        assertEquals(single[0].y, single[1].y, 0.01f)

        val multi = computeSparklinePoints(listOf(0f, 50f, 100f), 200f, 60f, 2f, minFloor = 0f, maxCeiling = 100f)
        assertEquals(3, multi.size)
        assertTrue(multi[0].y > multi[1].y)
        assertTrue(multi[1].y > multi[2].y)

        var hostView: android.view.View? = null
        composeRule.setContent {
            hostView = androidx.compose.ui.platform.LocalView.current
            CorewatchTheme(dark = true) {
                androidx.compose.foundation.layout.Column {
                    SparklineCanvas(
                        samples = emptyList(),
                        lineColor = androidx.compose.ui.graphics.Color.Cyan,
                        gridColor = androidx.compose.ui.graphics.Color.Gray,
                        modifier = androidx.compose.ui.Modifier.size(160.dp, 48.dp),
                    )
                    SparklineCanvas(
                        samples = listOf(42f),
                        lineColor = androidx.compose.ui.graphics.Color.Cyan,
                        gridColor = androidx.compose.ui.graphics.Color.Gray,
                        modifier = androidx.compose.ui.Modifier.size(160.dp, 48.dp),
                    )
                    SparklineCanvas(
                        samples = listOf(15f, 45f, 80f, 35f),
                        lineColor = androidx.compose.ui.graphics.Color.Cyan,
                        gridColor = androidx.compose.ui.graphics.Color.Gray,
                        modifier = androidx.compose.ui.Modifier.size(160.dp, 48.dp),
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.runOnUiThread {
            val bmp = android.graphics.Bitmap.createBitmap(400, 300, android.graphics.Bitmap.Config.ARGB_8888)
            hostView?.draw(android.graphics.Canvas(bmp))
        }
    }

    @Test
    fun telemetryCollectorReadsPopulatedSysfsAndFallsBackWhenMissing() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val tempRoot = java.nio.file.Files.createTempDirectory("corewatch_fake_sysfs").toFile()
        try {
            val cpuRoot = java.io.File(tempRoot, "cpu").apply { mkdirs() }
            val cpu0 = java.io.File(cpuRoot, "cpu0/cpufreq").apply { mkdirs() }
            java.io.File(cpu0, "scaling_cur_freq").writeText("2400000\n")
            java.io.File(cpu0, "scaling_max_freq").writeText("4800000\n")
            java.io.File(cpu0, "scaling_min_freq").writeText("400000\n")
            java.io.File(cpu0, "scaling_governor").writeText("schedutil\n")

            val cpu1 = java.io.File(cpuRoot, "cpu1/cpufreq").apply { mkdirs() }
            java.io.File(cpu1, "scaling_cur_freq").writeText("1200000\n")
            java.io.File(cpu1, "scaling_max_freq").writeText("3200000\n")
            java.io.File(cpu1, "scaling_min_freq").writeText("400000\n")
            java.io.File(cpu1, "scaling_governor").writeText("powersave\n")

            val cpu0Idle = java.io.File(cpuRoot, "cpu0/cpuidle/state0").apply { mkdirs() }
            val cpu0IdleTime = java.io.File(cpu0Idle, "time")
            cpu0IdleTime.writeText("1000000\n")
            val cpu1Idle = java.io.File(cpuRoot, "cpu1/cpuidle/state0").apply { mkdirs() }
            val cpu1IdleTime = java.io.File(cpu1Idle, "time")
            cpu1IdleTime.writeText("2000000\n")

            val thermalRoot = java.io.File(tempRoot, "thermal").apply { mkdirs() }
            val tz0 = java.io.File(thermalRoot, "thermal_zone0").apply { mkdirs() }
            java.io.File(tz0, "type").writeText("x86_pkg_temp\n")
            java.io.File(tz0, "temp").writeText("54200\n")

            val hwmonRoot = java.io.File(tempRoot, "hwmon").apply { mkdirs() }
            val hw0 = java.io.File(hwmonRoot, "hwmon0").apply { mkdirs() }
            java.io.File(hw0, "name").writeText("coretemp\n")
            java.io.File(hw0, "temp1_label").writeText("Package id 0\n")
            java.io.File(hw0, "temp1_input").writeText("56000\n")

            val memInfo = java.io.File(tempRoot, "meminfo")
            memInfo.writeText(
                """
                MemTotal:       16384000 kB
                MemFree:         4096000 kB
                MemAvailable:    9216000 kB
                Buffers:          256000 kB
                Cached:          3840000 kB
                Dirty:             12000 kB
                SwapTotal:       8192000 kB
                SwapFree:        6144000 kB
                """.trimIndent(),
            )

            val drmRoot = java.io.File(tempRoot, "drm").apply { mkdirs() }
            val xeFreq = java.io.File(drmRoot, "card0/device/tile0/gt0/freq0").apply { mkdirs() }
            java.io.File(xeFreq, "act_freq").writeText("1200\n")
            java.io.File(xeFreq, "cur_freq").writeText("1250\n")
            java.io.File(xeFreq, "min_freq").writeText("800\n")
            java.io.File(xeFreq, "max_freq").writeText("2500\n")
            java.io.File(xeFreq, "rp0_freq").writeText("2500\n")
            java.io.File(xeFreq, "rpe_freq").writeText("850\n")
            java.io.File(xeFreq, "rpn_freq").writeText("100\n")
            java.io.File(xeFreq, "power_profile").writeText("[base] power_saving\n")
            val xeThr = java.io.File(xeFreq, "throttle").apply { mkdirs() }
            java.io.File(xeThr, "status").writeText("0\n")
            val xeIdle = java.io.File(drmRoot, "card0/device/tile0/gt0/gtidle").apply { mkdirs() }
            val gpuIdleTime = java.io.File(xeIdle, "idle_residency_ms")
            gpuIdleTime.writeText("5000\n")

            val accelRoot = java.io.File(tempRoot, "accel").apply { mkdirs() }
            val npuDev = java.io.File(accelRoot, "accel0/device").apply { mkdirs() }
            val npuBusyTime = java.io.File(npuDev, "npu_busy_time_us")
            npuBusyTime.writeText("100000\n")
            java.io.File(npuDev, "npu_current_frequency_mhz").writeText("1400\n")
            java.io.File(npuDev, "npu_max_frequency_mhz").writeText("2000\n")
            java.io.File(npuDev, "uevent").writeText("DRIVER=intel_vpu\nPCI_ID=8086:7D1D\n")

            val usbRoot = java.io.File(tempRoot, "usb").apply { mkdirs() }
            val usb2 = java.io.File(usbRoot, "usb2").apply { mkdirs() }
            java.io.File(usb2, "idVendor").writeText("1d6b\n")
            java.io.File(usb2, "idProduct").writeText("0003\n")
            java.io.File(usb2, "speed").writeText("20000\n")
            java.io.File(usb2, "version").writeText(" 3.10\n")
            java.io.File(usb2, "bDeviceClass").writeText("09\n")
            java.io.File(usb2, "tx_lanes").writeText("2\n")
            java.io.File(usb2, "rx_lanes").writeText("2\n")
            val usbNic = java.io.File(usbRoot, "2-1").apply { mkdirs() }
            java.io.File(usbNic, "idVendor").writeText("0bda\n")
            java.io.File(usbNic, "idProduct").writeText("8156\n")
            java.io.File(usbNic, "speed").writeText("5000\n")
            java.io.File(usbNic, "version").writeText(" 3.20\n")
            java.io.File(usbNic, "removable").writeText("removable\n")
            java.io.File(usbNic, "bMaxPower").writeText("256mA\n")
            java.io.File(usbNic, "bmAttributes").writeText("a0\n")
            java.io.File(usbNic, "product").writeText("USB 2.5G LAN\n")
            val usbNicIf = java.io.File(usbRoot, "2-1:1.0").apply { mkdirs() }
            java.io.File(usbNicIf, "bInterfaceClass").writeText("ff\n")
            java.io.File(usbNicIf, "bNumEndpoints").writeText("03\n")

            com.droid.corewatch.data.AccelIoctlBridge.testDrmVersionProvider = { path ->
                if (path.contains("accel")) "intel_vpu|Driver for Intel NPU|1.0.0"
                else "xe|Intel Xe2 Graphics|1.1.0"
            }
            var mockHeartbeat = 10L
            com.droid.corewatch.data.AccelIoctlBridge.testIvpuParamsProvider = { _, _ ->
                longArrayOf(0xB03EL, 7L, 2_000_000_000L, 64L, 0x00030025L, mockHeartbeat, 1L)
            }

            // Populate ShadowConnectivityManager with physical 2.5 GbE (eth1), AVF VM bridge (avf_tap_2052), and Wi-Fi (wlan0)
            val cm = app.getSystemService(android.net.ConnectivityManager::class.java)
            val shadowCm = org.robolectric.Shadows.shadowOf(cm)
            val ethNet = org.robolectric.shadows.ShadowNetwork.newInstance(106)
            val ethCaps = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance().also { caps ->
                val sc = org.robolectric.Shadows.shadowOf(caps)
                sc.addTransportType(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)
                sc.addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                sc.addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                sc.addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            }
            val ethLp = android.net.LinkProperties().apply {
                interfaceName = "eth1"
                mtu = 9000
                setDnsServers(
                    listOf(
                        java.net.InetAddress.getByName("1.1.1.1"),
                        java.net.InetAddress.getByName("2606:4700:4700::1111"),
                    ),
                )
            }
            shadowCm.addNetwork(ethNet, null)
            shadowCm.setNetworkCapabilities(ethNet, ethCaps)
            shadowCm.setLinkProperties(ethNet, ethLp)

            val avfNet = org.robolectric.shadows.ShadowNetwork.newInstance(105)
            val avfCaps = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance().also { caps ->
                org.robolectric.Shadows.shadowOf(caps).addTransportType(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)
            }
            val avfLp = android.net.LinkProperties().apply { interfaceName = "avf_tap_2052" }
            shadowCm.addNetwork(avfNet, null)
            shadowCm.setNetworkCapabilities(avfNet, avfCaps)
            shadowCm.setLinkProperties(avfNet, avfLp)

            val wifiNet = org.robolectric.shadows.ShadowNetwork.newInstance(103)
            val wifiCaps = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance().also { caps ->
                val sc = org.robolectric.Shadows.shadowOf(caps)
                sc.addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                sc.addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                sc.addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }
            val wifiLp = android.net.LinkProperties().apply {
                interfaceName = "wlan0"
                mtu = 1500
            }
            shadowCm.addNetwork(wifiNet, null)
            shadowCm.setNetworkCapabilities(wifiNet, wifiCaps)
            shadowCm.setLinkProperties(wifiNet, wifiLp)

            val populatedCollector = com.droid.corewatch.data.TelemetryCollector(
                context = app,
                cpuSysfsRoot = cpuRoot,
                thermalSysfsRoot = thermalRoot,
                hwmonSysfsRoot = hwmonRoot,
                procMemInfoFile = memInfo,
                drmSysfsRoot = drmRoot,
                accelSysfsRoot = accelRoot,
                usbSysfsRoot = usbRoot,
            )
            val snap1 = populatedCollector.sample(CaffeineConfig(), nowElapsedMs = 1_000L)
            // Advance 1,000 ms (1,000,000 us): cpu0 sleeps 940,000 us (6% active), cpu1 sleeps 950,000 us (5% active)
            cpu0IdleTime.writeText("1940000\n")
            cpu1IdleTime.writeText("2950000\n")
            // GPU sleeps 750 ms out of 1,000 ms -> 25% active
            gpuIdleTime.writeText("5750\n")
            // NPU busy 400,000 us out of 1,000,000 us -> 40% active
            npuBusyTime.writeText("500000\n")
            mockHeartbeat = 25L
            val snap2 = populatedCollector.sample(CaffeineConfig(), nowElapsedMs = 2_000L)
            assertEquals(2, snap1.cpuCores.size)
            assertEquals(0.06f, snap2.cpuCores[0].utilizationFraction, 0.01f)
            assertEquals(0.05f, snap2.cpuCores[1].utilizationFraction, 0.01f)
            assertEquals("powersave", snap2.cpuCores[1].scalingStateLabel)
            assertEquals(0.25f, snap2.gpu.utilizationFraction!!, 0.01f)
            assertTrue(snap2.npu.isPresent)
            assertEquals(0.40f, snap2.npu.utilizationFraction, 0.01f)
            assertTrue(snap1.thermalSensors.any { it.celsius > 50f })
            assertTrue(snap2.memory.totalBytes > 0L)
            assertTrue(snap2.ethernet.isConnected)
            assertEquals("eth1", snap2.ethernet.interfaceName)
            assertEquals(9000, snap2.ethernet.mtu)
            assertTrue(snap2.wifi.isConnected)
            assertEquals("wlan0", snap2.wifi.interfaceName)

            // Now test with missing directories so all fallback branches execute
            com.droid.corewatch.data.AccelIoctlBridge.testDrmVersionProvider = null
            com.droid.corewatch.data.AccelIoctlBridge.testIvpuParamsProvider = null
            val missingDir = java.io.File(tempRoot, "nonexistent")
            val fallbackCollector = com.droid.corewatch.data.TelemetryCollector(
                context = app,
                cpuSysfsRoot = missingDir,
                thermalSysfsRoot = missingDir,
                hwmonSysfsRoot = missingDir,
                procMemInfoFile = java.io.File(missingDir, "meminfo"),
                accelSysfsRoot = missingDir,
            )
            val fallbackSnap = fallbackCollector.sample(CaffeineConfig(), nowElapsedMs = 3_000L)
            assertTrue(fallbackSnap.cpuCores.isNotEmpty())
            assertEquals(2, fallbackSnap.thermalSensors.size)
            assertTrue(fallbackSnap.memory.totalBytes > 0L)
            assertFalse(fallbackSnap.npu.isPresent)
            assertEquals("not-present", fallbackSnap.npu.scalingStateLabel)
        } finally {
            com.droid.corewatch.data.AccelIoctlBridge.testDrmVersionProvider = null
            com.droid.corewatch.data.AccelIoctlBridge.testIvpuParamsProvider = null
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun retailUngrantedPermissionsShowActionableFallbacksAndLivePromoteWhenGranted() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = CorewatchViewModel(app)
        var overlayRequestedCount = 0

        composeRule.setContent {
            CorewatchTheme(dark = true) {
                CorewatchAppRoot(
                    vm = vm,
                    canDrawOverlays = false,
                    onRequestOverlayPermission = { overlayRequestedCount++ },
                )
            }
        }

        // 1. Retail ungranted DUMP state on Tab 6 (Bluetooth) shows honest locked card + Copy ADB Command chip
        composeRule.onNodeWithTag("tab_5").performClick()
        assertEquals(CorewatchTab.Bluetooth, vm.state.value.activeTab)
        composeRule.onNodeWithText("Per-Profile Stack Drop Counters Locked (A2DP · HFP/SCO · LE Audio)")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag("copy_bt_dump_adb_btn").performScrollTo().performClick()
        assertTrue(vm.state.value.statusBanner!!.contains("ADB DUMP"))
        vm.dismissBanner()

        // 2. Once DUMP is granted (hasStackCounters = true), live-switches to the 3-column counter grid + peripheral list
        vm.setSnapshotForTesting(
            vm.state.value.snapshot.copy(
                bluetooth = BluetoothTelemetrySnapshot(
                    isAdapterEnabled = true,
                    hasConnectPermission = true,
                    connectedCount = 1,
                    bondedCount = 1,
                    connectionStateLabel = "Connected (1 active)",
                    peripherals = listOf(
                        com.droid.corewatch.data.BluetoothPeripheralReading(
                            name = "Pixel Buds Pro 2",
                            transportTypeLabel = "Dual BR/EDR + LE",
                            categoryLabel = "Audio / Headset",
                            isConnected = true,
                            profilesSummary = "A2DP · LE Audio",
                            batteryPercent = 85,
                        ),
                    ),
                    audioDrops = BluetoothAudioDropStats(
                        sourceLabel = "BT STACK DUMPSYS (LIVE)",
                        hasStackCounters = true,
                        a2dpExpectedFrames = 12_500L,
                        a2dpDroppedFrames = 3L,
                        a2dpTxDropped = 1L,
                        hfpDroppedPackets = 2L,
                        leAudioUnderflows = 1L,
                    ),
                ),
            ),
        )
        composeRule.waitForIdle()
        composeRule.onNodeWithText("A2DP MEDIA STREAM").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("HFP / SCO VOICE").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("LE AUDIO (CIS / BIS)").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Pixel Buds Pro 2").performScrollTo().assertIsDisplayed()

        // 3. Switch to Tab 5 (Network): undocked has no Wired Ethernet card; docking dynamically reveals Wired Ethernet beside Wi-Fi 7 (Standby)
        composeRule.onNodeWithTag("tab_4").performClick()
        assertEquals(CorewatchTab.Network, vm.state.value.activeTab)
        composeRule.onNodeWithTag("ethernet_link_card").assertDoesNotExist()
        vm.setSnapshotForTesting(
            vm.state.value.snapshot.copy(
                wifi = WifiTelemetrySnapshot(
                    isConnected = true,
                    transportLabel = "Wi-Fi",
                    interfaceName = "wlan0",
                    isPrimaryDefaultRoute = false,
                    routeRoleLabel = "Standby (Ethernet Primary)",
                    mtu = 1500,
                    isValidated = true,
                    ipStackLabel = "Dual-Stack (IPv4 + IPv6)",
                    wifiStandardCode = 8,
                    wifiStandardLabel = "Wi-Fi 7 (802.11be)",
                    frequencyMhz = 5745,
                    bandAndChannelLabel = "5 GHz · Ch 149 (5745 MHz)",
                    rssiDbm = -36,
                    signalQualityPercent = 100,
                    signalQualityLabel = "Excellent",
                    txLinkSpeedMbps = 1296,
                    rxLinkSpeedMbps = 1080,
                    securityLabel = "WPA3-SAE",
                    throughput = NetworkThroughput(rxMbps = 0.05f, txMbps = 0.02f),
                ),
                ethernet = EthernetTelemetrySnapshot(
                    isConnected = true,
                    interfaceName = "eth1",
                    isPrimaryDefaultRoute = true,
                    isValidated = true,
                    mtu = 1500,
                    negotiatedSpeedMbps = 100,
                    linkSpeedTierLabel = "100 Mbps (100BASE-TX)",
                    ipStackLabel = "Dual-Stack (IPv4 + IPv6)",
                    dnsSummaryLabel = "Private DNS (DoT) · 2 Servers",
                    wakeOnLanSupported = false,
                    isEmulatorVirtualNic = false,
                    throughput = NetworkThroughput(rxMbps = 82.4f, txMbps = 14.2f),
                ),
                activeDefaultRouteSummary = "Ethernet (eth1)",
            ),
        )
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Wi-Fi Radio & Link").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("ethernet_link_card").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Wired Ethernet Link").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("100 Mbps (100BASE-TX)").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("ethernet_bottleneck_warning").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Default Route: Ethernet (eth1)").performScrollTo().assertIsDisplayed()

        // Unplugging the Ethernet dock dynamically removes Wired Ethernet Link and expands Wi-Fi back to full width
        vm.setSnapshotForTesting(
            vm.state.value.snapshot.copy(
                ethernet = EthernetTelemetrySnapshot(isConnected = false),
                activeDefaultRouteSummary = "Wi-Fi (wlan0)",
            ),
        )
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("ethernet_link_card").assertDoesNotExist()
        composeRule.onNodeWithTag("wifi_link_card").performScrollTo().assertIsDisplayed()

        // 4. Retail Mini-HUD without SYSTEM_ALERT_WINDOW shows "Pin Floating PiP" action chip
        composeRule.onNodeWithTag("btn_mini_hud").performClick()
        composeRule.onNodeWithTag("btn_grant_overlay_pip").assertIsDisplayed().performClick()
        assertEquals(1, overlayRequestedCount)
    }

    @Test
    fun wifiRegulatoryCapabilitiesAndSurroundingApScannerRenderFilterSortMaskAndCollect() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = CorewatchViewModel(app)
        var scanPermRequests = 0
        var locSettingsOpens = 0

        composeRule.setContent {
            CorewatchTheme(dark = true) {
                CorewatchAppRoot(
                    vm = vm,
                    onRequestWifiScanPermission = { scanPermRequests++ },
                    onOpenLocationSettings = { locSettingsOpens++ },
                )
            }
        }

        composeRule.onNodeWithTag("tab_4").performClick()

        // 1. Verify opt-in Location permission card before granting
        vm.setSnapshotForTesting(
            vm.state.value.snapshot.copy(
                wifi = WifiTelemetrySnapshot(
                    isConnected = true,
                    capabilities = com.droid.corewatch.data.WifiRadioCapabilities(
                        isCapabilitiesQueried = true,
                        hasNearbyWifiPermission = true,
                        channelQuerySucceeded = true,
                        supports11beWifi7 = true,
                        supports11axWifi6 = true,
                        supports24Ghz = true,
                        supports5Ghz = true,
                        supports6Ghz = true,
                        maxTxLinkSpeedMbps = 5764,
                        maxRxLinkSpeedMbps = 5764,
                        supportsWpa3Sae = true,
                        supportsSaeH2e = true,
                        countryCode = "CA",
                        countryCodeSource = "Driver RegDomain (dumpsys)",
                        band24Ghz = com.droid.corewatch.data.BandChannelSummary(
                            bandLabel = "2.4 GHz",
                            isHardwareSupported = true,
                            allowedCount = 11,
                            usableCount = 11,
                            maxBandwidthMhz = 40,
                            channelRangeSummary = "Ch 1–11",
                            subBandNote = "FCC / ISED (Ch 1–11)",
                        ),
                        band5Ghz = com.droid.corewatch.data.BandChannelSummary(
                            bandLabel = "5 GHz",
                            isHardwareSupported = true,
                            allowedCount = 22,
                            usableCount = 22,
                            maxBandwidthMhz = 160,
                            channelRangeSummary = "Ch 36–64, 100–116, 132–144, 149–165",
                            dfsChannelCount = 13,
                            subBandNote = "13 DFS ch · TDWR 120–128 excl.",
                        ),
                        band6Ghz = com.droid.corewatch.data.BandChannelSummary(
                            bandLabel = "6 GHz",
                            isHardwareSupported = true,
                            allowedCount = 0,
                            usableCount = 0,
                            maxBandwidthMhz = 0,
                            channelRangeSummary = "Blocked in Region",
                            subBandNote = "0 allowed 6 GHz channels",
                        ),
                    ),
                    surroundingScan = com.droid.corewatch.data.WifiScanTelemetrySnapshot(
                        hasScanLocationPermission = false,
                        isLocationEnabled = true,
                        isWifiEnabled = true,
                    ),
                ),
            ),
        )
        composeRule.waitForIdle()

        // Verify Device Wi-Fi Radio & Regulatory Capabilities card + Canada 6 GHz warning banner
        composeRule.onNodeWithTag("wifi_regulatory_card").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("wifi_6ghz_reg_warning").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("wifi_band_6g_tile").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("0 Allowed (Reg-Blocked)").performScrollTo().assertIsDisplayed()

        // 1b. Verify real-world firmware-blocked Wi-Fi 7 case (supports6Ghz == false, is6GhzHardwareCapable == true)
        //     renders "Firmware Blocked (CA)" instead of "Hardware Unsupported", versus genuine 2.4/5 GHz hardware!
        val fwBlocked6g = com.droid.corewatch.data.WirelessTelemetryParsers.evaluateSixGhzBandSummary(
            allowedFreqsWidths = emptyList(),
            usableFreqs = emptyList(),
            osReports6GhzSupported = false,
            supports11be = true,
            countryCode = "CA",
            wifiKernelDriver = "iwlwifi",
            wifiResConfig6GhzSta = false,
            wifiResConfig6GhzSoftAp = true,
            driver6GhzChannelListEmpty = true,
        )
        vm.setSnapshotForTesting(
            vm.state.value.snapshot.copy(
                wifi = vm.state.value.snapshot.wifi.copy(
                    capabilities = vm.state.value.snapshot.wifi.capabilities.copy(
                        supports6Ghz = false,
                        wifiKernelDriver = "iwlwifi",
                        wifiResConfig6GhzSta = false,
                        wifiResConfig6GhzSoftAp = true,
                        driver6GhzChannelListEmpty = true,
                        is6GhzHardwareCapable = true,
                        band6Ghz = fwBlocked6g,
                    ),
                ),
            ),
        )
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("wifi_6ghz_reg_warning").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Firmware Blocked (CA)").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Channels: Firmware / RegDomain Blocked (CA)").performScrollTo().assertIsDisplayed()

        // 1c. Genuine 2.4/5 GHz-only hardware shows "Hardware Unsupported" and NO 6 GHz regulatory warning banner
        val genuineNo6g = com.droid.corewatch.data.WirelessTelemetryParsers.evaluateSixGhzBandSummary(
            allowedFreqsWidths = emptyList(),
            usableFreqs = emptyList(),
            osReports6GhzSupported = false,
            supports11be = false,
            countryCode = "CA",
            wifiResConfig6GhzSta = false,
            wifiResConfig6GhzSoftAp = false,
        )
        vm.setSnapshotForTesting(
            vm.state.value.snapshot.copy(
                wifi = vm.state.value.snapshot.wifi.copy(
                    capabilities = vm.state.value.snapshot.wifi.capabilities.copy(
                        supports11beWifi7 = false,
                        supports6Ghz = false,
                        wifiResConfig6GhzSta = false,
                        wifiResConfig6GhzSoftAp = false,
                        is6GhzHardwareCapable = false,
                        band6Ghz = genuineNo6g,
                    ),
                ),
            ),
        )
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("wifi_6ghz_reg_warning").assertDoesNotExist()
        composeRule.onNodeWithText("Hardware Unsupported").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("2.4 / 5 GHz radio only (no 6 GHz PHY)").performScrollTo().assertIsDisplayed()

        // Click opt-in Location permission button
        composeRule.onNodeWithTag("grant_wifi_scan_permission_btn").performScrollTo().performClick()
        assertEquals(1, scanPermRequests)

        // 2. Verify Location Services Off state
        vm.setSnapshotForTesting(
            vm.state.value.snapshot.copy(
                wifi = vm.state.value.snapshot.wifi.copy(
                    surroundingScan = com.droid.corewatch.data.WifiScanTelemetrySnapshot(
                        hasScanLocationPermission = true,
                        isLocationEnabled = false,
                        isWifiEnabled = true,
                    ),
                ),
            ),
        )
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("open_location_settings_btn").performScrollTo().performClick()
        assertEquals(1, locSettingsOpens)

        // 3. Populate verbose surrounding APs across 2.4 GHz, 5 GHz [DFS], and 6 GHz [PSC] 320 MHz Wi-Fi 7
        vm.setSnapshotForTesting(
            vm.state.value.snapshot.copy(
                wifi = vm.state.value.snapshot.wifi.copy(
                    surroundingScan = com.droid.corewatch.data.WifiScanTelemetrySnapshot(
                        hasScanLocationPermission = true,
                        isLocationEnabled = true,
                        isWifiEnabled = true,
                        totalApCount = 3,
                        count24Ghz = 1,
                        count5Ghz = 1,
                        count6Ghz = 1,
                        lastScanAgeSeconds = 0.8f,
                        accessPoints = listOf(
                            com.droid.corewatch.data.SurroundingWifiAp(
                                ssid = "Lab-WiFi7-6GHz",
                                bssid = "02:11:22:33:44:06",
                                maskedBssid = "02:11:22:••:••:06",
                                isConnectedAp = true,
                                bandGhz = 6,
                                bandLabel = "6 GHz",
                                primaryFreqMhz = 6135,
                                centerFreq0Mhz = 6105,
                                channelNumber = 37,
                                channelWidthMhz = 320,
                                channelWidthLabel = "320 MHz",
                                isPscChannel = true,
                                wifiStandardCode = 8,
                                wifiStandardLabel = "Wi-Fi 7 (11be)",
                                securitySummary = "WPA3-SAE",
                                capabilityBadges = listOf("SAE-H2E", "PMF-Req", "MLO (2L)", "TWT"),
                                rssiDbm = -46,
                                signalPercent = 98,
                                mloAffiliatedLinkCount = 2,
                                countryCode80211d = "US",
                                qbssStationCount = 4,
                                qbssChannelUtilizationPct = 12,
                                ageSeconds = 0.8f,
                            ),
                            com.droid.corewatch.data.SurroundingWifiAp(
                                ssid = "Office-5G-DFS",
                                bssid = "02:11:22:33:44:05",
                                maskedBssid = "02:11:22:••:••:05",
                                bandGhz = 5,
                                bandLabel = "5 GHz",
                                primaryFreqMhz = 5500,
                                centerFreq0Mhz = 5530,
                                channelNumber = 100,
                                channelWidthMhz = 80,
                                channelWidthLabel = "80 MHz",
                                isDfsChannel = true,
                                wifiStandardCode = 6,
                                wifiStandardLabel = "Wi-Fi 6 (11ax)",
                                securitySummary = "WPA2/WPA3-Ent",
                                capabilityBadges = listOf("PMF", "11r", "11k", "11v"),
                                rssiDbm = -61,
                                signalPercent = 71,
                                ageSeconds = 1.4f,
                            ),
                            com.droid.corewatch.data.SurroundingWifiAp(
                                ssid = "IoT-24G",
                                bssid = "02:11:22:33:44:02",
                                maskedBssid = "02:11:22:••:••:02",
                                bandGhz = 2,
                                bandLabel = "2.4 GHz",
                                primaryFreqMhz = 2437,
                                channelNumber = 6,
                                channelWidthMhz = 20,
                                channelWidthLabel = "20 MHz",
                                wifiStandardCode = 4,
                                wifiStandardLabel = "Wi-Fi 4 (11n)",
                                securitySummary = "WPA2-PSK",
                                rssiDbm = -74,
                                signalPercent = 47,
                                ageSeconds = 2.1f,
                            ),
                        ),
                    ),
                ),
            ),
        )
        composeRule.waitForIdle()

        // Verify masked BSSID by default and toggle to full BSSID
        composeRule.onNodeWithText("Lab-WiFi7-6GHz").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("02:11:22:••:••:06").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("wifi_ap_mask_bssid_chip").performScrollTo().performClick()
        composeRule.onNodeWithText("02:11:22:33:44:06").performScrollTo().assertIsDisplayed()

        // Filter to 6 GHz only and cycle sort order
        composeRule.onNodeWithTag("wifi_filter_BAND_6_GHZ").performScrollTo().performClick()
        composeRule.onNodeWithText("Lab-WiFi7-6GHz").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("wifi_ap_sort_chip").performScrollTo().performClick()
        composeRule.onNodeWithTag("wifi_ap_sort_chip").performScrollTo().performClick()
        composeRule.onNodeWithTag("wifi_filter_ALL").performScrollTo().performClick()

        // Exercise TelemetryCollector live scan + capabilities collection with ShadowWifiManager & granted permissions
        val shadowApp = org.robolectric.Shadows.shadowOf(app)
        shadowApp.grantPermissions(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
            android.Manifest.permission.NEARBY_WIFI_DEVICES,
        )
        val wm = app.getSystemService(android.net.wifi.WifiManager::class.java)
        @Suppress("DEPRECATION")
        wm.isWifiEnabled = true
        val shadowWm = org.robolectric.Shadows.shadowOf(wm)
        val sr = android.net.wifi.ScanResult().also { res ->
            @Suppress("DEPRECATION")
            res.SSID = "TestBench-6E"
            res.BSSID = "02:aa:bb:cc:dd:01"
            res.frequency = 5975
            res.channelWidth = android.net.wifi.ScanResult.CHANNEL_WIDTH_160MHZ
            res.centerFreq0 = 6025
            res.level = -51
            res.capabilities = "[RSN-SAE-CCMP][MFPR][ESS]"
            runCatching {
                val ie = android.net.wifi.ScanResult.InformationElement(
                    7,
                    0,
                    byteArrayOf('C'.code.toByte(), 'A'.code.toByte(), 'I'.code.toByte(), 1, 11, 30),
                )
                res.javaClass.getDeclaredField("informationElements").apply {
                    isAccessible = true
                    set(res, arrayOf(ie))
                }
            }
        }
        shadowWm.setScanResults(listOf(sr))
        vm.onPermissionsUpdated()
        vm.triggerWifiScan()
        val liveWifi = vm.state.value.snapshot.wifi
        assertTrue(liveWifi.capabilities.isCapabilitiesQueried)
        assertTrue(liveWifi.surroundingScan.hasScanLocationPermission)
        assertEquals(1, liveWifi.surroundingScan.totalApCount)
        assertEquals("TestBench-6E", liveWifi.surroundingScan.accessPoints.first().ssid)
        assertEquals("WPA3-SAE", liveWifi.surroundingScan.accessPoints.first().securitySummary)
    }

    @Test
    @Config(qualifiers = "w1280dp-h900dp")
    fun usbPeripheralsTabMasterDetailTreeFiltersWarningsAndCrossTabJumpsWorkEndToEnd() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = CorewatchViewModel(app)

        val usbNodes = listOf(
            com.droid.corewatch.data.UsbTopologyNode(
                id = "usb2",
                parentId = null,
                depth = 0,
                busNumber = 2,
                portChain = "Bus 2",
                pciControllerLabel = "PCI 00:0d.0",
                vendorId = 0x1d6b,
                productId = 0x0003,
                vidPidHex = "1d6b:0003",
                productName = "xHCI Host Controller",
                vendorName = "Linux Foundation (xHCI)",
                usbVersionBcd = "3.10",
                deviceClassCode = 0x09,
                negotiatedSpeedMbps = 20_000f,
                speedBadgeLabel = "20 Gbps",
                speedTierDescription = "USB 3.2 Gen 2×2 (20 Gbps)",
                txLanes = 2,
                rxLanes = 2,
                lanesLabel = "2×2 Lanes",
                maxPowerMa = 0,
                isSelfPowered = true,
                category = com.droid.corewatch.data.UsbNodeCategory.RootController,
                companionNodeId = "usb1",
            ),
            com.droid.corewatch.data.UsbTopologyNode(
                id = "usb1",
                parentId = null,
                depth = 0,
                busNumber = 1,
                portChain = "Bus 1",
                pciControllerLabel = "PCI 00:0d.0",
                vendorId = 0x1d6b,
                productId = 0x0002,
                vidPidHex = "1d6b:0002",
                productName = "xHCI Host Controller",
                vendorName = "Linux Foundation (xHCI)",
                usbVersionBcd = "2.00",
                deviceClassCode = 0x09,
                negotiatedSpeedMbps = 480f,
                speedBadgeLabel = "480 Mbps",
                speedTierDescription = "USB 2.0 High-Speed (480 Mbps)",
                txLanes = 1,
                rxLanes = 1,
                lanesLabel = "1×1 Lane",
                maxPowerMa = 0,
                isSelfPowered = true,
                category = com.droid.corewatch.data.UsbNodeCategory.RootController,
                companionNodeId = "usb2",
            ),
            com.droid.corewatch.data.UsbTopologyNode(
                id = "2-4",
                parentId = "usb2",
                depth = 1,
                busNumber = 2,
                portChain = "2-4",
                pciControllerLabel = "PCI 00:0d.0",
                vendorId = 0x8087,
                productId = 0x0b40,
                vidPidHex = "8087:0b40",
                productName = "Thunderbolt 4 Dock Hub",
                vendorName = "Intel",
                usbVersionBcd = "3.20",
                deviceClassCode = 0x09,
                negotiatedSpeedMbps = 10_000f,
                speedBadgeLabel = "10 Gbps",
                speedTierDescription = "USB 3.2 Gen 2 (10 Gbps)",
                txLanes = 1,
                rxLanes = 1,
                lanesLabel = "1×1 Lane",
                maxPowerMa = 0,
                isSelfPowered = true,
                downstreamRequestedMa = 256,
                removableRaw = "removable",
                category = com.droid.corewatch.data.UsbNodeCategory.Hub,
            ),
            com.droid.corewatch.data.UsbTopologyNode(
                id = "2-4.1",
                parentId = "2-4",
                depth = 2,
                busNumber = 2,
                portChain = "2-4.1",
                pciControllerLabel = "PCI 00:0d.0",
                vendorId = 0x0bda,
                productId = 0x8156,
                vidPidHex = "0bda:8156",
                productName = "USB 10/100/1G/2.5G LAN",
                vendorName = "Realtek Semiconductor",
                usbVersionBcd = "3.20",
                deviceClassCode = 0x00,
                negotiatedSpeedMbps = 5_000f,
                speedBadgeLabel = "5 Gbps",
                speedTierDescription = "USB 3.2 Gen 1 (5 Gbps)",
                txLanes = 1,
                rxLanes = 1,
                lanesLabel = "1×1 Lane",
                maxPowerMa = 256,
                isSelfPowered = false,
                removableRaw = "fixed",
                category = com.droid.corewatch.data.UsbNodeCategory.NetworkAdapter,
                interfaces = listOf(
                    com.droid.corewatch.data.UsbInterfaceSummary(0, 0xff, 0xff, 0x00, "Vendor Specific", "r8152", "2.5G Ethernet", 3),
                ),
                correlatedInfo = com.droid.corewatch.data.UsbCorrelatedInfo(
                    usbManagerEndpointTotal = 3,
                    usbManagerConfigCount = 2,
                    isPresentInUsbManager = true,
                ),
            ),
            com.droid.corewatch.data.UsbTopologyNode(
                id = "1-2",
                parentId = "usb1",
                depth = 1,
                busNumber = 1,
                portChain = "1-2",
                pciControllerLabel = "PCI 00:0d.0",
                vendorId = 0x0781,
                productId = 0x5583,
                vidPidHex = "0781:5583",
                productName = "Extreme NVMe SSD",
                vendorName = "SanDisk / Western Digital",
                usbVersionBcd = "3.20",
                deviceClassCode = 0x00,
                negotiatedSpeedMbps = 480f,
                speedBadgeLabel = "480 Mbps",
                speedTierDescription = "USB 2.0 High-Speed (480 Mbps)",
                txLanes = 1,
                rxLanes = 1,
                lanesLabel = "1×1 Lane",
                maxPowerMa = 500,
                isSelfPowered = false,
                removableRaw = "removable",
                category = com.droid.corewatch.data.UsbNodeCategory.Storage,
                healthStatus = com.droid.corewatch.data.UsbHealthStatus.SpeedBottleneck,
                healthMessage = "SuperSpeed Downgrade: Device supports USB 3.20 (5+ Gbps) but negotiated 480 Mbps.",
                correlatedInfo = com.droid.corewatch.data.UsbCorrelatedInfo(
                    storageVolumeSummary = "SanDisk Extreme (mounted)",
                ),
            ),
            com.droid.corewatch.data.UsbTopologyNode(
                id = "1-3",
                parentId = "usb1",
                depth = 1,
                busNumber = 1,
                portChain = "1-3",
                pciControllerLabel = "PCI 00:0d.0",
                vendorId = 0x413c,
                productId = 0xb0a2,
                vidPidHex = "413c:b0a2",
                productName = "Dell Pro TBT4 Smart SD25TB4",
                vendorName = "Dell",
                usbVersionBcd = "2.01",
                deviceClassCode = 0x00,
                negotiatedSpeedMbps = 480f,
                speedBadgeLabel = "480 Mbps",
                speedTierDescription = "USB 2.0 High-Speed (480 Mbps)",
                txLanes = 1,
                rxLanes = 1,
                lanesLabel = "1×1 Lane",
                maxPowerMa = 100,
                isSelfPowered = true,
                removableRaw = "removable",
                category = com.droid.corewatch.data.UsbNodeCategory.Dock,
                passiveNote = "Dock exposes a passive USB Billboard (0x11) management descriptor.",
            ),
            com.droid.corewatch.data.UsbTopologyNode(
                id = "1-5",
                parentId = "usb1",
                depth = 1,
                busNumber = 1,
                portChain = "1-5",
                pciControllerLabel = "PCI 00:0d.0",
                vendorId = 0x04f2,
                productId = 0xb890,
                vidPidHex = "04f2:b890",
                productName = "Integrated 5M Webcam",
                vendorName = "Chicony Electronics",
                usbVersionBcd = "2.01",
                deviceClassCode = 0xef,
                negotiatedSpeedMbps = 480f,
                speedBadgeLabel = "480 Mbps",
                speedTierDescription = "USB 2.0 High-Speed (480 Mbps)",
                txLanes = 1,
                rxLanes = 1,
                lanesLabel = "1×1 Lane",
                maxPowerMa = 500,
                isSelfPowered = false,
                isInternal = true,
                removableRaw = "fixed",
                category = com.droid.corewatch.data.UsbNodeCategory.VideoWebcam,
            ),
            com.droid.corewatch.data.UsbTopologyNode(
                id = "1-10",
                parentId = "usb1",
                depth = 1,
                busNumber = 1,
                portChain = "1-10",
                pciControllerLabel = "PCI 00:0d.0",
                vendorId = 0x8087,
                productId = 0x0037,
                vidPidHex = "8087:0037",
                productName = "Intel BE201 Bluetooth Adapter",
                vendorName = "Intel",
                usbVersionBcd = "2.01",
                deviceClassCode = 0xe0,
                negotiatedSpeedMbps = 12f,
                speedBadgeLabel = "12 Mbps",
                speedTierDescription = "USB 1.1 Full-Speed (12 Mbps)",
                txLanes = 1,
                rxLanes = 1,
                lanesLabel = "1×1 Lane",
                maxPowerMa = 100,
                isSelfPowered = true,
                isInternal = true,
                removableRaw = "fixed",
                category = com.droid.corewatch.data.UsbNodeCategory.WirelessController,
                interfaces = listOf(
                    com.droid.corewatch.data.UsbInterfaceSummary(0, 0xe0, 0x01, 0x01, "Wireless / Bluetooth", "btusb", "Bluetooth HCI", 3),
                ),
            ),
        )

        composeRule.setContent {
            CorewatchTheme(dark = true) {
                CorewatchAppRoot(vm = vm)
            }
        }

        // 1. Ungranted DUMP state shows locked Type-C banner + Copy ADB Command chip while full USB tree works!
        vm.setSnapshotForTesting(
            SystemTelemetrySnapshot(
                usbPeripherals = com.droid.corewatch.data.UsbPeripheralTelemetrySnapshot(
                    nodes = usbNodes,
                    externalPeripheralCount = 3,
                    externalHubCount = 2,
                    internalDeviceCount = 2,
                    warningCount = 1,
                    maxExternalSpeedMbps = 10_000f,
                    maxExternalSpeedLabel = "10 Gbps",
                    maxControllerSpeedLabel = "20 Gbps",
                    hasDumpPermission = false,
                    hasTypeCPorts = false,
                    hotplugEvents = listOf(
                        com.droid.corewatch.data.UsbHotplugEvent(
                            timestampLabel = "15:40:12",
                            elapsedRealtimeMs = 1_000L,
                            nodeId = "1-2",
                            deviceName = "Extreme NVMe SSD",
                            vidPidHex = "0781:5583",
                            speedBadgeLabel = "480 Mbps",
                            action = com.droid.corewatch.data.UsbHotplugAction.BottleneckDetected,
                            detail = "SuperSpeed Downgrade on port 1-2",
                        ),
                    ),
                ),
            ),
        )
        composeRule.onNodeWithTag("tab_6").performClick()
        assertEquals(CorewatchTab.UsbPeripherals, vm.state.value.activeTab)
        composeRule.onNodeWithTag("usb_summary_typec_card").assertIsDisplayed()
        composeRule.onNodeWithTag("usb_typec_locked_banner").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("copy_usb_dump_adb_btn").performScrollTo().performClick()
        assertTrue(vm.state.value.statusBanner!!.contains("ADB DUMP"))

        // 2. External Only hides Integrated 5M Webcam (1-5) by default; Include Internal reveals it
        composeRule.onNodeWithTag("usb_node_2-4.1").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("usb_node_1-5").assertDoesNotExist()
        composeRule.onNodeWithTag("usb_toggle_internal_chip").performScrollTo().performClick()
        assertTrue(vm.state.value.showInternalUsbDevices)
        composeRule.onNodeWithTag("usb_node_1-5").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("usb_toggle_internal_chip").performScrollTo().performClick()
        assertFalse(vm.state.value.showInternalUsbDevices)

        // 3. Select Bottlenecked NVMe SSD (1-2) and verify warning banner & StorageManager correlation
        composeRule.onNodeWithTag("usb_node_1-2").performScrollTo().performClick()
        composeRule.onNodeWithTag("usb_node_warning_banner").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("usb_correlated_capabilities_box").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("StorageManager: SanDisk Extreme (mounted)").performScrollTo().assertIsDisplayed()

        // 4. Filter by WarningsOnly, then All, collapse/expand hub 2-4, and jump across companion & tabs
        composeRule.onNodeWithTag("usb_search_input").performScrollTo()
        composeRule.onNodeWithTag("usb_filter_WarningsOnly").performClick()
        composeRule.onNodeWithTag("usb_node_1-2").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("usb_node_2-4.1").assertDoesNotExist()
        composeRule.onNodeWithTag("usb_search_input").performScrollTo()
        composeRule.onNodeWithTag("usb_filter_All").performClick()

        composeRule.onNodeWithTag("usb_collapse_2-4").performScrollTo().performClick()
        composeRule.onNodeWithTag("usb_node_2-4.1").assertDoesNotExist()
        composeRule.onNodeWithTag("usb_collapse_2-4").performScrollTo().performClick()
        composeRule.onNodeWithTag("usb_node_2-4.1").performScrollTo().assertIsDisplayed()

        // Select root bus usb2 -> click companion usb1 jump chip
        composeRule.onNodeWithTag("usb_node_usb2").performScrollTo().performClick()
        assertEquals("usb2", vm.state.value.selectedUsbNodeId)
        composeRule.onNodeWithTag("usb_node_details_card").performScrollTo()
        composeRule.onNodeWithTag("usb_jump_companion_btn").performClick()
        assertEquals("usb1", vm.state.value.selectedUsbNodeId)

        // Select Dell Dock (1-3) -> jump to Displays; 2.5G NIC (2-4.1) -> jump to Network; Bluetooth (1-10) -> jump to Bluetooth
        composeRule.onNodeWithTag("usb_node_1-3").performScrollTo().performClick()
        composeRule.onNodeWithTag("usb_node_details_card").performScrollTo()
        composeRule.onNodeWithTag("usb_jump_displays_tab_btn").performClick()
        assertEquals(CorewatchTab.Displays, vm.state.value.activeTab)

        composeRule.onNodeWithTag("tab_6").performClick()
        composeRule.onNodeWithTag("usb_node_2-4.1").performScrollTo().performClick()
        composeRule.onNodeWithTag("usb_node_details_card").performScrollTo()
        composeRule.onNodeWithTag("usb_jump_network_tab_btn").performClick()
        assertEquals(CorewatchTab.Network, vm.state.value.activeTab)

        composeRule.onNodeWithTag("tab_6").performClick()
        composeRule.onNodeWithTag("usb_toggle_internal_chip").performScrollTo().performClick()
        composeRule.onNodeWithTag("usb_node_1-10").performScrollTo().performClick()
        composeRule.onNodeWithTag("usb_node_details_card").performScrollTo()
        composeRule.onNodeWithTag("usb_jump_bluetooth_tab_btn").performClick()
        assertEquals(CorewatchTab.Bluetooth, vm.state.value.activeTab)

        // 5. Granted DUMP state renders live Type-C port tiles
        composeRule.onNodeWithTag("tab_6").performClick()
        vm.setSnapshotForTesting(
            vm.state.value.snapshot.copy(
                usbPeripherals = vm.state.value.snapshot.usbPeripherals.copy(
                    hasDumpPermission = true,
                    hasTypeCPorts = true,
                    typeCPorts = listOf(
                        com.droid.corewatch.data.TypeCPortStatus(
                            portId = "port0",
                            supportedModes = "Dual-Role (DRP)",
                            isConnected = true,
                            currentMode = "DFP",
                            powerRole = "Sink",
                            dataRole = "Host",
                            usbDataStatus = "Enabled",
                            displayPortAltModeSummary = "DP Alt-Mode (4-Lane)",
                        ),
                    ),
                ),
            ),
        )
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("typec_port_port0").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("usb_hotplug_timeline_card").performScrollTo().assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    @Config(qualifiers = "w560dp-h800dp")
    fun usbPeripheralsTabCompactStackedLayoutSearchSpacebarGuardAndEmptyStateWork() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = CorewatchViewModel(app)
        composeRule.setContent {
            CorewatchTheme(dark = false) {
                CorewatchAppRoot(vm = vm)
            }
        }
        vm.selectTab(CorewatchTab.UsbPeripherals)
        vm.setSnapshotForTesting(
            SystemTelemetrySnapshot(
                bluetooth = BluetoothTelemetrySnapshot(
                    isAdapterEnabled = true,
                    hasConnectPermission = true,
                    audioDrops = BluetoothAudioDropStats(
                        sourceLabel = "BT STACK DUMPSYS (LIVE)",
                        hasStackCounters = true,
                        a2dpExpectedFrames = 1_000L,
                    ),
                ),
                usbPeripherals = com.droid.corewatch.data.UsbPeripheralTelemetrySnapshot(
                    nodes = listOf(
                        com.droid.corewatch.data.UsbTopologyNode(
                            id = "1-5",
                            parentId = null,
                            depth = 0,
                            busNumber = 1,
                            portChain = "1-5",
                            pciControllerLabel = "PCI 00:0d.0",
                            vendorId = 0x04f2,
                            productId = 0xb890,
                            vidPidHex = "04f2:b890",
                            productName = "Integrated 5M Webcam",
                            vendorName = "Chicony Electronics",
                            usbVersionBcd = "2.01",
                            deviceClassCode = 0xef,
                            negotiatedSpeedMbps = 480f,
                            speedBadgeLabel = "480 Mbps",
                            speedTierDescription = "USB 2.0 High-Speed (480 Mbps)",
                            txLanes = 1,
                            rxLanes = 1,
                            lanesLabel = "1×1 Lane",
                            maxPowerMa = 500,
                            isSelfPowered = false,
                            isInternal = true,
                            removableRaw = "fixed",
                            category = com.droid.corewatch.data.UsbNodeCategory.VideoWebcam,
                        ),
                    ),
                    internalDeviceCount = 1,
                    hasDumpPermission = true,
                    hasTypeCPorts = false,
                ),
            ),
        )
        composeRule.waitForIdle()
        // External Only empty state explains hidden internal devices
        composeRule.onNodeWithTag("usb_empty_tree_state").performScrollTo().assertIsDisplayed()

        // Reveal internal device and type a search query containing Spacebar without pausing telemetry
        vm.toggleShowInternalUsbDevices()
        composeRule.waitForIdle()
        assertFalse(vm.state.value.isPaused)
        composeRule.onNodeWithTag("usb_search_input").performScrollTo().performClick()
        composeRule.onNodeWithTag("usb_search_input").performKeyInput {
            pressKey(Key.Spacebar)
        }
        composeRule.waitForIdle()
        assertFalse("Spacebar inside focused USB search bar must not pause telemetry", vm.state.value.isPaused)
        composeRule.onNodeWithTag("usb_search_input").performTextReplacement("Integrated 5M")
        composeRule.onNodeWithTag("usb_node_1-5").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("usb_node_details_card").performScrollTo().assertIsDisplayed()

        // Also verify compact stacked Bluetooth audio drop cards on 560dp (< 680dp)
        vm.selectTab(CorewatchTab.Bluetooth)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("bluetooth_audio_drops_section").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun displaysTabZerosMetricsAndShowsCopyAdbButtonWhenDumpLocked() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = CorewatchViewModel(app)
        vm.setSnapshotForTesting(
            SystemTelemetrySnapshot(
                frameJank = com.droid.corewatch.data.FrameJankSnapshot(
                    hasDumpPermission = false,
                ),
            ),
        )
        vm.selectTab(CorewatchTab.Displays)
        composeRule.setContent {
            CorewatchTheme(dark = true) {
                CorewatchAppRoot(vm = vm, canDrawOverlays = false)
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("frame_jank_card").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("SYSTEM-WIDE (LOCKED)").assertIsDisplayed()
        composeRule.onNodeWithText("0 missed VSyncs / 0 expected").assertIsDisplayed()
        composeRule.onNodeWithText("0 late frames / 0 rendered").assertIsDisplayed()
        composeRule.onNodeWithText(
            "Jank Causes (5m): Long CPU: 0 · Long GPU: 0 · Buffer Stuffing: 0 · Missed VSync: 0",
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            "VRR Deadline Accounting: Waiting for system-wide GraphicsStats permission",
        ).assertIsDisplayed()
        composeRule.onNodeWithTag("display_dump_locked_banner").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("copy_display_dump_adb_btn").performScrollTo().performClick()
        assertTrue(vm.state.value.statusBanner.orEmpty().contains("Copied ADB GraphicsStats"))
    }

    @Test
    fun displaysTabRendersSystemWideGraphicsStatsAndLockedAdbHelper() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = CorewatchViewModel(app)

        // 1. Locked state (hasDumpPermission = false): SYSTEM-WIDE (LOCKED) + zeroed metrics + Copy ADB Command chip
        val lockedSnap = SystemTelemetrySnapshot(
            frameJank = com.droid.corewatch.data.FrameJankSnapshot(
                hasDumpPermission = false,
            ),
        )
        vm.setSnapshotForTesting(lockedSnap)
        vm.selectTab(CorewatchTab.Displays)

        composeRule.setContent {
            CorewatchTheme(dark = true) {
                CorewatchAppRoot(vm = vm, canDrawOverlays = false)
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("frame_jank_card").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("frame_jank_beta_badge").assertIsDisplayed()
        composeRule.onNodeWithText("Beta").assertIsDisplayed()
        composeRule.onNodeWithText("SYSTEM-WIDE (LOCKED)").assertIsDisplayed()
        composeRule.onNodeWithTag("display_dump_locked_banner").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("copy_display_dump_adb_btn").performScrollTo().performClick()
        assertTrue(vm.state.value.statusBanner.orEmpty().contains("Copied ADB GraphicsStats"))

        // 2. System-Wide GraphicsStats active (hasDumpPermission = true, 4 active apps): hides ADB helper banner
        val sysWideSnap = SystemTelemetrySnapshot(
            frameJank = com.droid.corewatch.data.FrameJankSnapshot(
                evaluatedFrames5m = 4_820,
                droppedFrames5m = 6,
                jankyFrames5m = 4,
                longCpuJank5m = 2,
                longGpuJank5m = 1,
                bufferStuffing5m = 1,
                sfMissedFrames5m = 1,
                windowDurationMs = 95_000L,
                activeDeadlineHzMin = 60,
                activeDeadlineHzMax = 120,
                isSystemWideGraphicsStats = true,
                hasDumpPermission = true,
                activeAppCount5m = 4,
            ),
        )
        composeRule.runOnUiThread {
            vm.setSnapshotForTesting(sysWideSnap)
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("SYSTEM-WIDE (GRAPHICSSTATS)").assertIsDisplayed()
        composeRule.onNodeWithText("Window: 01:35 / 05:00 · 4820 evaluated frames (4 active apps)").assertIsDisplayed()
        composeRule.onNodeWithText(
            "VRR Deadline Accounting: System-wide HWUI FrameTimeline / GraphicsStats across all apps [60, 120] Hz",
        ).assertIsDisplayed()
        composeRule.onNodeWithTag("display_dump_locked_banner").assertDoesNotExist()
    }

    @Suppress("DEPRECATION")
    @Test
    fun telemetryCollectorCoversBluetoothUsbGpuDisplayWifiAndBatteryFallbackBranches() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val shadowApp = org.robolectric.Shadows.shadowOf(app)
        val tempRoot = java.nio.file.Files.createTempDirectory("corewatch_branch_sysfs").toFile()

        try {
            // 1. Null-services ContextWrapper to cover defensive null-service branches and empty-DisplayManager fallback
            val nullServicesCtx = object : android.content.ContextWrapper(app) {
                override fun getSystemService(name: String): Any? = null
                override fun getApplicationContext(): android.content.Context = this
            }
            com.droid.corewatch.data.AccelIoctlBridge.testDrmConnectorsProvider = { "eDP-1|1|1|10|300x190" }
            val nullSvcCollector = com.droid.corewatch.data.TelemetryCollector(context = nullServicesCtx)
            val nullSvcSnap = nullSvcCollector.sample(CaffeineConfig(), nowElapsedMs = 1_000L)
            assertEquals("No Adapter", nullSvcSnap.bluetooth.connectionStateLabel)
            assertEquals(1, nullSvcSnap.displays.size)
            assertEquals("eDP-1", nullSvcSnap.displays.first().drmConnectorName)
            assertFalse(nullSvcCollector.triggerActiveWifiScan().first)

            // 2. Setup i915 DRM directory, NPU freq sub-directory, PSI memory, and USB sysfs devices
            val drmI915 = java.io.File(tempRoot, "drm_i915").apply { mkdirs() }
            val i915Gt = java.io.File(drmI915, "card0/gt/gt0").apply { mkdirs() }
            java.io.File(i915Gt, "rps_act_freq_mhz").writeText("950\n")
            java.io.File(i915Gt, "rps_cur_freq_mhz").writeText("1000\n")
            java.io.File(i915Gt, "rps_min_freq_mhz").writeText("300\n")
            java.io.File(i915Gt, "rps_max_freq_mhz").writeText("1450\n")
            java.io.File(i915Gt, "rps_RP0_freq_mhz").writeText("1450\n")
            java.io.File(i915Gt, "rps_RP1_freq_mhz").writeText("500\n")
            java.io.File(i915Gt, "rps_RPn_freq_mhz").writeText("300\n")
            val rc6File = java.io.File(i915Gt, "rc6_residency_ms").apply { writeText("1000\n") }

            val drmXe = java.io.File(tempRoot, "drm_xe").apply { mkdirs() }
            val xeFreq0 = java.io.File(drmXe, "card0/device/tile0/gt0/freq0").apply { mkdirs() }
            java.io.File(xeFreq0, "act_freq").writeText("0\n")
            java.io.File(xeFreq0, "cur_freq").writeText("400\n")
            java.io.File(xeFreq0, "rpa_freq").writeText("1800\n")
            val xeFreq1 = java.io.File(drmXe, "card0/device/tile0/gt1/freq0").apply { mkdirs() }
            java.io.File(xeFreq1, "act_freq").writeText("0\n")
            java.io.File(xeFreq1, "cur_freq").writeText("600\n")
            val xeThr = java.io.File(xeFreq0, "throttle").apply { mkdirs() }
            java.io.File(xeThr, "status").writeText("1\n")
            java.io.File(xeThr, "reason_pl1").writeText("1\n")
            java.io.File(xeThr, "reason_thermal").writeText("1\n")
            java.io.File(drmXe, "card0/device/tile0/gt0/gtidle").apply { mkdirs() }
                .let { java.io.File(it, "idle_residency_ms").writeText("2000\n") }

            val accelRoot = java.io.File(tempRoot, "accel").apply { mkdirs() }
            val accelDev = java.io.File(accelRoot, "accel0/device").apply { mkdirs() }
            java.io.File(accelDev, "npu_memory_utilization").writeText("4096\n")
            val accelFreq = java.io.File(accelDev, "freq").apply { mkdirs() }
            java.io.File(accelFreq, "current_freq").writeText("1200\n")
            java.io.File(accelFreq, "hw_max_freq").writeText("2000\n")
            java.io.File(accelFreq, "hw_min_freq").writeText("200\n")
            java.io.File(accelFreq, "hw_efficient_freq").writeText("800\n")

            val psiFile = java.io.File(tempRoot, "psi_memory").apply {
                writeText("some avg10=4.25 avg60=1.50 avg300=0.50 total=100\nfull avg10=1.10 avg60=0.20 avg300=0.10 total=20\n")
            }

            val usbRoot = java.io.File(tempRoot, "usb").apply { mkdirs() }
            // USB Audio DAC (0bda:4014)
            val usbDac = java.io.File(usbRoot, "1-1").apply { mkdirs() }
            java.io.File(usbDac, "idVendor").writeText("0bda\n")
            java.io.File(usbDac, "idProduct").writeText("4014\n")
            java.io.File(usbDac, "speed").writeText("480\n")
            java.io.File(usbDac, "version").writeText(" 2.00\n")
            java.io.File(usbDac, "product").writeText("USB Audio DAC\n")
            java.io.File(usbDac, "removable").writeText("removable\n")
            val usbDacIf = java.io.File(usbRoot, "1-1:1.0").apply { mkdirs() }
            java.io.File(usbDacIf, "bInterfaceClass").writeText("01\n")
            java.io.File(usbDacIf, "bNumEndpoints").writeText("02\n")

            // External UVC Camera + HID receiver (046d:085e)
            val usbCam = java.io.File(usbRoot, "1-2").apply { mkdirs() }
            java.io.File(usbCam, "idVendor").writeText("046d\n")
            java.io.File(usbCam, "idProduct").writeText("085e\n")
            java.io.File(usbCam, "speed").writeText("5000\n")
            java.io.File(usbCam, "version").writeText(" 3.10\n")
            java.io.File(usbCam, "product").writeText("BRIO 4K Stream Edition\n")
            java.io.File(usbCam, "removable").writeText("removable\n")
            val usbCamIf = java.io.File(usbRoot, "1-2:1.0").apply { mkdirs() }
            java.io.File(usbCamIf, "bInterfaceClass").writeText("0e\n")
            java.io.File(usbCamIf, "bNumEndpoints").writeText("01\n")

            // External USB Mass Storage (0781:5583)
            val usbSto = java.io.File(usbRoot, "1-3").apply { mkdirs() }
            java.io.File(usbSto, "idVendor").writeText("0781\n")
            java.io.File(usbSto, "idProduct").writeText("5583\n")
            java.io.File(usbSto, "speed").writeText("10000\n")
            java.io.File(usbSto, "version").writeText(" 3.20\n")
            java.io.File(usbSto, "product").writeText("Extreme SSD\n")
            java.io.File(usbSto, "removable").writeText("removable\n")
            val usbStoIf = java.io.File(usbRoot, "1-3:1.0").apply { mkdirs() }
            java.io.File(usbStoIf, "bInterfaceClass").writeText("08\n")
            java.io.File(usbStoIf, "bNumEndpoints").writeText("02\n")

            // 3. Populate InputManager, CameraManager, StorageManager, ActivityManager, and Battery sticky intent
            val im = app.getSystemService(android.hardware.input.InputManager::class.java)
            val shadowIm = org.robolectric.Shadows.shadowOf(im)
            val inputDev = org.robolectric.shadows.ShadowInputDevice.makeInputDeviceNamed("BRIO HID")
            org.robolectric.util.ReflectionHelpers.setField(inputDev, "mId", 42)
            org.robolectric.util.ReflectionHelpers.setField(inputDev, "mVendorId", 0x046d)
            org.robolectric.util.ReflectionHelpers.setField(inputDev, "mProductId", 0x085e)
            org.robolectric.util.ReflectionHelpers.setField(inputDev, "mIsExternal", true)
            org.robolectric.util.ReflectionHelpers.setField(
                inputDev,
                "mSources",
                android.view.InputDevice.SOURCE_KEYBOARD or
                    android.view.InputDevice.SOURCE_MOUSE or
                    android.view.InputDevice.SOURCE_TOUCHPAD or
                    android.view.InputDevice.SOURCE_STYLUS or
                    android.view.InputDevice.SOURCE_GAMEPAD,
            )
            org.robolectric.util.ReflectionHelpers.setField(
                inputDev,
                "mKeyboardType",
                android.view.InputDevice.KEYBOARD_TYPE_ALPHABETIC,
            )
            shadowIm.addInputDevice(inputDev)

            val camMgr = app.getSystemService(android.hardware.camera2.CameraManager::class.java)
            val shadowCam = org.robolectric.Shadows.shadowOf(camMgr)
            val camChars = org.robolectric.shadows.ShadowCameraCharacteristics.newCameraCharacteristics()
            org.robolectric.Shadows.shadowOf(camChars).set(
                android.hardware.camera2.CameraCharacteristics.LENS_FACING,
                android.hardware.camera2.CameraCharacteristics.LENS_FACING_EXTERNAL,
            )
            shadowCam.addCamera("ext_uvc_0", camChars)

            val sm = app.getSystemService(android.os.storage.StorageManager::class.java)
            val shadowSm = org.robolectric.Shadows.shadowOf(sm)
            val remVol = org.robolectric.shadows.StorageVolumeBuilder(
                "usb_vol_1",
                tempRoot,
                "Extreme SSD",
                android.os.Process.myUserHandle(),
                android.os.Environment.MEDIA_MOUNTED,
            ).setIsRemovable(true).build()
            shadowSm.addStorageVolume(remVol)

            val am = app.getSystemService(android.app.ActivityManager::class.java)
            val shadowAm = org.robolectric.Shadows.shadowOf(am)
            val nowWallMs = System.currentTimeMillis()
            shadowAm.addApplicationExitInfo(
                org.robolectric.shadows.ShadowActivityManager.ApplicationExitInfoBuilder.newBuilder()
                    .setProcessName("com.heavy.game")
                    .setPid(4120)
                    .setReason(android.app.ApplicationExitInfo.REASON_LOW_MEMORY)
                    .setPss(512_000L)
                    .setTimestamp(nowWallMs - 2_000L)
                    .build(),
            )
            shadowAm.addApplicationExitInfo(
                org.robolectric.shadows.ShadowActivityManager.ApplicationExitInfoBuilder.newBuilder()
                    .setProcessName("com.bg.sync")
                    .setPid(4121)
                    .setReason(android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE)
                    .setPss(128_000L)
                    .setTimestamp(nowWallMs - 3_000L)
                    .build(),
            )
            shadowAm.addApplicationExitInfo(
                org.robolectric.shadows.ShadowActivityManager.ApplicationExitInfoBuilder.newBuilder()
                    .setProcessName("com.normal.exit")
                    .setPid(4122)
                    .setReason(android.app.ApplicationExitInfo.REASON_OTHER)
                    .setTimestamp(nowWallMs - 1_000L)
                    .build(),
            )
            shadowAm.addApplicationExitInfo(
                org.robolectric.shadows.ShadowActivityManager.ApplicationExitInfoBuilder.newBuilder()
                    .setProcessName("com.old.kill")
                    .setPid(4100)
                    .setReason(android.app.ApplicationExitInfo.REASON_LOW_MEMORY)
                    .setTimestamp(1_000L)
                    .build(),
            )

            app.sendStickyBroadcast(
                android.content.Intent(android.content.Intent.ACTION_BATTERY_CHANGED).apply {
                    putExtra(android.os.BatteryManager.EXTRA_STATUS, android.os.BatteryManager.BATTERY_STATUS_DISCHARGING)
                    putExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0)
                    putExtra(android.os.BatteryManager.EXTRA_LEVEL, 45)
                    putExtra(android.os.BatteryManager.EXTRA_SCALE, 100)
                    putExtra(android.os.BatteryManager.EXTRA_VOLTAGE, 11_400)
                    putExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 325)
                    putExtra(android.os.BatteryManager.EXTRA_CYCLE_COUNT, 42)
                    putExtra(android.os.BatteryManager.EXTRA_HEALTH, android.os.BatteryManager.BATTERY_HEALTH_OVERHEAT)
                },
            )

            // 4. Configure BluetoothAdapter, bonded devices, and AudioManager outputs / modes
            val btMgr = app.getSystemService(android.bluetooth.BluetoothManager::class.java)
            val adapter = btMgr.adapter
            val shadowAdapter = org.robolectric.Shadows.shadowOf(adapter)
            shadowAdapter.setEnabled(true)
            shadowAdapter.setIsLe2MPhySupported(true)
            shadowAdapter.setIsLeCodedPhySupported(true)
            shadowAdapter.setLeAudioSupported(10)

            val btAudioDev = org.robolectric.shadows.ShadowBluetoothDevice.newInstance("AA:BB:CC:DD:EE:01").also { d ->
                org.robolectric.Shadows.shadowOf(d).apply {
                    setName("Sony WH-1000XM5")
                    setType(android.bluetooth.BluetoothDevice.DEVICE_TYPE_DUAL)
                    setConnected(true)
                    setBatteryLevel(85)
                    runCatching {
                        val btClass = org.robolectric.util.ReflectionHelpers.callConstructor(
                            android.bluetooth.BluetoothClass::class.java,
                            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, 0x240404),
                        )
                        setBluetoothClass(btClass)
                    }
                }
            }
            val btBleDev = org.robolectric.shadows.ShadowBluetoothDevice.newInstance("AA:BB:CC:DD:EE:02").also { d ->
                org.robolectric.Shadows.shadowOf(d).apply {
                    setName("MX Master 3S")
                    setType(android.bluetooth.BluetoothDevice.DEVICE_TYPE_LE)
                    setConnected(true)
                    setBatteryLevel(90)
                }
            }
            val btStandbyDev = org.robolectric.shadows.ShadowBluetoothDevice.newInstance("AA:BB:CC:DD:EE:03").also { d ->
                org.robolectric.Shadows.shadowOf(d).apply {
                    setName("Pixel Buds Pro")
                    setType(android.bluetooth.BluetoothDevice.DEVICE_TYPE_CLASSIC)
                    setConnected(false)
                }
            }
            val btBlankDev = org.robolectric.shadows.ShadowBluetoothDevice.newInstance("AA:BB:CC:DD:EE:04").also { d ->
                org.robolectric.Shadows.shadowOf(d).setName("")
            }
            shadowAdapter.setBondedDevices(setOf(btAudioDev, btBleDev, btStandbyDev, btBlankDev))

            val audioMgr = app.getSystemService(android.media.AudioManager::class.java)
            val shadowAudio = org.robolectric.Shadows.shadowOf(audioMgr)
            val a2dpOut = org.robolectric.shadows.AudioDeviceInfoBuilder.newBuilder()
                .setType(android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
                .build()
            val scoOut = org.robolectric.shadows.AudioDeviceInfoBuilder.newBuilder()
                .setType(android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
                .build()
            val bleOut = org.robolectric.shadows.AudioDeviceInfoBuilder.newBuilder()
                .setType(android.media.AudioDeviceInfo.TYPE_BLE_HEADSET)
                .build()
            val usbHeadsetOut = org.robolectric.shadows.AudioDeviceInfoBuilder.newBuilder()
                .setType(android.media.AudioDeviceInfo.TYPE_USB_HEADSET)
                .build()
            val hdmiOut = org.robolectric.shadows.AudioDeviceInfoBuilder.newBuilder()
                .setType(android.media.AudioDeviceInfo.TYPE_HDMI)
                .build()
            val wiredOut = org.robolectric.shadows.AudioDeviceInfoBuilder.newBuilder()
                .setType(android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
                .build()
            shadowAudio.setOutputDevices(listOf(a2dpOut, usbHeadsetOut))

            // 5. Exercise BLUETOOTH_CONNECT denied (adapter enabled & disabled), then granted with A2DP logcat codec fallback
            shadowApp.denyPermissions(android.Manifest.permission.BLUETOOTH_CONNECT)
            val collectorI915 = com.droid.corewatch.data.TelemetryCollector(
                context = app,
                drmSysfsRoot = drmI915,
                accelSysfsRoot = accelRoot,
                procPsiMemoryFile = psiFile,
                usbSysfsRoot = usbRoot,
            )
            val deniedBtOn = collectorI915.sample(CaffeineConfig(), nowElapsedMs = 1_000L)
            assertEquals("On (Grant Permission)", deniedBtOn.bluetooth.connectionStateLabel)
            assertFalse(deniedBtOn.battery.isCharging)
            assertEquals(45, deniedBtOn.battery.levelPercent)
            assertTrue(deniedBtOn.lmkd.killCount5m >= 2)
            assertEquals(4.25f, deniedBtOn.lmkd.psiMemorySomeAvg10, 0.01f)

            shadowAdapter.setEnabled(false)
            rc6File.writeText("1600\n")
            val deniedBtOff = collectorI915.sample(CaffeineConfig(), nowElapsedMs = 2_000L)
            assertEquals("Off", deniedBtOff.bluetooth.connectionStateLabel)
            assertEquals(0.40f, deniedBtOff.gpu.utilizationFraction!!, 0.02f)

            // Grant BLUETOOTH_CONNECT, DUMP, READ_LOGS, ACCESS_FINE_LOCATION, NEARBY_WIFI_DEVICES
            shadowAdapter.setEnabled(true)
            shadowApp.grantPermissions(
                android.Manifest.permission.BLUETOOTH_CONNECT,
                android.Manifest.permission.DUMP,
                android.Manifest.permission.READ_LOGS,
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.NEARBY_WIFI_DEVICES,
                android.Manifest.permission.PACKAGE_USAGE_STATS,
            )
            val extDisplayId = org.robolectric.shadows.ShadowDisplayManager.addDisplay("w1920dp-h1080dp")
            org.robolectric.util.ReflectionHelpers.setField(collectorI915, "btStackRefreshInFlight", true)
            org.robolectric.util.ReflectionHelpers.setField(collectorI915, "usbDumpsysRefreshInFlight", true)
            // Sample once with empty logcat text while A2DP is active so readSystemProperty fallback executes
            collectorI915.sample(CaffeineConfig(), nowElapsedMs = 5_000L)
            org.robolectric.util.ReflectionHelpers.setField(
                collectorI915,
                "cachedBtLogcatText",
                "mCodecConfig: {codecName:LDAC,mCodecType:4,mCodecPriority:1000,mSampleRate:0x4(96000),mBitsPerSample:0x4(24),mChannelMode:0x2(STEREO),mCodecSpecific1:1000}\n" +
                    "mCodecsSelectableCapabilities: [{codecName:LDAC},{codecName:AAC},{codecName:SBC}]",
            )
            org.robolectric.util.ReflectionHelpers.setField(
                collectorI915,
                "cachedUsbDumpsysText",
                """
                port_manager={
                  usb_ports=[
                    {
                      port={
                        id=port0
                        supported_modes=dual
                      }
                      status={
                        connected=true
                        current_mode=dfp
                        power_role=source
                        data_role=host
                        usb_data_status=enabled
                      }
                      connected_at_millis=1000
                    }
                  ]
                }
                """.trimIndent(),
            )
            collectorI915.invalidateUsbCaches()
            com.droid.corewatch.data.AccelIoctlBridge.testDrmConnectorsProvider = {
                "eDP-1|1|1|10|300x190;DP-1|1|0|12|600x340"
            }
            val grantedSnap1 = collectorI915.sample(CaffeineConfig(), nowElapsedMs = 6_000L)
            assertTrue(grantedSnap1.displays.size >= 2)
            org.robolectric.shadows.ShadowDisplayManager.removeDisplay(extDisplayId)
            assertTrue(grantedSnap1.bluetooth.hasConnectPermission)
            assertEquals("LDAC", grantedSnap1.bluetooth.audioCodec.activeCodecName)
            assertTrue(grantedSnap1.bluetooth.peripherals.isNotEmpty())
            assertTrue(grantedSnap1.usbPeripherals.hasTypeCPorts)
            assertTrue(grantedSnap1.usbPeripherals.nodes.any { it.correlatedInfo.cameraSummary != null })
            assertTrue(grantedSnap1.usbPeripherals.nodes.any { it.correlatedInfo.storageVolumeSummary != null })
            assertTrue(grantedSnap1.usbPeripherals.nodes.any { it.correlatedInfo.audioCapabilitiesSummary != null })
            assertTrue(grantedSnap1.usbPeripherals.nodes.any { it.correlatedInfo.inputSourcesSummary != null })

            // Sample again within cache windows to cover A2DP codec cache hit, Wi-Fi capabilities cache hit, and USB cache hit
            val grantedSnap2 = collectorI915.sample(CaffeineConfig(), nowElapsedMs = 6_500L)
            assertEquals("LDAC", grantedSnap2.bluetooth.audioCodec.activeCodecName)

            // Cycle AudioManager modes and output devices (SCO, BLE, HDMI, Wired) +Xe GPU throttle reasons
            val collectorXe = com.droid.corewatch.data.TelemetryCollector(
                context = app,
                drmSysfsRoot = drmXe,
                accelSysfsRoot = accelRoot,
                usbSysfsRoot = usbRoot,
            )
            audioMgr.mode = android.media.AudioManager.MODE_IN_COMMUNICATION
            shadowAudio.setOutputDevices(listOf(scoOut))
            shadowApp.denyPermissions(android.Manifest.permission.DUMP)
            val scoSnap = collectorXe.sample(CaffeineConfig(), nowElapsedMs = 10_000L)
            assertTrue(scoSnap.bluetooth.audioCodec.communicationModeLabel.contains("VoIP"))
            assertTrue(scoSnap.gpu.isThrottled)

            audioMgr.mode = android.media.AudioManager.MODE_IN_CALL
            shadowAudio.setOutputDevices(listOf(bleOut))
            val bleSnap = collectorXe.sample(CaffeineConfig(), nowElapsedMs = 14_000L)
            assertEquals("Voice Call", bleSnap.bluetooth.audioCodec.communicationModeLabel)

            audioMgr.mode = android.media.AudioManager.MODE_RINGTONE
            shadowAudio.setOutputDevices(listOf(hdmiOut))
            val hdmiSnap = collectorXe.sample(CaffeineConfig(), nowElapsedMs = 18_000L)
            assertEquals("Ringtone", hdmiSnap.bluetooth.audioCodec.communicationModeLabel)

            audioMgr.mode = android.media.AudioManager.MODE_NORMAL
            shadowAudio.setOutputDevices(listOf(wiredOut))
            collectorXe.resetWindowCounters()
            collectorXe.invalidateGraphicsStatsCache()

            // 6. Exercise triggerActiveWifiScan states and TelephonyManager regulatory fallback
            val wm = app.getSystemService(android.net.wifi.WifiManager::class.java)
            val lm = app.getSystemService(android.location.LocationManager::class.java)
            val shadowLm = org.robolectric.Shadows.shadowOf(lm)
            @Suppress("DEPRECATION")
            wm.isWifiEnabled = false
            assertFalse(collectorXe.triggerActiveWifiScan().first)
            @Suppress("DEPRECATION")
            wm.isWifiEnabled = true
            shadowLm.setLocationEnabled(false)
            assertFalse(collectorXe.triggerActiveWifiScan().first)
            shadowLm.setLocationEnabled(true)
            shadowApp.denyPermissions(
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION,
            )
            assertFalse(collectorXe.triggerActiveWifiScan().first)
            shadowApp.grantPermissions(android.Manifest.permission.ACCESS_FINE_LOCATION)
            collectorXe.triggerActiveWifiScan()

            val tm = app.getSystemService(android.telephony.TelephonyManager::class.java)
            org.robolectric.Shadows.shadowOf(tm).apply {
                setNetworkCountryIso("gb")
                setSimCountryIso("jp")
            }
            collectorXe.invalidateWifiCaches()
            val gbSnap = collectorXe.sample(CaffeineConfig(), nowElapsedMs = 25_000L)
            assertEquals("GB", gbSnap.wifi.capabilities.countryCode)

            org.robolectric.Shadows.shadowOf(tm).setNetworkCountryIso("")
            collectorXe.invalidateWifiCaches()
            val jpSnap = collectorXe.sample(CaffeineConfig(), nowElapsedMs = 31_000L)
            assertEquals("JP", jpSnap.wifi.capabilities.countryCode)

            // 7. Exercise SDK 26 legacy fallback branches inside the loaded sandbox
            val savedSdk = android.os.Build.VERSION.SDK_INT
            try {
                org.robolectric.util.ReflectionHelpers.setStaticField(android.os.Build.VERSION::class.java, "SDK_INT", 26)
                collectorXe.invalidateWifiCaches()
                val sdk26Snap = collectorXe.sample(CaffeineConfig(), nowElapsedMs = 40_000L)
                assertTrue(sdk26Snap.osVersion.contains("26"))
            } finally {
                org.robolectric.util.ReflectionHelpers.setStaticField(android.os.Build.VERSION::class.java, "SDK_INT", savedSdk)
            }
        } finally {
            com.droid.corewatch.data.AccelIoctlBridge.testDrmConnectorsProvider = null
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun sysfsPermissionCacheRollingGpuActFreqThrottleEnumerationVsyncSysfsMtuAndA2dpGuard() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val shadowApp = org.robolectric.Shadows.shadowOf(app)
        val tempRoot = java.nio.file.Files.createTempDirectory("cw_issues_1_to_7").toFile()
        val lockedFiles = ArrayList<java.io.File>()
        try {
            // 1. Setup Xe GPU sysfs with unreadable gt1 media clocks & unreadable gtidle residency,
            //    dynamic throttle/reason_vr_tdc = 1, and rolling act_freq samples [0, 0, 300, 244] -> avg 136 MHz.
            val drmRoot = java.io.File(tempRoot, "drm").apply { mkdirs() }
            val xeFreq0 = java.io.File(drmRoot, "card0/device/tile0/gt0/freq0").apply { mkdirs() }
            val actFreqFile = java.io.File(xeFreq0, "act_freq").apply { writeText("0\n") }
            java.io.File(xeFreq0, "cur_freq").writeText("900\n")
            java.io.File(xeFreq0, "min_freq").writeText("850\n")
            java.io.File(xeFreq0, "rpe_freq").writeText("900\n")
            java.io.File(xeFreq0, "max_freq").writeText("2500\n")
            val missingRpaFile = java.io.File(xeFreq0, "rpa_freq") // Missing at poll 1..2, created at poll 3

            val xeThr = java.io.File(xeFreq0, "throttle").apply { mkdirs() }
            java.io.File(xeThr, "status").writeText("1\n")
            java.io.File(xeThr, "reason_vr_tdc").writeText("1\n")
            java.io.File(xeThr, "reason_ratl").writeText("1\n")

            val xeFreq1 = java.io.File(drmRoot, "card0/device/tile0/gt1/freq0").apply { mkdirs() }
            val mediaAct = java.io.File(xeFreq1, "act_freq").apply {
                writeText("500\n")
                setReadable(false, false)
                lockedFiles += this
            }
            val mediaCur = java.io.File(xeFreq1, "cur_freq").apply {
                writeText("500\n")
                setReadable(false, false)
                lockedFiles += this
            }

            val xeIdleDir = java.io.File(drmRoot, "card0/device/tile0/gt0/gtidle").apply { mkdirs() }
            val idleResFile = java.io.File(xeIdleDir, "idle_residency_ms").apply {
                writeText("4000\n")
                setReadable(false, false)
                lockedFiles += this
            }

            // 2. Setup NPU /dev/accel0 ioctl provider while locking sysfs npu_* counter files (EACCES)
            val accelRoot = java.io.File(tempRoot, "accel").apply { mkdirs() }
            val accelDev = java.io.File(accelRoot, "accel0/device").apply { mkdirs() }
            for (name in listOf("npu_busy_time_us", "npu_current_frequency_mhz", "npu_max_frequency_mhz")) {
                java.io.File(accelDev, name).apply {
                    writeText("1000\n")
                    setReadable(false, false)
                    lockedFiles += this
                }
            }
            com.droid.corewatch.data.AccelIoctlBridge.testDrmVersionProvider = { path ->
                if (path.contains("accel")) "intel_vpu|Driver for Intel NPU|1.0.0" else "xe|Intel Xe2 Graphics|1.1.0"
            }
            com.droid.corewatch.data.AccelIoctlBridge.testIvpuParamsProvider = { _, _ ->
                longArrayOf(0xB03EL, 7L, 2_000_000_000L, 64L, 0x00030025L, 12L, 3L, 0L)
            }

            // 3. Setup Network MTU: wlan0 has LinkProperties.mtu = 0 -> reads /sys/class/net/wlan0/mtu = 1420;
            //    eth1 has LinkProperties.mtu = 0 and no sysfs mtu file -> mtu = 0 ("—", never 1500).
            val netRoot = java.io.File(tempRoot, "net").apply { mkdirs() }
            java.io.File(netRoot, "wlan0").apply { mkdirs() }.let { java.io.File(it, "mtu").writeText("1420\n") }

            val cm = app.getSystemService(android.net.ConnectivityManager::class.java)
            val shadowCm = org.robolectric.Shadows.shadowOf(cm)
            shadowCm.clearAllNetworks()
            val wifiNet = org.robolectric.shadows.ShadowNetwork.newInstance(301)
            val wifiCaps = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance().also { caps ->
                val sc = org.robolectric.Shadows.shadowOf(caps)
                sc.addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                sc.addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                sc.addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }
            val wifiLp = android.net.LinkProperties().apply {
                interfaceName = "wlan0"
                mtu = 0
            }
            shadowCm.addNetwork(wifiNet, null)
            shadowCm.setNetworkCapabilities(wifiNet, wifiCaps)
            shadowCm.setLinkProperties(wifiNet, wifiLp)

            val ethNet = org.robolectric.shadows.ShadowNetwork.newInstance(302)
            val ethCaps = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance().also { caps ->
                val sc = org.robolectric.Shadows.shadowOf(caps)
                sc.addTransportType(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)
                sc.addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                sc.addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }
            val ethLp = android.net.LinkProperties().apply {
                interfaceName = "eth1"
                mtu = 0
            }
            shadowCm.addNetwork(ethNet, null)
            shadowCm.setNetworkCapabilities(ethNet, ethCaps)
            shadowCm.setLinkProperties(ethNet, ethLp)

            // 4. Setup Bluetooth A2DP proxy test double to verify Issue #7 (empty connectedDevices + SecurityException caching)
            shadowApp.grantPermissions(android.Manifest.permission.BLUETOOTH_CONNECT)
            val btMgr = app.getSystemService(android.bluetooth.BluetoothManager::class.java)
            org.robolectric.Shadows.shadowOf(btMgr.adapter).setEnabled(true)
            val audioMgr = app.getSystemService(android.media.AudioManager::class.java)
            val a2dpOut = org.robolectric.shadows.AudioDeviceInfoBuilder.newBuilder()
                .setType(android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
                .build()
            org.robolectric.Shadows.shadowOf(audioMgr).setOutputDevices(listOf(a2dpOut))

            var fakeConnectedA2dpDevices: List<android.bluetooth.BluetoothDevice> = emptyList()
            var getCodecStatusCalls = 0
            var throwSecurityOnCodecQuery = true
            val fakeA2dpAdapter = object : com.droid.corewatch.data.TelemetryCollector.A2dpProxyAdapter {
                override fun connectedDevices(): List<android.bluetooth.BluetoothDevice> = fakeConnectedA2dpDevices

                override fun queryCodecStatus(
                    device: android.bluetooth.BluetoothDevice,
                ): com.droid.corewatch.data.TelemetryCollector.CachedA2dpCodecInfo? {
                    getCodecStatusCalls++
                    if (throwSecurityOnCodecQuery) {
                        throw SecurityException("BLUETOOTH_PRIVILEGED required for getCodecStatus")
                    }
                    return com.droid.corewatch.data.TelemetryCollector.CachedA2dpCodecInfo(
                        codecType = 1, // AAC
                        sampleRate = 0x2, // 48.0 kHz
                        bitsPerSample = 0x1, // 16-bit
                        channelMode = 0x2, // Stereo
                        codecSpecific1 = 0L,
                        selectableSummary = "SBC, AAC",
                    )
                }
            }

            val openCounts = HashMap<String, Int>()
            val collector = com.droid.corewatch.data.TelemetryCollector(
                context = app,
                drmSysfsRoot = drmRoot,
                accelSysfsRoot = accelRoot,
                netSysfsRoot = netRoot,
            ).also { c ->
                c.fileOpenObserverForTesting = { p -> openCounts[p] = (openCounts[p] ?: 0) + 1 }
                c.displayVsyncRateExtractor = { null }
                c.displayArrSupportExtractor = { null }
                c.testA2dpProxyAdapter = fakeA2dpAdapter
            }
            org.robolectric.util.ReflectionHelpers.setField(
                collector,
                "cachedBtLogcatText",
                "mCodecConfig: {codecName:LDAC,mCodecType:4,mCodecPriority:1000,mSampleRate:0x4(96000),mBitsPerSample:0x4(24),mChannelMode:0x2(STEREO),mCodecSpecific1:1000}",
            )

            // Poll 1: act_freq = 0 (no connected A2DP device -> getCodecStatus never called with null)
            val s1 = collector.sample(CaffeineConfig(), nowElapsedMs = 1_000L)
            assertEquals("Idle (RC6)", s1.gpu.headlineFreqLabel)
            assertNull(s1.gpu.utilizationFraction)
            assertEquals("—", s1.gpu.headlineBusyLabel)
            assertFalse(s1.gpu.busyCounterReadable)
            assertFalse(s1.gpu.mediaEngineFreqReadable)
            assertEquals("RATL, VR_TDC", s1.gpu.throttleSummary)
            assertTrue(s1.npu.isPresent)
            assertFalse(s1.npu.sysfsCountersReadable)
            assertEquals(2000, s1.npu.maxFreqMhz)
            assertEquals(1420, s1.wifi.mtu)
            assertEquals("1420", s1.wifi.mtuLabel)
            assertEquals(0, s1.ethernet.mtu)
            assertEquals("—", s1.ethernet.mtuLabel)
            assertNull(s1.displays.first().vsyncRateHz)
            assertTrue(s1.displays.first().liveCadenceSummary.contains("VSync rate unavailable"))
            assertFalse(s1.displays.first().androidArrAvailable)

            // Poll 2: act_freq = 0
            collector.sample(CaffeineConfig(), nowElapsedMs = 2_000L)
            // Poll 3: act_freq = 300, and missing rpa_freq appears now (must still be retried & read!)
            actFreqFile.writeText("300\n")
            missingRpaFile.writeText("1800\n")
            val s3 = collector.sample(CaffeineConfig(), nowElapsedMs = 3_000L)
            assertEquals("300 MHz", s3.gpu.headlineFreqLabel)

            // Poll 4: act_freq = 244 -> rolling avg over [0, 0, 300, 244] = 544 / 4 = 136 MHz
            actFreqFile.writeText("244\n")
            val s4 = collector.sample(CaffeineConfig(), nowElapsedMs = 4_000L)
            assertEquals(136, s4.gpu.avgActualFreqMhz)
            assertEquals("Expected 0 getCodecStatus calls when connectedDevices is empty", 0, getCodecStatusCalls)

            // Verify every chmod 000 sysfs path was probed AT MOST ONCE across 4 polls, while missingRpaFile was retried!
            for (locked in lockedFiles) {
                assertTrue("Expected ${locked.path} cached unreadable", collector.isPathMarkedUnreadable(locked))
                assertEquals("Expected ${locked.name} opened once", 1, openCounts[locked.path])
            }
            assertFalse("Missing file must not be cached unreadable", collector.isPathMarkedUnreadable(missingRpaFile))
            assertEquals(4, openCounts[missingRpaFile.path])

            // Verify Issue #7: when A2DP sink connects & getCodecStatus throws SecurityException,
            // it is probed ONCE, cached across polls (using LDAC logcat fallback), and re-probed ONLY on ACTION_CONNECTION_STATE_CHANGED!
            val btSink = btMgr.adapter.getRemoteDevice("AA:BB:CC:11:22:33")
            fakeConnectedA2dpDevices = listOf(btSink)
            val btDenied1 = collector.sample(CaffeineConfig(), nowElapsedMs = 5_000L)
            val btDenied2 = collector.sample(CaffeineConfig(), nowElapsedMs = 6_000L)
            val btDenied3 = collector.sample(CaffeineConfig(), nowElapsedMs = 7_000L)
            assertEquals(1, getCodecStatusCalls)
            assertEquals("LDAC", btDenied1.bluetooth.audioCodec.activeCodecName)
            assertEquals("LDAC", btDenied2.bluetooth.audioCodec.activeCodecName)
            assertEquals("LDAC", btDenied3.bluetooth.audioCodec.activeCodecName)

            // Now simulate A2DP connection state change when getCodecStatus succeeds -> re-probes once and caches AAC!
            throwSecurityOnCodecQuery = false
            app.sendBroadcast(android.content.Intent(android.bluetooth.BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED))
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            val btAllowed1 = collector.sample(CaffeineConfig(), nowElapsedMs = 8_000L)
            val btAllowed2 = collector.sample(CaffeineConfig(), nowElapsedMs = 9_000L)
            assertEquals(2, getCodecStatusCalls)
            assertEquals("AAC", btAllowed1.bluetooth.audioCodec.activeCodecName)
            assertEquals("AAC", btAllowed2.bluetooth.audioCodec.activeCodecName)

            // 5. Render s4 in Compose Workbench + Mini-HUD + Display + Network tabs and verify all new UI hints!
            val vm = CorewatchViewModel(app)
            vm.setSnapshotForTesting(s4)
            composeRule.setContent {
                CorewatchTheme(dark = true) {
                    CorewatchAppRoot(vm = vm)
                }
            }
            composeRule.waitForIdle()
            composeRule.onNodeWithText("— · 244 MHz").performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithText("busy % unavailable on this build", substring = true).performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithText("Avg actual: 136 MHz · requested 900 MHz · floor 850 MHz", substring = true)
                .performScrollTo()
                .assertIsDisplayed()
            composeRule.onNodeWithText("Media engine clock: not readable on this build", substring = true)
                .performScrollTo()
                .assertIsDisplayed()
            composeRule.onNodeWithText("Throttle: RATL, VR_TDC", substring = true).performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithText("NPU counters: not readable on this build", substring = true)
                .performScrollTo()
                .assertIsDisplayed()

            // Switch to Display tab (Tab 4) -> verify VSync rate unavailable & Android ARR: Unavailable
            composeRule.onNodeWithTag("tab_3").performClick()
            composeRule.onNodeWithText("VSync rate unavailable", substring = true).performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithText("Android ARR: Unavailable", substring = true).performScrollTo().assertIsDisplayed()

            // Switch to Network tab (Tab 5) -> verify wlan0 MTU 1420 and eth1 MTU —
            composeRule.onNodeWithTag("tab_4").performClick()
            composeRule.onNodeWithText("Interface: wlan0 (MTU 1420)", substring = true).performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithText("Interface: eth1 (MTU —)", substring = true).performScrollTo().assertIsDisplayed()

            // Switch to Mini-HUD -> verify GPU & NPU primary & sub labels
            composeRule.onNodeWithTag("btn_mini_hud").performClick()
            composeRule.onNodeWithText("GPU — (244 MHz) · NPU —").assertIsDisplayed()
            composeRule.onNodeWithText("counters unavailable", substring = true).assertIsDisplayed()
            composeRule.onNodeWithTag("btn_expand_workbench").performClick()
            composeRule.onNodeWithTag("tab_0").performClick()

            // 6. Verify 120 Hz inter-frame GPU operating clock + /dev/accel0 METRIC_STREAMER NPU live clocks (idle -> active -> turbo -> idle)
            idleResFile.setReadable(true, false)
            idleResFile.writeText("4000\n")
            actFreqFile.writeText("0\n")
            var npuActiveCtx = 0L
            var npuDpuRaw = 0L
            var npuNceRaw = 0L
            com.droid.corewatch.data.AccelIoctlBridge.testIvpuParamsProvider = { _, _ ->
                longArrayOf(0xB03EL, 0L, 2_000_000_000L, 64L, 0x0003000AL, 50L, 3L, npuActiveCtx, npuDpuRaw, npuNceRaw, 1L)
            }
            val liveCollector = com.droid.corewatch.data.TelemetryCollector(
                context = app,
                drmSysfsRoot = drmRoot,
                accelSysfsRoot = accelRoot,
            )
            // Poll A (10s): idle NPU (0 ctx -> 0 MHz D3cold) + baseline GPU idle residency
            val liveIdle = liveCollector.sample(CaffeineConfig(), nowElapsedMs = 10_000L)
            assertEquals("Idle (RC6)", liveIdle.gpu.headlineFreqLabel)
            assertEquals("0% · Idle (D3cold)", liveIdle.npu.headlineLabel)
            vm.setSnapshotForTesting(liveIdle)
            composeRule.waitForIdle()
            composeRule.onNodeWithText("0% · Idle (D3cold)").performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithText("D3cold suspended (0 MHz)", substring = true).performScrollTo().assertIsDisplayed()

            // Poll B (11s): 4% GPU active residency (960 ms idle out of 1000 ms) with act_freq = 0, cur_freq = 900 -> "900 MHz"
            //               and NPU active (1 ctx, DPU 950 MHz, NCE 543 MHz) via heartbeatOnly update!
            idleResFile.writeText("4960\n")
            npuActiveCtx = 1L
            npuDpuRaw = 950_000L
            npuNceRaw = 542_857L
            val liveActive = liveCollector.sample(CaffeineConfig(), nowElapsedMs = 11_000L)
            assertEquals("4%", liveActive.gpu.headlineBusyLabel)
            assertEquals("900 MHz", liveActive.gpu.headlineFreqLabel)
            assertEquals("Active · 950 / 2000 MHz", liveActive.npu.headlineLabel)
            assertEquals(543, liveActive.npu.nceFreqMhz)
            vm.setSnapshotForTesting(liveActive)
            composeRule.waitForIdle()
            composeRule.onNodeWithText("4% · 900 MHz").performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithText("Active · 950 / 2000 MHz").performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithText("DPU: 950 MHz · NCE: 543 MHz", substring = true).performScrollTo().assertIsDisplayed()

            composeRule.onNodeWithTag("btn_mini_hud").performClick()
            composeRule.onNodeWithText("GPU 4% (900 MHz) · NPU 950 MHz").assertIsDisplayed()
            composeRule.onNodeWithText("NCE 543", substring = true).assertIsDisplayed()
        } finally {
            for (f in lockedFiles) {
                runCatching { f.setReadable(true, false) }
            }
            com.droid.corewatch.data.AccelIoctlBridge.testDrmVersionProvider = null
            com.droid.corewatch.data.AccelIoctlBridge.testIvpuParamsProvider = null
            tempRoot.deleteRecursively()
        }
    }
}
