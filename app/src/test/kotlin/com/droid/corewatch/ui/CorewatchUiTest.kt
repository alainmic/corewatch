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
import com.droid.corewatch.data.SystemTelemetrySnapshot
import com.droid.corewatch.data.ThermalLevel
import com.droid.corewatch.data.ThermalSensorReading
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
        composeRule.onNodeWithTag("wifi_link_card").assertIsDisplayed()
        composeRule.onNodeWithTag("bluetooth_card").assertIsDisplayed()
        composeRule.onNodeWithText("Network Throughput (TrafficStats RX / TX)").assertIsDisplayed()

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
        composeRule.onNodeWithText("Network Throughput (TrafficStats RX / TX)").assertIsDisplayed()
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
        composeRule.onNodeWithTag("tab_0").assertIsDisplayed().performClick()
        assertEquals(CorewatchTab.OverviewThermals, vm.state.value.activeTab)

        // At 560dp window width with desktop caption insets (usableCaptionWidth = 275dp < 465dp),
        // splitTwoRowHeader activates so all 5 tabs and pills remain visible and clickable on row 2
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

            com.droid.corewatch.data.AccelIoctlBridge.testDrmVersionProvider = { path ->
                if (path.contains("accel")) "intel_vpu|Driver for Intel NPU|1.0.0"
                else "xe|Intel Xe2 Graphics|1.1.0"
            }
            var mockHeartbeat = 10L
            com.droid.corewatch.data.AccelIoctlBridge.testIvpuParamsProvider = { _, _ ->
                longArrayOf(0xB03EL, 7L, 2_000_000_000L, 64L, 0x00030025L, mockHeartbeat, 1L)
            }

            val populatedCollector = com.droid.corewatch.data.TelemetryCollector(
                context = app,
                cpuSysfsRoot = cpuRoot,
                thermalSysfsRoot = thermalRoot,
                hwmonSysfsRoot = hwmonRoot,
                procMemInfoFile = memInfo,
                drmSysfsRoot = drmRoot,
                accelSysfsRoot = accelRoot,
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

        // 3. Retail Mini-HUD without SYSTEM_ALERT_WINDOW shows "Pin Floating PiP" action chip
        composeRule.onNodeWithTag("btn_mini_hud").performClick()
        composeRule.onNodeWithTag("btn_grant_overlay_pip").assertIsDisplayed().performClick()
        assertEquals(1, overlayRequestedCount)
    }
}
