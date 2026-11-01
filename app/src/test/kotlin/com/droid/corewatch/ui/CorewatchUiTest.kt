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

        // 4. Switch to Tab 4 (Display / Displays) and Tab 5 (Network & Bluetooth)
        assertEquals("Display", CorewatchTab.Displays.labelForDisplayCount(1, compact = false))
        assertEquals("Displays", CorewatchTab.Displays.labelForDisplayCount(2, compact = false))
        composeRule.onNodeWithTag("tab_3").performClick()
        assertEquals(CorewatchTab.Displays, vm.state.value.activeTab)
        composeRule.onNodeWithTag("frame_jank_card").assertIsDisplayed()

        composeRule.onNodeWithTag("tab_4").performClick()
        assertEquals(CorewatchTab.NetworkBluetooth, vm.state.value.activeTab)
        composeRule.onNodeWithTag("wifi_link_card").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("ethernet_link_card").assertDoesNotExist()
        composeRule.onNodeWithTag("network_throughput_card").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Network Throughput (TrafficStats RX / TX)").assertIsDisplayed()
        composeRule.onNodeWithTag("wifi_regulatory_card").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("surrounding_wifi_aps_card").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("bluetooth_card").performScrollTo().assertIsDisplayed()

        // 5. Copy Markdown Diagnostic Snapshot AND verify switching all 5 tabs works while the notification bar is visible
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
        assertEquals(CorewatchTab.NetworkBluetooth, vm.state.value.activeTab)
        composeRule.onNodeWithText("Network Throughput (TrafficStats RX / TX)").performScrollTo().assertIsDisplayed()
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
        // all 5 compact tabs ("CPU", "Battery", "Memory", "Display(s)", "Net & Bluetooth") must be visible and clickable
        composeRule.onNodeWithTag("btn_copy_markdown").performClick()
        assertTrue(vm.state.value.statusBanner!!.contains("Copied Markdown"))
        composeRule.onNodeWithTag("tab_1").assertIsDisplayed().performClick()
        assertEquals(CorewatchTab.BatteryPower, vm.state.value.activeTab)
        composeRule.onNodeWithTag("tab_2").assertIsDisplayed().performClick()
        assertEquals(CorewatchTab.MemoryStorage, vm.state.value.activeTab)
        composeRule.onNodeWithTag("tab_3").assertIsDisplayed().performClick()
        assertEquals(CorewatchTab.Displays, vm.state.value.activeTab)
        composeRule.onNodeWithTag("tab_4").assertIsDisplayed().performClick()
        assertEquals(CorewatchTab.NetworkBluetooth, vm.state.value.activeTab)
        composeRule.onNodeWithTag("tab_5").assertIsDisplayed().performClick()
        assertEquals(CorewatchTab.UsbPeripherals, vm.state.value.activeTab)
        composeRule.onNodeWithTag("tab_0").assertIsDisplayed().performClick()
        assertEquals(CorewatchTab.OverviewThermals, vm.state.value.activeTab)

        // At 560dp window width with desktop caption insets (usableCaptionWidth = 275dp < 465dp),
        // splitTwoRowHeader activates so all 6 tabs and pills remain visible and clickable on row 2
        composeRule.runOnUiThread {
            simulatedWidth.value = 560.dp
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("tab_1").assertIsDisplayed().performClick()
        assertEquals(CorewatchTab.BatteryPower, vm.state.value.activeTab)
        composeRule.onNodeWithTag("tab_3").assertIsDisplayed().performClick()
        assertEquals(CorewatchTab.Displays, vm.state.value.activeTab)
        composeRule.onNodeWithTag("tab_4").assertIsDisplayed().performClick()
        assertEquals(CorewatchTab.NetworkBluetooth, vm.state.value.activeTab)
        composeRule.onNodeWithTag("tab_5").performScrollTo().assertIsDisplayed().performClick()
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
        assertEquals(CorewatchTab.NetworkBluetooth, vm.state.value.activeTab)

        root.performKeyInput {
            withKeyDown(Key.CtrlLeft) { pressKey(Key.Six) }
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
            assertEquals(0.25f, snap2.gpu.utilizationFraction, 0.01f)
            assertEquals(0.40f, snap2.npu.utilizationFraction, 0.01f)
            assertTrue(snap1.thermalSensors.any { it.celsius > 50f })
            assertTrue(snap2.memory.totalBytes > 0L)
            assertTrue(snap2.ethernet.isConnected)
            assertEquals("eth1", snap2.ethernet.interfaceName)
            assertEquals(9000, snap2.ethernet.mtu)
            assertTrue(snap2.wifi.isConnected)
            assertEquals("wlan0", snap2.wifi.interfaceName)

            // Now test with missing directories so all fallback branches execute
            val missingDir = java.io.File(tempRoot, "nonexistent")
            val fallbackCollector = com.droid.corewatch.data.TelemetryCollector(
                context = app,
                cpuSysfsRoot = missingDir,
                thermalSysfsRoot = missingDir,
                hwmonSysfsRoot = missingDir,
                procMemInfoFile = java.io.File(missingDir, "meminfo"),
            )
            val fallbackSnap = fallbackCollector.sample(CaffeineConfig(), nowElapsedMs = 3_000L)
            assertTrue(fallbackSnap.cpuCores.isNotEmpty())
            assertEquals(2, fallbackSnap.thermalSensors.size)
            assertTrue(fallbackSnap.memory.totalBytes > 0L)
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

        // 1. Retail ungranted DUMP state on Tab 5 shows honest locked card + Copy ADB Command chip
        composeRule.onNodeWithTag("tab_4").performClick()
        composeRule.onNodeWithText("Per-Profile Stack Drop Counters Locked (A2DP · HFP/SCO · LE Audio)")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag("copy_bt_dump_adb_btn").performScrollTo().performClick()
        assertTrue(vm.state.value.statusBanner!!.contains("ADB DUMP"))
        vm.dismissBanner()

        // 2. Once DUMP is granted (hasStackCounters = true), live-switches to the 3-column counter grid
        vm.setSnapshotForTesting(
            vm.state.value.snapshot.copy(
                bluetooth = BluetoothTelemetrySnapshot(
                    isAdapterEnabled = true,
                    hasConnectPermission = true,
                    connectionStateLabel = "Connected (1 active)",
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

        // 3. Undocked has no Wired Ethernet card; docking dynamically reveals Wired Ethernet beside Wi-Fi 7 (Standby)
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
                    internalDeviceCount = 1,
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
        composeRule.onNodeWithTag("tab_5").performClick()
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

        // Select Dell Dock (1-3) -> jump to Displays; then return & select 2.5G NIC (2-4.1) -> jump to Network & Bluetooth
        composeRule.onNodeWithTag("usb_node_1-3").performScrollTo().performClick()
        composeRule.onNodeWithTag("usb_node_details_card").performScrollTo()
        composeRule.onNodeWithTag("usb_jump_displays_tab_btn").performClick()
        assertEquals(CorewatchTab.Displays, vm.state.value.activeTab)

        composeRule.onNodeWithTag("tab_5").performClick()
        composeRule.onNodeWithTag("usb_node_2-4.1").performScrollTo().performClick()
        composeRule.onNodeWithTag("usb_node_details_card").performScrollTo()
        composeRule.onNodeWithTag("usb_jump_network_tab_btn").performClick()
        assertEquals(CorewatchTab.NetworkBluetooth, vm.state.value.activeTab)

        // 5. Granted DUMP state renders live Type-C port tiles
        composeRule.onNodeWithTag("tab_5").performClick()
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
    }
}
