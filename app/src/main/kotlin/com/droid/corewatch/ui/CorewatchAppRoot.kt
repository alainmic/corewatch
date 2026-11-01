// Copyright 2026 The Corewatch Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.corewatch.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Cable
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Coffee
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Mouse
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PhotoSizeSelectSmall
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.SettingsEthernet
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import com.droid.corewatch.data.BandChannelSummary
import com.droid.corewatch.data.CaffeineDuration
import com.droid.corewatch.data.CoreCluster
import com.droid.corewatch.data.DiagnosticReportBuilder
import com.droid.corewatch.data.DisplaySpec
import com.droid.corewatch.data.EthernetTelemetrySnapshot
import com.droid.corewatch.data.SurroundingWifiAp
import com.droid.corewatch.data.ThermalLevel
import com.droid.corewatch.data.TypeCPortStatus
import com.droid.corewatch.data.UsbCategoryFilter
import com.droid.corewatch.data.UsbHealthStatus
import com.droid.corewatch.data.UsbHotplugAction
import com.droid.corewatch.data.UsbHotplugEvent
import com.droid.corewatch.data.UsbNodeCategory
import com.droid.corewatch.data.UsbTopologyNode
import com.droid.corewatch.data.WifiApBandFilter
import com.droid.corewatch.data.WifiApSortOrder
import com.droid.corewatch.data.WifiRadioCapabilities
import com.droid.corewatch.data.WifiScanTelemetrySnapshot
import com.droid.corewatch.data.WifiTelemetrySnapshot
import com.droid.corewatch.ui.theme.CorewatchMonoTypography
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

internal data class CaptionMetrics(val height: Dp, val start: Dp, val end: Dp)

internal fun computeCaptionMetrics(
    density: Density,
    layoutDir: LayoutDirection,
    capTopPx: Int,
    statusTopPx: Int,
    widthPx: Int,
    boundingRects: List<android.graphics.Rect>,
): CaptionMetrics {
    var leftPx = 0
    var rightPx = 0
    if (capTopPx > 0) {
        val maxLeftWidthPx = with(density) { 104.dp.roundToPx() }
        val minRightWidthPx = with(density) { 154.dp.roundToPx() }
        val maxRightWidthPx = with(density) { 200.dp.roundToPx() }
        val edgeAnchorTolerancePx = with(density) { 24.dp.roundToPx() }

        for (r in boundingRects) {
            if (r.isEmpty) continue
            if (r.left <= edgeAnchorTolerancePx) {
                leftPx = maxOf(leftPx, r.right.coerceAtMost(maxLeftWidthPx))
            } else {
                val distFromRight = if (widthPx > 0 && r.right >= widthPx - edgeAnchorTolerancePx && r.right <= widthPx + edgeAnchorTolerancePx) {
                    widthPx - r.left
                } else {
                    r.width()
                }
                rightPx = maxOf(rightPx, distFromRight.coerceIn(minRightWidthPx, maxRightWidthPx))
            }
        }
        if (leftPx == 0) {
            leftPx = with(density) { 76.dp.roundToPx() }
        }
        if (rightPx < minRightWidthPx) {
            rightPx = minRightWidthPx
        }
    }
    val startPx = if (layoutDir == LayoutDirection.Rtl) rightPx else leftPx
    val endPx = if (layoutDir == LayoutDirection.Rtl) leftPx else rightPx
    val minCaptionH = (44f * density.fontScale.coerceIn(1f, 1.35f)).dp
    return with(density) {
        CaptionMetrics(
            height = max(minCaptionH, maxOf(capTopPx, statusTopPx).toDp()),
            start = startPx.toDp(),
            end = endPx.toDp(),
        )
    }
}

@Composable
private fun rememberCaptionMetrics(): CaptionMetrics {
    val density = LocalDensity.current
    val view = LocalView.current
    val layoutDir = LocalLayoutDirection.current
    val capTop = WindowInsets.captionBar.getTop(density)
    val statusTop = WindowInsets.statusBars.getTop(density)
    val widthPx = LocalWindowInfo.current.containerSize.width
    val rects = if (Build.VERSION.SDK_INT >= 35 && capTop > 0) {
        view.rootWindowInsets?.getBoundingRects(android.view.WindowInsets.Type.captionBar()).orEmpty()
    } else {
        emptyList()
    }
    return computeCaptionMetrics(
        density = density,
        layoutDir = layoutDir,
        capTopPx = capTop,
        statusTopPx = statusTop,
        widthPx = widthPx,
        boundingRects = rects,
    )
}

@Composable
fun CorewatchAppRoot(
    vm: CorewatchViewModel,
    canDrawOverlays: Boolean = true,
    onRequestOverlayPermission: (() -> Unit)? = null,
    onRequestWifiScanPermission: (() -> Unit)? = null,
    onRequestNearbyWifiPermission: (() -> Unit)? = null,
    onOpenLocationSettings: (() -> Unit)? = null,
    onResizeWindowForMode: (Boolean) -> Unit = {},
) {
    val state by vm.state.collectAsState()
    val focusRequester = remember { FocusRequester() }
    var textInputFocused by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }
    LaunchedEffect(state.isMiniHud) {
        onResizeWindowForMode(state.isMiniHud)
    }
    LaunchedEffect(state.statusBanner) {
        if (state.statusBanner != null) {
            delay(3_500L)
            vm.dismissBanner()
        }
    }

    val cs = MaterialTheme.colorScheme
    val cap = rememberCaptionMetrics()
    val bgAlpha = if (state.isMiniHud) state.miniHudAlpha else 1.0f
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(cs.background.copy(alpha = bgAlpha))
            .focusRequester(focusRequester)
            .focusTarget()
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    runCatching { focusRequester.requestFocus() }
                }
            }
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when {
                    ev.isCtrlPressed && ev.isShiftPressed && ev.key == Key.C -> {
                        vm.copyDiagnosticMarkdown(); true
                    }
                    ev.isCtrlPressed && ev.isShiftPressed && ev.key == Key.S -> {
                        vm.triggerWifiScan(); true
                    }
                    ev.isCtrlPressed && ev.isShiftPressed && ev.key == Key.B -> {
                        vm.toggleMaskApBssids(); true
                    }
                    ev.isCtrlPressed && ev.isShiftPressed && ev.key == Key.I -> {
                        vm.toggleShowInternalUsbDevices(); true
                    }
                    ev.isCtrlPressed && ev.key == Key.M -> {
                        vm.toggleMiniHud(); true
                    }
                    ev.isCtrlPressed && ev.key == Key.K -> {
                        vm.toggleCaffeineQuick(); true
                    }
                    ev.isCtrlPressed && ev.key == Key.R -> {
                        vm.resetSessionHistory(); true
                    }
                    ev.isCtrlPressed && ev.key == Key.One -> {
                        vm.selectTab(CorewatchTab.OverviewThermals); true
                    }
                    ev.isCtrlPressed && ev.key == Key.Two -> {
                        vm.selectTab(CorewatchTab.BatteryPower); true
                    }
                    ev.isCtrlPressed && ev.key == Key.Three -> {
                        vm.selectTab(CorewatchTab.MemoryStorage); true
                    }
                    ev.isCtrlPressed && ev.key == Key.Four -> {
                        vm.selectTab(CorewatchTab.Displays); true
                    }
                    ev.isCtrlPressed && ev.key == Key.Five -> {
                        vm.selectTab(CorewatchTab.Network); true
                    }
                    ev.isCtrlPressed && ev.key == Key.Six -> {
                        vm.selectTab(CorewatchTab.Bluetooth); true
                    }
                    ev.isCtrlPressed && ev.key == Key.Seven -> {
                        vm.selectTab(CorewatchTab.UsbPeripherals); true
                    }
                    !textInputFocused && !ev.isCtrlPressed && ev.key == Key.Spacebar -> {
                        vm.togglePaused(); true
                    }
                    !ev.isCtrlPressed && ev.key == Key.F5 -> {
                        vm.resetSessionHistory(); true
                    }
                    !ev.isCtrlPressed && ev.key == Key.F1 -> {
                        vm.setShortcutsDialogVisible(!state.showShortcutsDialog); true
                    }
                    else -> false
                }
            },
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val isNarrowWindow = maxWidth < 480.dp
            LaunchedEffect(isNarrowWindow) {
                vm.onWindowNarrowChanged(isNarrowWindow)
            }
            if (state.isMiniHud) {
                MiniHudView(
                    state = state,
                    vm = vm,
                    cap = cap,
                    showTitleInCaption = cap.start == 0.dp || maxWidth >= 420.dp,
                    canDrawOverlays = canDrawOverlays,
                    onRequestOverlayPermission = onRequestOverlayPermission,
                )
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    CorewatchTitleBar(state = state, vm = vm, cap = cap)
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        when (state.activeTab) {
                            CorewatchTab.OverviewThermals -> OverviewThermalsTab(state = state, vm = vm)
                            CorewatchTab.BatteryPower -> BatteryPowerTab(state = state, vm = vm)
                            CorewatchTab.MemoryStorage -> MemoryStorageTab(state = state)
                            CorewatchTab.Displays -> DisplaysTab(state = state)
                            CorewatchTab.Network -> NetworkTab(
                                state = state,
                                vm = vm,
                                onRequestWifiScanPermission = onRequestWifiScanPermission,
                                onRequestNearbyWifiPermission = onRequestNearbyWifiPermission,
                                onOpenLocationSettings = onOpenLocationSettings,
                            )
                            CorewatchTab.Bluetooth -> BluetoothTab(
                                state = state,
                                vm = vm,
                            )
                            CorewatchTab.UsbPeripherals -> UsbPeripheralsTab(
                                state = state,
                                vm = vm,
                                onSearchFocusChanged = { textInputFocused = it },
                            )
                        }
                    }
                }
            }
            state.statusBanner?.let { banner ->
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    StatusBannerRow(message = banner, onDismiss = vm::dismissBanner)
                }
            }
        }
    }

    if (state.showShortcutsDialog) {
        ShortcutsDialog(onDismiss = { vm.setShortcutsDialogVisible(false) })
    }
}

@Composable
internal fun CorewatchTitleBar(state: CorewatchUiState, vm: CorewatchViewModel, cap: CaptionMetrics) {
    val cs = MaterialTheme.colorScheme
    val snap = state.snapshot
    val caffeineActive = snap.caffeineEvaluation.isActive
    val density = LocalDensity.current
    val hasDesktopCaption = cap.start > 0.dp || cap.end > 0.dp
    val statusTopDp = if (!hasDesktopCaption) {
        with(density) { WindowInsets.statusBars.getTop(density).toDp() }
    } else {
        0.dp
    }
    val startPad = maxOf(cap.start, 10.dp)
    val endPad = maxOf(cap.end + 8.dp, 12.dp)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .background(cs.surfaceContainerLow)
            .padding(top = statusTopDp),
    ) {
        val totalWidth = maxWidth
        val usableCaptionWidth = (totalWidth - startPad - endPad).coerceAtLeast(0.dp)
        // Separate interactive controls below the OS caption drag region whenever an OS caption bar is present
        // or when the caption width is narrow, preventing Embedded{Caption} pointer-stealing and minimize-button overlap.
        val splitTwoRowHeader = hasDesktopCaption || usableCaptionWidth < 520.dp
        val showBrandText = usableCaptionWidth >= 560.dp || cap.start == 0.dp || splitTwoRowHeader
        val useFullTabTitles = if (splitTwoRowHeader) totalWidth >= 980.dp else usableCaptionWidth >= 1000.dp
        val useUltraCompactTabTitles = if (splitTwoRowHeader) totalWidth < 780.dp else usableCaptionWidth < 800.dp
        val showWattagePill = usableCaptionWidth >= 880.dp || splitTwoRowHeader
        val showThermalPill = usableCaptionWidth >= 420.dp || splitTwoRowHeader
        val showCaffeineLabel = if (splitTwoRowHeader) totalWidth >= 720.dp else usableCaptionWidth >= 780.dp
        val btnSize = if (totalWidth >= 540.dp) 28.dp else 25.dp

        Column(modifier = Modifier.fillMaxWidth()) {
            // Row 1: Inside the OS desktop caption bar (Brand + Live Hardware Telemetry Status Pills on the left)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = cap.height)
                    .padding(
                        start = startPad,
                        end = endPad,
                        top = 4.dp,
                        bottom = 4.dp,
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = if (!splitTwoRowHeader) Modifier.weight(1f, fill = false) else Modifier,
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(if (useFullTabTitles) 6.dp else 3.dp),
                ) {
                    if (showBrandText) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(end = 4.dp),
                        ) {
                            if (cap.start == 0.dp) {
                                Icon(Icons.Outlined.Speed, contentDescription = null, tint = cs.primary, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                            }
                            Text("Corewatch", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = cs.onSurface)
                        }
                    }

                    if (splitTwoRowHeader) {
                        ThermalPill(
                            celsius = snap.primaryCpuTempCelsius,
                            useFahrenheit = state.useFahrenheit,
                            thermalLevel = snap.thermalLevel,
                            onToggleUnit = vm::toggleTemperatureUnit,
                        )
                        if (usableCaptionWidth >= 220.dp) {
                            WattagePill(
                                levelPercent = snap.battery.levelPercent,
                                watts = snap.battery.watts,
                                isCharging = snap.battery.isCharging,
                            )
                        }
                        if (usableCaptionWidth >= 340.dp) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = cs.surfaceContainerHigh,
                            ) {
                                Text(
                                    text = String.format(
                                        Locale.US,
                                        "CPU %d%% · %d MHz",
                                        (snap.avgCpuUtilizationFraction * 100f).roundToInt(),
                                        snap.maxCpuFreqMhz,
                                    ),
                                    style = CorewatchMonoTypography.hudSmall,
                                    color = cs.primary,
                                    maxLines = 1,
                                    softWrap = false,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                                )
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(if (useFullTabTitles) 4.dp else 2.dp),
                        ) {
                            CorewatchTabStrip(
                                activeTab = state.activeTab,
                                displayCount = snap.displays.size,
                                useFullTabTitles = useFullTabTitles,
                                useUltraCompactTabTitles = useUltraCompactTabTitles,
                                onSelectTab = vm::selectTab,
                            )
                        }
                    }
                }

                if (!splitTwoRowHeader) {
                    Spacer(Modifier.width(6.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(if (usableCaptionWidth >= 560.dp) 4.dp else 2.dp),
                    ) {
                        if (showThermalPill) {
                            ThermalPill(
                                celsius = snap.primaryCpuTempCelsius,
                                useFahrenheit = state.useFahrenheit,
                                thermalLevel = snap.thermalLevel,
                                onToggleUnit = vm::toggleTemperatureUnit,
                            )
                        }
                        if (showWattagePill) {
                            WattagePill(
                                levelPercent = snap.battery.levelPercent,
                                watts = snap.battery.watts,
                                isCharging = snap.battery.isCharging,
                            )
                        }
                        CorewatchActionToolbarButtons(
                            state = state,
                            vm = vm,
                            caffeineActive = caffeineActive,
                            showCaffeineLabel = showCaffeineLabel,
                            btnSize = btnSize,
                        )
                    }
                }
            }

            // Row 2: Full-width Interactive Tab Strip + Action Toolbar below the OS caption bar
            // (100% immune to Embedded{Caption} pointer-stealing and never overlaps OS window buttons)
            if (splitTwoRowHeader) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(if (useFullTabTitles) 4.dp else 2.dp),
                    ) {
                        CorewatchTabStrip(
                            activeTab = state.activeTab,
                            displayCount = snap.displays.size,
                            useFullTabTitles = useFullTabTitles,
                            useUltraCompactTabTitles = useUltraCompactTabTitles,
                            onSelectTab = vm::selectTab,
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(if (totalWidth >= 600.dp) 4.dp else 2.dp),
                    ) {
                        if (usableCaptionWidth < 220.dp) {
                            WattagePill(
                                levelPercent = snap.battery.levelPercent,
                                watts = snap.battery.watts,
                                isCharging = snap.battery.isCharging,
                            )
                        }
                        CorewatchActionToolbarButtons(
                            state = state,
                            vm = vm,
                            caffeineActive = caffeineActive,
                            showCaffeineLabel = showCaffeineLabel,
                            btnSize = btnSize,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CorewatchActionToolbarButtons(
    state: CorewatchUiState,
    vm: CorewatchViewModel,
    caffeineActive: Boolean,
    showCaffeineLabel: Boolean,
    btnSize: Dp,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        onClick = vm::toggleCaffeineQuick,
        shape = RoundedCornerShape(6.dp),
        color = if (caffeineActive) cs.secondaryContainer else cs.surfaceContainerHigh,
        modifier = Modifier
            .testTag("btn_caffeine_quick")
            .semantics { contentDescription = "Toggle Caffeine Keep-Awake" },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = if (showCaffeineLabel) 8.dp else 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Coffee,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = if (caffeineActive) cs.onSecondaryContainer else cs.onSurfaceVariant,
            )
            if (showCaffeineLabel) {
                Spacer(Modifier.width(4.dp))
                Text(
                    text = if (caffeineActive) "Awake" else "Caffeine",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (caffeineActive) cs.onSecondaryContainer else cs.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }

    Surface(
        onClick = vm::cycleSamplingInterval,
        shape = RoundedCornerShape(6.dp),
        color = cs.surfaceContainerHigh,
        modifier = Modifier
            .testTag("btn_sampling_rate")
            .semantics { contentDescription = "Cycle Sampling Interval (${state.samplingInterval.label})" },
    ) {
        Text(
            text = state.samplingInterval.label,
            style = CorewatchMonoTypography.hudSmall,
            color = cs.primary,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
        )
    }

    IconButton(
        onClick = vm::togglePaused,
        modifier = Modifier.size(btnSize).testTag("btn_pause_resume"),
    ) {
        Icon(
            if (state.isPaused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
            contentDescription = if (state.isPaused) "Resume telemetry" else "Pause telemetry",
            modifier = Modifier.size(16.dp),
        )
    }

    IconButton(
        onClick = { vm.setMiniHud(true) },
        modifier = Modifier.size(btnSize).testTag("btn_mini_hud"),
    ) {
        Icon(
            Icons.Outlined.PhotoSizeSelectSmall,
            contentDescription = "Switch to Compact Mini-HUD (Ctrl+M)",
            modifier = Modifier.size(16.dp),
        )
    }

    IconButton(
        onClick = { vm.copyDiagnosticMarkdown() },
        modifier = Modifier.size(btnSize).testTag("btn_copy_markdown"),
    ) {
        Icon(
            Icons.Outlined.ContentCopy,
            contentDescription = "Copy Markdown Diagnostic Snapshot (Ctrl+Shift+C)",
            modifier = Modifier.size(16.dp),
        )
    }

    IconButton(
        onClick = vm::resetSessionHistory,
        modifier = Modifier.size(btnSize).testTag("btn_reset_history"),
    ) {
        Icon(
            Icons.Outlined.Refresh,
            contentDescription = "Reset session telemetry counters (F5)",
            modifier = Modifier.size(16.dp),
        )
    }

    IconButton(
        onClick = vm::cycleTheme,
        modifier = Modifier.size(btnSize).testTag("btn_theme"),
    ) {
        Icon(
            Icons.Outlined.DarkMode,
            contentDescription = "Cycle Theme (${state.themeMode.label})",
            modifier = Modifier.size(16.dp),
        )
    }

    IconButton(
        onClick = { vm.setShortcutsDialogVisible(true) },
        modifier = Modifier.size(btnSize).testTag("btn_shortcuts"),
    ) {
        Icon(
            Icons.AutoMirrored.Outlined.HelpOutline,
            contentDescription = "Keyboard Shortcuts (F1)",
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun CorewatchTabStrip(
    activeTab: CorewatchTab,
    displayCount: Int,
    useFullTabTitles: Boolean,
    useUltraCompactTabTitles: Boolean = false,
    onSelectTab: (CorewatchTab) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    CorewatchTab.entries.forEach { tab ->
        val selected = activeTab == tab
        Surface(
            onClick = { onSelectTab(tab) },
            shape = RoundedCornerShape(8.dp),
            color = if (selected) cs.primaryContainer else Color.Transparent,
            modifier = Modifier.testTag("tab_${tab.index}"),
        ) {
            Text(
                text = tab.labelForDisplayCount(
                    displayCount = displayCount,
                    compact = !useFullTabTitles,
                    ultraCompact = useUltraCompactTabTitles,
                ),
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) cs.onPrimaryContainer else cs.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.padding(
                    horizontal = when {
                        useFullTabTitles -> 9.dp
                        useUltraCompactTabTitles -> 5.dp
                        else -> 7.dp
                    },
                    vertical = 5.dp,
                ),
            )
        }
    }
}

@Composable
private fun ThermalPill(
    celsius: Float,
    useFahrenheit: Boolean,
    thermalLevel: ThermalLevel,
    onToggleUnit: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val tempText = formatTemp(celsius, useFahrenheit)
    Surface(
        onClick = onToggleUnit,
        shape = RoundedCornerShape(6.dp),
        color = cs.surfaceContainerHigh,
        modifier = Modifier.testTag("pill_thermal"),
    ) {
        Text(
            text = tempText,
            style = CorewatchMonoTypography.hudSmall,
            color = thermalColor(thermalLevel),
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun WattagePill(
    levelPercent: Int,
    watts: Float,
    isCharging: Boolean,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = cs.surfaceContainerHigh,
        modifier = Modifier.testTag("pill_wattage"),
    ) {
        Text(
            text = String.format(Locale.US, "%d%% %+.1fW", levelPercent, watts),
            style = CorewatchMonoTypography.hudSmall,
            color = if (isCharging) cs.secondary else cs.tertiary,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun StatusBannerRow(message: String, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = cs.secondaryContainer,
        tonalElevation = 4.dp,
        shadowElevation = 4.dp,
        modifier = Modifier
            .widthIn(max = 560.dp)
            .testTag("status_banner"),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSecondaryContainer,
                modifier = Modifier.weight(1f, fill = false),
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = "Dismiss notification",
                    tint = cs.onSecondaryContainer,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

// ------------------------------------------------------------------------ Compact Mini-HUD Mode (320dp x 420dp)

@Composable
internal fun PipOverlayRoot(
    vm: CorewatchViewModel,
    onExpandToFull: () -> Unit,
    onDragOverlay: (Float, Float) -> Unit,
) {
    val state by vm.state.collectAsState()
    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    val dark = when (state.themeMode) {
        com.droid.corewatch.ui.theme.ThemeMode.System -> systemDark
        com.droid.corewatch.ui.theme.ThemeMode.Light -> false
        com.droid.corewatch.ui.theme.ThemeMode.Dark -> true
    }
    com.droid.corewatch.ui.theme.CorewatchTheme(dark = dark) {
        val cs = MaterialTheme.colorScheme
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(14.dp))
                .background(cs.background.copy(alpha = state.miniHudAlpha))
                .border(1.dp, cs.primary.copy(alpha = 0.45f), RoundedCornerShape(14.dp)),
        ) {
            MiniHudView(
                state = state,
                vm = vm,
                onExpandToFull = onExpandToFull,
                onDragOverlay = onDragOverlay,
            )
        }
    }
}

@Composable
internal fun MiniHudView(
    state: CorewatchUiState,
    vm: CorewatchViewModel,
    cap: CaptionMetrics = CaptionMetrics(0.dp, 0.dp, 0.dp),
    showTitleInCaption: Boolean = true,
    canDrawOverlays: Boolean = true,
    onRequestOverlayPermission: (() -> Unit)? = null,
    onExpandToFull: () -> Unit = { vm.setMiniHud(false) },
    onDragOverlay: ((Float, Float) -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val snap = state.snapshot
    val hist = state.history
    val caffeineActive = snap.caffeineEvaluation.isActive
    val density = LocalDensity.current
    val isFloatingPip = onDragOverlay != null
    val hasDesktopCaption = cap.start > 0.dp || cap.end > 0.dp
    val statusTopDp = if (!hasDesktopCaption && !isFloatingPip) {
        with(density) { WindowInsets.statusBars.getTop(density).toDp() }
    } else {
        0.dp
    }
    val cardAlpha = state.miniHudAlpha

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = statusTopDp)
            .testTag("mini_hud_root"),
    ) {
        if (hasDesktopCaption) {
            // Top OS window caption strip (keeps title clear of left/right OS window controls)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = cap.height)
                    .background(cs.surfaceContainerLow.copy(alpha = cardAlpha))
                    .padding(
                        start = maxOf(cap.start, 8.dp),
                        end = maxOf(cap.end + 8.dp, 8.dp),
                        top = 2.dp,
                        bottom = 2.dp,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showTitleInCaption) {
                    Text(
                        text = "Corewatch Mini-HUD",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        val containerAlignment = if (isFloatingPip) Alignment.TopCenter else Alignment.Center
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(if (isFloatingPip) 0.dp else 12.dp),
            contentAlignment = containerAlignment,
        ) {
            val hudColumnModifier = if (isFloatingPip) {
                Modifier.fillMaxSize()
            } else {
                Modifier
                    .widthIn(max = 440.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(cs.surfaceContainerLow.copy(alpha = cardAlpha))
                    .border(1.dp, cs.primary.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            }

            Column(modifier = hudColumnModifier) {
                // Interactive Mini-HUD Control Bar (draggable when in floating PiP overlay)
                val dragModifier = if (onDragOverlay != null) {
                    Modifier.pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            onDragOverlay(dragAmount.x, dragAmount.y)
                        }
                    }
                } else {
                    Modifier
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(cs.surfaceContainerLow.copy(alpha = cardAlpha))
                        .then(dragModifier)
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (!hasDesktopCaption && showTitleInCaption) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Outlined.Speed, contentDescription = null, tint = cs.primary, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "Corewatch Mini-HUD",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    } else {
                        Text(
                            text = if (isFloatingPip) "PiP HUD" else "Compact HUD",
                            style = CorewatchMonoTypography.hudSmall,
                            color = cs.primary,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    Surface(
                        onClick = vm::cycleMiniHudOpacity,
                        shape = RoundedCornerShape(6.dp),
                        color = cs.surfaceContainerHigh,
                        modifier = Modifier.testTag("mini_hud_opacity"),
                    ) {
                        Text(
                            text = "${(state.miniHudAlpha * 100f).roundToInt()}%",
                            style = CorewatchMonoTypography.hudSmall,
                            color = cs.onSurfaceVariant,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                        )
                    }

                    Surface(
                        onClick = vm::toggleCaffeineQuick,
                        shape = RoundedCornerShape(6.dp),
                        color = if (caffeineActive) cs.secondaryContainer else cs.surfaceContainerHigh,
                        modifier = Modifier.testTag("mini_hud_caffeine"),
                    ) {
                        Text(
                            text = if (caffeineActive) "☕ Awake" else "☕ Off",
                            style = CorewatchMonoTypography.hudSmall,
                            color = if (caffeineActive) cs.onSecondaryContainer else cs.onSurfaceVariant,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                        )
                    }

                    IconButton(
                        onClick = { vm.copyDiagnosticMarkdown() },
                        modifier = Modifier.size(24.dp).testTag("mini_hud_copy"),
                    ) {
                        Icon(
                            Icons.Outlined.ContentCopy,
                            contentDescription = "Copy Markdown Snapshot (Ctrl+Shift+C)",
                            modifier = Modifier.size(14.dp),
                        )
                    }

                    Surface(
                        onClick = onExpandToFull,
                        shape = RoundedCornerShape(6.dp),
                        color = cs.primaryContainer,
                        modifier = Modifier
                            .testTag("btn_expand_workbench")
                            .semantics { contentDescription = "Expand to Full Workbench (Ctrl+M)" },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Outlined.OpenInFull,
                                contentDescription = null,
                                tint = cs.onPrimaryContainer,
                                modifier = Modifier.size(13.dp),
                            )
                            Spacer(Modifier.width(3.dp))
                            Text(
                                text = "Full",
                                style = MaterialTheme.typography.labelSmall,
                                color = cs.onPrimaryContainer,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
                }

                if (!isFloatingPip && !canDrawOverlays && onRequestOverlayPermission != null) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = cs.surfaceContainerHigh,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = "Enable 'Display over other apps' to detach Mini-HUD as an Always-On-Top PiP overlay.",
                                style = CorewatchMonoTypography.hudSmall,
                                color = cs.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            Surface(
                                onClick = onRequestOverlayPermission,
                                shape = RoundedCornerShape(6.dp),
                                color = cs.secondaryContainer,
                                modifier = Modifier.testTag("btn_grant_overlay_pip"),
                            ) {
                                Text(
                                    text = "Pin Floating PiP",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = cs.onSecondaryContainer,
                                    maxLines = 1,
                                    softWrap = false,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // 1. CPU Load & Peak Frequency Sparkline Row
                    MiniHudSparklineCard(
                        title = "CPU (${snap.cpuCores.size} cores)",
                        primaryValue = String.format(Locale.US, "%d%% · %d MHz", (snap.avgCpuUtilizationFraction * 100f).roundToInt(), snap.maxCpuFreqMhz),
                        subValue = String.format(Locale.US, "Avg %.0f%% · Peak %.0f%%", hist.cpuLoadAvg, hist.cpuLoadPeak),
                        samples = hist.cpuLoadSamples,
                        lineColor = cs.primary,
                        maxCeiling = 100f,
                        cardAlpha = cardAlpha,
                    )

                    // 2. GPU & NPU Hardware Accelerators Sparkline Row
                    MiniHudSparklineCard(
                        title = "GPU & NPU",
                        primaryValue = String.format(
                            Locale.US,
                            "GPU %d%% (%d MHz) · NPU %d%%",
                            (snap.gpu.utilizationFraction * 100f).roundToInt(),
                            snap.gpu.displayFreqMhz,
                            (snap.npu.utilizationFraction * 100f).roundToInt(),
                        ),
                        subValue = String.format(
                            Locale.US,
                            "GPU %s · NPU %s (%d/%d MHz)",
                            snap.gpu.scalingStateLabel,
                            snap.npu.scalingStateLabel,
                            snap.npu.currentFreqMhz,
                            snap.npu.maxFreqMhz,
                        ),
                        samples = hist.gpuLoadSamples,
                        lineColor = cs.tertiary,
                        maxCeiling = 100f,
                        cardAlpha = cardAlpha,
                    )

                    // 3. Thermal Status & Headroom Sparkline Row
                    MiniHudSparklineCard(
                        title = "THERMAL (${snap.thermalLevel.label})",
                        primaryValue = formatTemp(snap.primaryCpuTempCelsius, state.useFahrenheit),
                        subValue = String.format(Locale.US, "Headroom %.2f · Peak %s", snap.thermalHeadroom, formatTemp(hist.thermalPeakCelsius, state.useFahrenheit)),
                        samples = hist.thermalCelsiusSamples,
                        lineColor = thermalColor(snap.thermalLevel),
                        minFloor = 20f,
                        maxCeiling = 100f,
                        cardAlpha = cardAlpha,
                    )

                    // 4. Battery Wattage & Time Remaining Sparkline Row
                    MiniHudSparklineCard(
                        title = "BATTERY (${snap.battery.levelPercent}% · ${snap.battery.powerSource.label})",
                        primaryValue = String.format(Locale.US, "%+.1f W", snap.battery.watts),
                        subValue = String.format(Locale.US, "%d mV · Peak %.1f W", snap.battery.voltageMv, hist.batteryPeakWatts),
                        samples = hist.batteryWattsSamples,
                        lineColor = if (snap.battery.isCharging) cs.secondary else cs.tertiary,
                        maxCeiling = 65f,
                        cardAlpha = cardAlpha,
                    )

                    // 5. Memory / zram Swap + Active Network Throughput Row
                    val activeNetPrefix = when {
                        snap.ethernet.isPrimaryDefaultRoute -> "Eth"
                        snap.wifi.isPrimaryDefaultRoute -> "Wi-Fi"
                        else -> "Net"
                    }
                    MiniHudSparklineCard(
                        title = "RAM & zram SWAP",
                        primaryValue = String.format(
                            Locale.US,
                            "%s / %s (%d%%)",
                            DiagnosticReportBuilder.formatGiB(snap.memory.usedBytes),
                            DiagnosticReportBuilder.formatGiB(snap.memory.totalBytes),
                            (snap.memory.usedFraction * 100f).roundToInt(),
                        ),
                        subValue = String.format(
                            Locale.US,
                            "Swap %d%% · %s ↓%.1f ↑%.1f Mbps",
                            (snap.memory.swapUsedFraction * 100f).roundToInt(),
                            activeNetPrefix,
                            snap.network.rxMbps,
                            snap.network.txMbps,
                        ),
                        samples = hist.memoryUsedPctSamples,
                        lineColor = cs.secondary,
                        maxCeiling = 100f,
                        cardAlpha = cardAlpha,
                    )
                }
            }
        }
    }
}

@Composable
private fun MiniHudSparklineCard(
    title: String,
    primaryValue: String,
    subValue: String,
    samples: List<Float>,
    lineColor: Color,
    minFloor: Float = 0f,
    maxCeiling: Float? = null,
    cardAlpha: Float = 0.88f,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = cs.surfaceContainer.copy(alpha = cardAlpha),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(end = 6.dp),
                )
                Text(
                    text = primaryValue,
                    style = CorewatchMonoTypography.hudValue,
                    color = cs.onSurface,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            Spacer(Modifier.height(1.dp))
            SparklineCanvas(
                samples = samples,
                lineColor = lineColor,
                gridColor = cs.outlineVariant.copy(alpha = 0.35f),
                minFloor = minFloor,
                maxCeiling = maxCeiling,
                modifier = Modifier.fillMaxWidth().height(18.dp),
            )
            Spacer(Modifier.height(1.dp))
            Text(
                text = subValue,
                style = CorewatchMonoTypography.hudSmall,
                color = cs.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ------------------------------------------------------------------------ Tab 1: Overview & Thermals

@Composable
private fun OverviewThermalsTab(state: CorewatchUiState, vm: CorewatchViewModel) {
    val cs = MaterialTheme.colorScheme
    val snap = state.snapshot
    val hist = state.history

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Top Summary Cards: Thermal Headroom + CPU Utilization
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = cs.surfaceContainer,
                modifier = Modifier.weight(1f),
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Thermostat, contentDescription = null, tint = thermalColor(snap.thermalLevel), modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Thermal Status & Headroom", style = MaterialTheme.typography.titleMedium)
                        }
                        Text(
                            text = formatTemp(snap.primaryCpuTempCelsius, state.useFahrenheit),
                            style = CorewatchMonoTypography.metricLarge,
                            color = thermalColor(snap.thermalLevel),
                        )
                    }
                    Text(
                        text = "State: ${snap.thermalLevel.label} · Predictive Headroom: ${String.format(Locale.US, "%.2f", snap.thermalHeadroom)} (1.00 = Severe Throttling)",
                        style = CorewatchMonoTypography.hudSmall,
                        color = cs.onSurfaceVariant,
                    )
                    LinearProgressIndicator(
                        progress = { (snap.thermalHeadroom / 1.2f).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                        color = thermalColor(snap.thermalLevel),
                        trackColor = cs.surfaceContainerHighest,
                    )
                    SparklineCanvas(
                        samples = hist.thermalCelsiusSamples,
                        lineColor = thermalColor(snap.thermalLevel),
                        gridColor = cs.outlineVariant.copy(alpha = 0.4f),
                        minFloor = 20f,
                        maxCeiling = 100f,
                        modifier = Modifier.fillMaxWidth().height(64.dp),
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = cs.surfaceContainer,
                modifier = Modifier.weight(1f),
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Memory, contentDescription = null, tint = cs.primary, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("CPU Frequency Scaling (60s)", style = MaterialTheme.typography.titleMedium)
                        }
                        Text(
                            text = String.format(Locale.US, "%d%% · %d MHz", (snap.avgCpuUtilizationFraction * 100f).roundToInt(), snap.maxCpuFreqMhz),
                            style = CorewatchMonoTypography.metricLarge,
                            color = cs.primary,
                        )
                    }
                    Text(
                        text = String.format(
                            Locale.US,
                            "%s · %s (%d Cores) · Avg %.1f%% · Peak %.1f%%",
                            snap.cpuModelName,
                            snap.cpuAbi,
                            snap.cpuCores.size,
                            hist.cpuLoadAvg,
                            hist.cpuLoadPeak,
                        ),
                        style = CorewatchMonoTypography.hudSmall,
                        color = cs.onSurfaceVariant,
                    )
                    LinearProgressIndicator(
                        progress = { snap.avgCpuUtilizationFraction.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                        color = cs.primary,
                        trackColor = cs.surfaceContainerHighest,
                    )
                    SparklineCanvas(
                        samples = hist.cpuLoadSamples,
                        lineColor = cs.primary,
                        gridColor = cs.outlineVariant.copy(alpha = 0.4f),
                        minFloor = 0f,
                        maxCeiling = 100f,
                        modifier = Modifier.fillMaxWidth().height(64.dp),
                    )
                }
            }
        }

        // Per-Core P-Core / E-Core Frequency Grid
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = cs.surfaceContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Per-Core CPU Frequencies (Performance vs. Efficiency Clusters)", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "Governor: ${snap.cpuCores.firstOrNull()?.governor ?: "schedutil"}",
                        style = CorewatchMonoTypography.hudSmall,
                        color = cs.onSurfaceVariant,
                    )
                }
                for (core in snap.cpuCores) {
                    val stateLabel = core.scalingStateLabel
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = String.format(Locale.US, "CPU%-2d [%s]", core.coreIndex, core.cluster.label),
                            style = CorewatchMonoTypography.hudValue,
                            modifier = Modifier.width(125.dp),
                            color = if (core.cluster == CoreCluster.Performance) cs.primary else cs.secondary,
                        )
                        LinearProgressIndicator(
                            progress = { core.utilizationFraction },
                            modifier = Modifier.weight(1f).height(8.dp).clip(CircleShape),
                            color = if (stateLabel == "turbo") cs.tertiary else if (core.cluster == CoreCluster.Performance) cs.primary else cs.secondary,
                            trackColor = cs.surfaceContainerHighest,
                        )
                        Text(
                            text = String.format(
                                Locale.US,
                                "%4d / %4d MHz (%3d%% · %-9s)",
                                core.curMhz,
                                core.maxMhz,
                                (core.utilizationFraction * 100f).roundToInt(),
                                stateLabel,
                            ),
                            style = CorewatchMonoTypography.hudSmall,
                            color = if (stateLabel == "turbo") cs.tertiary else cs.onSurfaceVariant,
                            modifier = Modifier.widthIn(min = 215.dp),
                        )
                    }
                }
            }
        }

        // GPU & NPU Hardware Accelerators (60s)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = cs.surfaceContainer,
                modifier = Modifier.weight(1f).testTag("gpu_accelerator_card"),
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Speed, contentDescription = null, tint = cs.secondary, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("GPU Accelerator (60s)", style = MaterialTheme.typography.titleMedium)
                        }
                        Text(
                            text = String.format(
                                Locale.US,
                                "%d%% · %d MHz",
                                (snap.gpu.utilizationFraction * 100f).roundToInt(),
                                snap.gpu.displayFreqMhz,
                            ),
                            style = CorewatchMonoTypography.metricLarge,
                            color = cs.secondary,
                        )
                    }
                    Text(
                        text = "${snap.gpu.deviceName} (${snap.gpu.driverName}) · State: ${snap.gpu.scalingStateLabel} · Profile: ${snap.gpu.powerProfile}",
                        style = CorewatchMonoTypography.hudSmall,
                        color = cs.onSurface,
                    )
                    val mediaSuffix = snap.gpu.mediaEngineFreqMhz?.let { " · Media GT1: $it MHz" } ?: ""
                    Text(
                        text = String.format(
                            Locale.US,
                            "Clock: %d MHz (Slice: %d MHz) · Range: %d–%d MHz (RPe %d MHz)%s · Throttle: %s",
                            snap.gpu.requestedFreqMhz,
                            snap.gpu.actualFreqMhz,
                            snap.gpu.minFreqMhz,
                            snap.gpu.maxFreqMhz,
                            snap.gpu.efficientFreqMhz,
                            mediaSuffix,
                            snap.gpu.throttleSummary,
                        ),
                        style = CorewatchMonoTypography.hudSmall,
                        color = if (snap.gpu.isThrottled) cs.error else cs.onSurfaceVariant,
                    )
                    LinearProgressIndicator(
                        progress = { snap.gpu.utilizationFraction.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                        color = if (snap.gpu.isThrottled) cs.error else cs.secondary,
                        trackColor = cs.surfaceContainerHighest,
                    )
                    SparklineCanvas(
                        samples = hist.gpuLoadSamples,
                        lineColor = cs.secondary,
                        gridColor = cs.outlineVariant.copy(alpha = 0.4f),
                        minFloor = 0f,
                        maxCeiling = 100f,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = cs.surfaceContainer,
                modifier = Modifier.weight(1f).testTag("npu_accelerator_card"),
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Memory, contentDescription = null, tint = cs.tertiary, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("NPU Accelerator (60s)", style = MaterialTheme.typography.titleMedium)
                        }
                        Text(
                            text = String.format(
                                Locale.US,
                                "%d%% · %d / %d MHz",
                                (snap.npu.utilizationFraction * 100f).roundToInt(),
                                snap.npu.currentFreqMhz,
                                snap.npu.maxFreqMhz,
                            ),
                            style = CorewatchMonoTypography.metricLarge,
                            color = cs.tertiary,
                        )
                    }
                    Text(
                        text = "${snap.npu.deviceName} (${snap.npu.driverName} ${snap.npu.firmwareVersion}) · State: ${snap.npu.scalingStateLabel}",
                        style = CorewatchMonoTypography.hudSmall,
                        color = cs.onSurface,
                    )
                    Text(
                        text = String.format(
                            Locale.US,
                            "Active Contexts: %d / %d · Max DPU Clock: %d MHz · Peak: %.1f%%",
                            snap.npu.activeContexts,
                            snap.npu.maxContexts,
                            snap.npu.maxFreqMhz,
                            hist.npuLoadPeak,
                        ),
                        style = CorewatchMonoTypography.hudSmall,
                        color = cs.onSurfaceVariant,
                    )
                    LinearProgressIndicator(
                        progress = { snap.npu.utilizationFraction.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                        color = cs.tertiary,
                        trackColor = cs.surfaceContainerHighest,
                    )
                    SparklineCanvas(
                        samples = hist.npuLoadSamples,
                        lineColor = cs.tertiary,
                        gridColor = cs.outlineVariant.copy(alpha = 0.4f),
                        minFloor = 0f,
                        maxCeiling = 100f,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                    )
                }
            }
        }

        // Thermal Sensors Table
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = cs.surfaceContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Thermal Zones & Hardware Sensors", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = vm::toggleTemperatureUnit) {
                        Text(if (state.useFahrenheit) "Switch to °C" else "Switch to °F")
                    }
                }
                for (sensor in snap.thermalSensors) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("${sensor.name} (${sensor.source})", style = CorewatchMonoTypography.hudSmall, color = cs.onSurface)
                        Text(formatTemp(sensor.celsius, state.useFahrenheit), style = CorewatchMonoTypography.hudValue, color = cs.primary)
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------------ Tab 2: Battery & Power (+ Caffeine)

@Composable
private fun BatteryPowerTab(state: CorewatchUiState, vm: CorewatchViewModel) {
    val cs = MaterialTheme.colorScheme
    val snap = state.snapshot
    val batt = snap.battery
    val hist = state.history
    val cfg = state.caffeineConfig
    val eval = snap.caffeineEvaluation

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Live Wattage & USB-C PD Profiler Card
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = cs.surfaceContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (batt.isCharging) Icons.Outlined.BatteryChargingFull else Icons.Outlined.Bolt,
                            contentDescription = null,
                            tint = if (batt.isCharging) cs.secondary else cs.tertiary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Live Battery Power Flow (W = V × I)", style = MaterialTheme.typography.titleMedium)
                    }
                    Text(
                        text = String.format(Locale.US, "%+.1f W", batt.watts),
                        style = CorewatchMonoTypography.metricLarge,
                        color = if (batt.isCharging) cs.secondary else cs.tertiary,
                    )
                }
                Text(
                    text = batt.statusSummary,
                    style = CorewatchMonoTypography.hudValue,
                    color = cs.onSurface,
                )
                Text(
                    text = String.format(
                        Locale.US,
                        "Charge: %d%% · Voltage: %d mV · Current: %+.0f mA · Temp: %s · Cycles: %d · Health: %s · Session Peak: %.1f W (Avg %.1f W)",
                        batt.levelPercent,
                        batt.voltageMv,
                        batt.currentMa,
                        formatTemp(batt.temperatureCelsius, state.useFahrenheit),
                        batt.cycleCount,
                        batt.healthLabel,
                        hist.batteryPeakWatts,
                        hist.batteryAvgWatts,
                    ),
                    style = CorewatchMonoTypography.hudSmall,
                    color = cs.onSurfaceVariant,
                )
                SparklineCanvas(
                    samples = hist.batteryWattsSamples,
                    lineColor = if (batt.isCharging) cs.secondary else cs.tertiary,
                    gridColor = cs.outlineVariant.copy(alpha = 0.4f),
                    minFloor = 0f,
                    maxCeiling = 65f,
                    modifier = Modifier.fillMaxWidth().height(80.dp),
                )
            }
        }

        // "Caffeine" Keep-Awake Controller Card
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = cs.surfaceContainer,
            modifier = Modifier.fillMaxWidth().testTag("caffeine_card"),
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Coffee, contentDescription = null, tint = if (eval.isActive) cs.secondary else cs.onSurfaceVariant)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("“Caffeine” Keep-Awake Controller (Ctrl+K)", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Prevents the Googlebook from dimming or sleeping during long builds, transfers, or docked sessions.",
                                style = MaterialTheme.typography.bodySmall,
                                color = cs.onSurfaceVariant,
                            )
                        }
                    }
                    Text(
                        text = eval.activeReason,
                        style = CorewatchMonoTypography.hudValue,
                        color = if (eval.isActive) cs.secondary else cs.onSurfaceVariant,
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CaffeineDuration.entries.forEach { dur ->
                        FilterChip(
                            selected = cfg.duration == dur,
                            onClick = { vm.setCaffeineDuration(dur) },
                            label = { Text(dur.label) },
                            modifier = Modifier.testTag("caffeine_dur_${dur.name}"),
                        )
                    }
                }

                HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.4f))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { vm.setCaffeineAutoRules(!cfg.autoOnAcCharging, cfg.autoOnExternalDisplay) }
                        .testTag("caffeine_auto_ac"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = cfg.autoOnAcCharging,
                        onCheckedChange = { vm.setCaffeineAutoRules(it, cfg.autoOnExternalDisplay) },
                    )
                    Text("Automatically keep awake while charging on AC / USB-C PD", style = MaterialTheme.typography.bodyMedium)
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { vm.setCaffeineAutoRules(cfg.autoOnAcCharging, !cfg.autoOnExternalDisplay) }
                        .testTag("caffeine_auto_ext"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = cfg.autoOnExternalDisplay,
                        onCheckedChange = { vm.setCaffeineAutoRules(cfg.autoOnAcCharging, it) },
                    )
                    Text("Automatically keep awake while an external monitor is connected", style = MaterialTheme.typography.bodyMedium)
                }

                Text(
                    text = "Safety Cutoff: Keep-Awake automatically pauses if discharging below ${cfg.lowBatteryCutoffPercent}% battery.",
                    style = CorewatchMonoTypography.hudSmall,
                    color = cs.onSurfaceVariant,
                )
            }
        }
    }
}

// ------------------------------------------------------------------------ Tab 3: Memory & Storage

@Composable
private fun MemoryStorageTab(state: CorewatchUiState) {
    val cs = MaterialTheme.colorScheme
    val mem = state.snapshot.memory
    val storage = state.snapshot.storage
    val hist = state.history

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = cs.surfaceContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Physical RAM (/proc/meminfo)", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = String.format(
                            Locale.US,
                            "%s / %s (%d%%)",
                            DiagnosticReportBuilder.formatGiB(mem.usedBytes),
                            DiagnosticReportBuilder.formatGiB(mem.totalBytes),
                            (mem.usedFraction * 100f).roundToInt(),
                        ),
                        style = CorewatchMonoTypography.metricLarge,
                        color = cs.primary,
                    )
                }
                LinearProgressIndicator(
                    progress = { mem.usedFraction },
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
                    color = cs.primary,
                    trackColor = cs.surfaceContainerHighest,
                )
                Text(
                    text = String.format(
                        Locale.US,
                        "Available: %s · Cached: %s · Buffers: %s · Free: %s · Dirty: %s",
                        DiagnosticReportBuilder.formatGiB(mem.availableBytes),
                        DiagnosticReportBuilder.formatGiB(mem.cachedBytes),
                        DiagnosticReportBuilder.formatGiB(mem.buffersBytes),
                        DiagnosticReportBuilder.formatGiB(mem.freeBytes),
                        DiagnosticReportBuilder.formatGiB(mem.dirtyBytes),
                    ),
                    style = CorewatchMonoTypography.hudSmall,
                    color = cs.onSurfaceVariant,
                )
                Text(
                    text = String.format(
                        Locale.US,
                        "LMKD / AMS Low Threshold: %s · LowMem Flag: %s · Memory Trim State: %s",
                        DiagnosticReportBuilder.formatGiB(mem.amsThresholdBytes),
                        if (mem.isLowMemory) "LOW MEMORY!" else "Healthy",
                        state.snapshot.lmkd.trimLevelLabel,
                    ),
                    style = CorewatchMonoTypography.hudSmall,
                    color = if (mem.isLowMemory) cs.error else cs.onSurfaceVariant,
                )
                SparklineCanvas(
                    samples = hist.memoryUsedPctSamples,
                    lineColor = cs.primary,
                    gridColor = cs.outlineVariant.copy(alpha = 0.4f),
                    minFloor = 0f,
                    maxCeiling = 100f,
                    modifier = Modifier.fillMaxWidth().height(72.dp),
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = cs.surfaceContainer,
                modifier = Modifier.weight(1f),
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("zram / Swap Pressure", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = String.format(
                                Locale.US,
                                "%s / %s (%d%%)",
                                DiagnosticReportBuilder.formatGiB(mem.swapUsedBytes),
                                DiagnosticReportBuilder.formatGiB(mem.swapTotalBytes),
                                (mem.swapUsedFraction * 100f).roundToInt(),
                            ),
                            style = CorewatchMonoTypography.hudValue,
                            color = cs.secondary,
                        )
                    }
                    LinearProgressIndicator(
                        progress = { mem.swapUsedFraction },
                        modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
                        color = cs.secondary,
                        trackColor = cs.surfaceContainerHighest,
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = cs.surfaceContainer,
                modifier = Modifier.weight(1f),
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Internal User Storage (/data)", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = String.format(
                                Locale.US,
                                "%s / %s (%s free)",
                                DiagnosticReportBuilder.formatGiB(storage.usedBytes),
                                DiagnosticReportBuilder.formatGiB(storage.totalBytes),
                                DiagnosticReportBuilder.formatGiB(storage.freeBytes),
                            ),
                            style = CorewatchMonoTypography.hudValue,
                            color = cs.onSurface,
                        )
                    }
                    LinearProgressIndicator(
                        progress = { storage.usedFraction },
                        modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
                        color = cs.tertiary,
                        trackColor = cs.surfaceContainerHighest,
                    )
                }
            }
        }

        // LMKD (Low Memory Killer Daemon) & PSI Memory Pressure Monitor (5m)
        val lmkd = state.snapshot.lmkd
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = cs.surfaceContainer,
            modifier = Modifier.fillMaxWidth().testTag("lmkd_monitor_card"),
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("LMKD Invocations & PSI Pressure (Last 5 Mins)", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = String.format(
                                Locale.US,
                                "Rolling 5-minute LMKD kill count · Trim Level: %s · PSI Memory (10s): some %.2f%% / full %.2f%%",
                                lmkd.trimLevelLabel,
                                lmkd.psiMemorySomeAvg10,
                                lmkd.psiMemoryFullAvg10,
                            ),
                            style = CorewatchMonoTypography.hudSmall,
                            color = cs.onSurfaceVariant,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = cs.surfaceContainerHigh,
                        ) {
                            Text(
                                text = lmkd.scopeBadgeLabel,
                                style = CorewatchMonoTypography.hudSmall,
                                color = cs.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                        Text(
                            text = "${lmkd.killCount5m} invocations",
                            style = CorewatchMonoTypography.metricLarge,
                            color = if (lmkd.killCount5m > 0) cs.error else cs.secondary,
                        )
                    }
                }

                if (lmkd.recentKills5m.isEmpty()) {
                    Text(
                        text = "✓ Zero LMKD kills recorded in the last 5 minutes.",
                        style = CorewatchMonoTypography.hudValue,
                        color = cs.secondary,
                    )
                } else {
                    for (kill in lmkd.recentKills5m) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "${kill.processName} (PID ${kill.pid}) · ${kill.reason}",
                                style = CorewatchMonoTypography.hudSmall,
                                color = cs.error,
                            )
                            Text(
                                text = kill.formattedPssMb,
                                style = CorewatchMonoTypography.hudValue,
                                color = cs.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------------ Tab 4: Display / Displays

@Composable
private fun DisplaysTab(state: CorewatchUiState) {
    val cs = MaterialTheme.colorScheme
    val snap = state.snapshot
    val hist = state.history

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val displayCount = snap.displays.size
        val headerLabel = if (displayCount == 1) {
            val sub = if (snap.externalDisplayCount > 0) "1 external" else "1 built-in"
            "Connected Display (1 total · $sub)"
        } else {
            "Connected Displays ($displayCount total · ${snap.externalDisplayCount} external)"
        }
        Text(
            text = headerLabel,
            style = MaterialTheme.typography.titleMedium,
        )

        for (disp in snap.displays) {
            DisplayCard(disp = disp)
        }

        // Dropped & Janky Frames (Rolling 5-Min Window, VRR-Aware)
        val jank = snap.frameJank
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = cs.surfaceContainer,
            modifier = Modifier.fillMaxWidth().testTag("frame_jank_card"),
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Dropped & Janky Frames (Last 5 Mins · VRR-Aware)", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "Window: ${jank.windowElapsedLabel} · ${jank.evaluatedFrames5m} evaluated frames",
                            style = CorewatchMonoTypography.hudSmall,
                            color = cs.onSurfaceVariant,
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = cs.surfaceContainerHigh,
                    ) {
                        Text(
                            text = jank.scopeBadgeLabel,
                            style = CorewatchMonoTypography.hudSmall,
                            color = cs.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = cs.surfaceContainerHigh,
                        modifier = Modifier.weight(1f),
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("DROPPED FRAMES (5M)", style = CorewatchMonoTypography.hudSmall, color = cs.onSurfaceVariant)
                            Text(
                                text = String.format(Locale.US, "%.2f%%", jank.droppedPercent5m),
                                style = CorewatchMonoTypography.metricLarge,
                                color = if (jank.droppedFrames5m > 0) cs.error else cs.secondary,
                            )
                            Text(
                                text = "${jank.droppedFrames5m} dropped frames (5m)",
                                style = CorewatchMonoTypography.hudSmall,
                                color = cs.onSurfaceVariant,
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = cs.surfaceContainerHigh,
                        modifier = Modifier.weight(1f),
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("JANKY FRAMES (5M)", style = CorewatchMonoTypography.hudSmall, color = cs.onSurfaceVariant)
                            Text(
                                text = String.format(Locale.US, "%.2f%%", jank.jankyPercent5m),
                                style = CorewatchMonoTypography.metricLarge,
                                color = if (jank.jankyPercent5m >= 5f) cs.error else cs.primary,
                            )
                            Text(
                                text = "${jank.jankyFrames5m} janky frames (5m)",
                                style = CorewatchMonoTypography.hudSmall,
                                color = cs.onSurfaceVariant,
                            )
                        }
                    }
                }

                SparklineCanvas(
                    samples = hist.jankPercentSamples,
                    lineColor = cs.tertiary,
                    gridColor = cs.outlineVariant.copy(alpha = 0.4f),
                    minFloor = 0f,
                    maxCeiling = 100f,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                )

                Text(
                    text = String.format(
                        Locale.US,
                        "Jank Causes (5m): Long CPU: %d · Long GPU: %d · Buffer Stuffing: %d · Missed VSync: %d",
                        jank.longCpuJank5m,
                        jank.longGpuJank5m,
                        jank.bufferStuffing5m,
                        jank.sfMissedFrames5m,
                    ),
                    style = CorewatchMonoTypography.hudSmall,
                    color = cs.onSurfaceVariant,
                )
                Text(
                    text = "VRR Deadline Accounting: Per-frame dynamic FrameMetrics.DEADLINE ${jank.deadlineRangeLabel}",
                    style = CorewatchMonoTypography.hudSmall,
                    color = cs.primary,
                )
            }
        }
    }
}

// ------------------------------------------------------------------------ Tab 5: Network (Wi-Fi & Ethernet)

@Composable
private fun NetworkTab(
    state: CorewatchUiState,
    vm: CorewatchViewModel,
    onRequestWifiScanPermission: (() -> Unit)? = null,
    onRequestNearbyWifiPermission: (() -> Unit)? = null,
    onOpenLocationSettings: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val snap = state.snapshot
    val hist = state.history
    val wifi = snap.wifi
    val eth = snap.ethernet

    BoxWithConstraints(modifier = Modifier.fillMaxSize().testTag("network_tab")) {
        val sideBySideLinks = maxWidth >= 760.dp
        val threeColumnBands = maxWidth >= 680.dp
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (eth.isConnected && sideBySideLinks) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    WifiRadioLinkCard(
                        wifi = wifi,
                        rssiSamples = hist.wifiSignalSamples,
                        modifier = Modifier.weight(1f),
                    )
                    WiredEthernetLinkCard(
                        eth = eth,
                        ethMbpsSamples = hist.ethernetMbpsSamples,
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                WifiRadioLinkCard(
                    wifi = wifi,
                    rssiSamples = hist.wifiSignalSamples,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (eth.isConnected) {
                    WiredEthernetLinkCard(
                        eth = eth,
                        ethMbpsSamples = hist.ethernetMbpsSamples,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // Aggregate Network Throughput (TrafficStats RX / TX) Card across all active interfaces + VPN
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = cs.surfaceContainer,
                modifier = Modifier.fillMaxWidth().testTag("network_throughput_card"),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1.3f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Network Throughput (TrafficStats RX / TX)", style = MaterialTheme.typography.titleMedium)
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (snap.activeDefaultRouteSummary != "Disconnected") cs.secondaryContainer else cs.surfaceContainerHigh,
                            ) {
                                Text(
                                    text = "Default Route: ${snap.activeDefaultRouteSummary}",
                                    style = CorewatchMonoTypography.hudSmall,
                                    color = if (snap.activeDefaultRouteSummary != "Disconnected") cs.onSecondaryContainer else cs.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = String.format(Locale.US, "↓ %.2f Mbps · ↑ %.2f Mbps", snap.network.rxMbps, snap.network.txMbps),
                                style = CorewatchMonoTypography.metricLarge,
                                color = cs.primary,
                            )
                            Text(
                                text = String.format(
                                    Locale.US,
                                    "Boot RX: %s · TX: %s",
                                    DiagnosticReportBuilder.formatGiB(snap.network.totalRxBytes),
                                    DiagnosticReportBuilder.formatGiB(snap.network.totalTxBytes),
                                ),
                                style = CorewatchMonoTypography.hudSmall,
                                color = cs.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = String.format(
                                Locale.US,
                                "Aggregate Packet Rate (All Interfaces): ↓ %.0f pkt/s · ↑ %.0f pkt/s (%,d RX / %,d TX pkts)",
                                snap.network.rxPacketsPerSec,
                                snap.network.txPacketsPerSec,
                                snap.network.totalRxPackets,
                                snap.network.totalTxPackets,
                            ),
                            style = CorewatchMonoTypography.hudSmall,
                            color = cs.onSurfaceVariant,
                        )
                    }
                    Column(
                        modifier = Modifier.weight(0.7f),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(
                            text = "60s Total RX + TX (Mbps)",
                            style = CorewatchMonoTypography.hudSmall,
                            color = cs.onSurfaceVariant,
                        )
                        SparklineCanvas(
                            samples = hist.networkMbpsSamples,
                            lineColor = cs.primary,
                            gridColor = cs.outlineVariant.copy(alpha = 0.4f),
                            minFloor = 0f,
                            modifier = Modifier.fillMaxWidth().height(42.dp),
                        )
                    }
                }
            }

            WifiRadioRegulatoryCard(
                capabilities = wifi.capabilities,
                threeColumnBands = threeColumnBands,
                onRequestNearbyWifiPermission = onRequestNearbyWifiPermission,
            )

            SurroundingWifiApsCard(
                scan = wifi.surroundingScan,
                bandFilter = state.wifiApBandFilter,
                sortOrder = state.wifiApSortOrder,
                maskBssids = state.maskApBssids,
                onSelectBandFilter = vm::setWifiApBandFilter,
                onCycleSortOrder = vm::cycleWifiApSortOrder,
                onToggleMaskBssids = vm::toggleMaskApBssids,
                onTriggerScan = vm::triggerWifiScan,
                onRequestWifiScanPermission = onRequestWifiScanPermission,
                onOpenLocationSettings = onOpenLocationSettings,
            )
        }
    }
}

// ------------------------------------------------------------------------ Tab 6: Bluetooth Audio & Peripherals

@Composable
private fun BluetoothTab(
    state: CorewatchUiState,
    vm: CorewatchViewModel,
) {
    val cs = MaterialTheme.colorScheme
    val bt = state.snapshot.bluetooth
    val drops = bt.audioDrops

    BoxWithConstraints(modifier = Modifier.fillMaxSize().testTag("bluetooth_tab")) {
        val threeColumnDrops = maxWidth >= 680.dp
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Card 1: Bluetooth Radio, Active Audio Route & Negotiated Codec
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = cs.surfaceContainer,
                modifier = Modifier.fillMaxWidth().testTag("bluetooth_card"),
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text("Bluetooth Radio & Audio Codec", style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = "Controller Capabilities: ${bt.controllerCapabilitiesSummary} · Max Concurrent Audio Sinks: ${bt.maxConnectedAudioDevices}",
                                style = CorewatchMonoTypography.hudSmall,
                                color = cs.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (bt.isAdapterEnabled) cs.surfaceContainerHigh else cs.errorContainer,
                            ) {
                                Text(
                                    text = if (bt.isAdapterEnabled) "Radio ON" else "Radio OFF",
                                    style = CorewatchMonoTypography.hudSmall,
                                    color = if (bt.isAdapterEnabled) cs.primary else cs.onErrorContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (bt.connectedCount > 0) cs.secondaryContainer else cs.surfaceContainerHigh,
                            ) {
                                Text(
                                    text = bt.connectionStateLabel,
                                    style = CorewatchMonoTypography.hudSmall,
                                    color = if (bt.connectedCount > 0) cs.onSecondaryContainer else cs.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                )
                            }
                        }
                    }

                    // Active Audio Route & Negotiated Bluetooth Codec Sub-Card
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = cs.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth().testTag("bluetooth_codec_section"),
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "Active Audio Route & Bluetooth Codec",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = cs.onSurface,
                                )
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (bt.audioCodec.isBluetoothAudioActive) cs.primaryContainer else cs.surfaceVariant,
                                ) {
                                    Text(
                                        text = "Codec: ${bt.audioCodec.activeCodecName}",
                                        style = CorewatchMonoTypography.hudSmall,
                                        color = if (bt.audioCodec.isBluetoothAudioActive) cs.onPrimaryContainer else cs.primary,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    )
                                }
                            }
                            Text(
                                text = "Output Route: ${bt.audioCodec.activeRouteLabel} · Audio Mode: ${bt.audioCodec.communicationModeLabel}",
                                style = CorewatchMonoTypography.hudSmall,
                                color = cs.onSurface,
                            )
                            Text(
                                text = "Stream Format: ${bt.audioCodec.sampleRateLabel} · ${bt.audioCodec.bitsPerSampleLabel} · ${bt.audioCodec.channelModeLabel} · Bitrate / Quality: ${bt.audioCodec.bitrateOrQualityLabel}",
                                style = CorewatchMonoTypography.hudSmall,
                                color = cs.onSurfaceVariant,
                            )
                            Text(
                                text = "Headset Selectable Codecs: ${bt.audioCodec.selectableCodecsSummary}",
                                style = CorewatchMonoTypography.hudSmall,
                                color = cs.primary,
                            )
                        }
                    }
                }
            }

            // Card 2: Bluetooth Audio Dropped Frames & Buffer Underflow Counters (A2DP · HFP/SCO · LE Audio)
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = cs.surfaceContainer,
                modifier = Modifier.fillMaxWidth().testTag("bluetooth_drops_card"),
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text("Bluetooth Audio Dropped Frames & Buffer Underflows", style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = "Per-profile encoder frame drops, HFP/SCO packet loss concealment (PLC), and LE Audio ISO underflows",
                                style = CorewatchMonoTypography.hudSmall,
                                color = cs.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = cs.surfaceContainerHigh,
                        ) {
                            Text(
                                text = drops.sourceLabel,
                                style = CorewatchMonoTypography.hudSmall,
                                color = if (drops.hasStackCounters) cs.primary else cs.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }

                    if (drops.hasStackCounters) {
                        if (threeColumnDrops) {
                            Row(
                                modifier = Modifier.fillMaxWidth().testTag("bluetooth_audio_drops_section"),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                BluetoothProfileDropTile(
                                    title = "A2DP MEDIA STREAM",
                                    primaryLabel = drops.a2dpPrimaryCounterLabel,
                                    detailSummary = drops.a2dpDetailSummary,
                                    hasError = drops.a2dpDroppedFrames > 0L || drops.a2dpUnderflows > 0L,
                                    modifier = Modifier.weight(1f),
                                )
                                BluetoothProfileDropTile(
                                    title = "HFP / SCO VOICE",
                                    primaryLabel = drops.hfpPrimaryCounterLabel,
                                    detailSummary = drops.hfpDetailSummary,
                                    hasError = drops.hfpDroppedPackets > 0L || drops.hfpUnderflows > 0L,
                                    modifier = Modifier.weight(1f),
                                )
                                BluetoothProfileDropTile(
                                    title = "LE AUDIO (CIS / BIS)",
                                    primaryLabel = drops.leAudioPrimaryCounterLabel,
                                    detailSummary = drops.leAudioDetailSummary,
                                    hasError = drops.leAudioUnderflows > 0L || drops.leIsoDroppedPackets > 0L,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        } else {
                            Column(
                                modifier = Modifier.fillMaxWidth().testTag("bluetooth_audio_drops_section"),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                BluetoothProfileDropTile(
                                    title = "A2DP MEDIA STREAM",
                                    primaryLabel = drops.a2dpPrimaryCounterLabel,
                                    detailSummary = drops.a2dpDetailSummary,
                                    hasError = drops.a2dpDroppedFrames > 0L || drops.a2dpUnderflows > 0L,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                BluetoothProfileDropTile(
                                    title = "HFP / SCO VOICE",
                                    primaryLabel = drops.hfpPrimaryCounterLabel,
                                    detailSummary = drops.hfpDetailSummary,
                                    hasError = drops.hfpDroppedPackets > 0L || drops.hfpUnderflows > 0L,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                BluetoothProfileDropTile(
                                    title = "LE AUDIO (CIS / BIS)",
                                    primaryLabel = drops.leAudioPrimaryCounterLabel,
                                    detailSummary = drops.leAudioDetailSummary,
                                    hasError = drops.leAudioUnderflows > 0L || drops.leIsoDroppedPackets > 0L,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    } else {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = cs.surfaceContainerHigh,
                            modifier = Modifier.fillMaxWidth().testTag("bluetooth_audio_drops_section"),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(3.dp),
                                ) {
                                    Text(
                                        text = "Per-Profile Stack Drop Counters Locked (A2DP · HFP/SCO · LE Audio)",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = cs.onSurface,
                                    )
                                    Text(
                                        text = "Android isolates Bluetooth encoder frame drops, PLC packet loss, and ISO underflows behind android.permission.DUMP. Grant once via ADB (persists across reboots):",
                                        style = CorewatchMonoTypography.hudSmall,
                                        color = cs.onSurfaceVariant,
                                    )
                                    Text(
                                        text = "adb shell pm grant --user current com.droid.corewatch android.permission.DUMP",
                                        style = CorewatchMonoTypography.hudSmall,
                                        color = cs.primary,
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                FilterChip(
                                    selected = false,
                                    onClick = {
                                        vm.copyTextToClipboard(
                                            label = "ADB Grant DUMP",
                                            text = "adb shell pm grant --user current com.droid.corewatch android.permission.DUMP",
                                            bannerMessage = "Copied ADB DUMP permission command to clipboard",
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Outlined.ContentCopy,
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp),
                                        )
                                    },
                                    label = { Text("Copy ADB Command") },
                                    modifier = Modifier.testTag("copy_bt_dump_adb_btn"),
                                )
                            }
                        }
                    }
                }
            }

            // Card 3: Connected & Paired Bluetooth Peripherals
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = cs.surfaceContainer,
                modifier = Modifier.fillMaxWidth().testTag("bluetooth_peripherals_card"),
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text("Connected & Paired Bluetooth Peripherals", style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = "BLE GATT / HID & Classic BR/EDR bonded devices, negotiated profiles, and live battery levels",
                                style = CorewatchMonoTypography.hudSmall,
                                color = cs.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (bt.connectedCount > 0) cs.secondaryContainer else cs.surfaceContainerHigh,
                        ) {
                            Text(
                                text = "${bt.connectedCount} Active · ${bt.bondedCount} Paired",
                                style = CorewatchMonoTypography.hudSmall,
                                color = if (bt.connectedCount > 0) cs.onSecondaryContainer else cs.primary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }

                    if (bt.peripherals.isEmpty()) {
                        val viewCtx = LocalView.current.context
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = if (bt.hasConnectPermission) {
                                    "No paired Bluetooth peripherals reported by BluetoothAdapter."
                                } else {
                                    "Grant BLUETOOTH_CONNECT permission to inspect paired Bluetooth peripherals and battery levels."
                                },
                                style = CorewatchMonoTypography.hudSmall,
                                color = cs.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            if (!bt.hasConnectPermission) {
                                Spacer(Modifier.width(8.dp))
                                FilterChip(
                                    selected = false,
                                    onClick = {
                                        runCatching {
                                            val act = viewCtx as? android.app.Activity
                                            if (act != null && Build.VERSION.SDK_INT >= 31) {
                                                act.requestPermissions(arrayOf(android.Manifest.permission.BLUETOOTH_CONNECT), 1001)
                                            }
                                        }
                                    },
                                    label = { Text("Grant Bluetooth Permission") },
                                    modifier = Modifier.testTag("grant_bluetooth_permission_btn"),
                                )
                            }
                        }
                    } else {
                        for (dev in bt.peripherals) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = cs.surfaceContainerHigh,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(2.dp),
                                    ) {
                                        Text(
                                            text = dev.name,
                                            style = CorewatchMonoTypography.hudValue,
                                            color = if (dev.isConnected) cs.secondary else cs.onSurface,
                                        )
                                        Text(
                                            text = "${dev.categoryLabel} · Transport: ${dev.transportTypeLabel}",
                                            style = CorewatchMonoTypography.hudSmall,
                                            color = cs.onSurfaceVariant,
                                        )
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        if (dev.batteryPercent in 0..100) {
                                            Text(
                                                text = "Battery: ${dev.batteryLabel}",
                                                style = CorewatchMonoTypography.hudValue,
                                                color = if (dev.batteryPercent <= 20) cs.error else cs.primary,
                                            )
                                        }
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = if (dev.isConnected) cs.secondaryContainer else cs.surfaceVariant,
                                        ) {
                                            Text(
                                                text = dev.profilesSummary,
                                                style = CorewatchMonoTypography.hudSmall,
                                                color = if (dev.isConnected) cs.onSecondaryContainer else cs.onSurfaceVariant,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BluetoothProfileDropTile(
    title: String,
    primaryLabel: String,
    detailSummary: String,
    hasError: Boolean,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = cs.surfaceContainerHigh,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = title,
                style = CorewatchMonoTypography.hudSmall,
                color = cs.onSurfaceVariant,
            )
            Text(
                text = primaryLabel,
                style = CorewatchMonoTypography.hudValue,
                color = if (hasError) cs.error else cs.onSurface,
            )
            Text(
                text = detailSummary,
                style = CorewatchMonoTypography.hudSmall,
                color = cs.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WifiRadioLinkCard(
    wifi: WifiTelemetrySnapshot,
    rssiSamples: List<Float>,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = cs.surfaceContainer,
        modifier = modifier.testTag("wifi_link_card"),
    ) {
        Column(modifier = Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.Wifi,
                        contentDescription = null,
                        tint = if (wifi.isConnected) cs.secondary else cs.onSurfaceVariant,
                        modifier = Modifier.size(17.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Wi-Fi Radio & Link", style = MaterialTheme.typography.titleMedium)
                }
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (wifi.isConnected) cs.primaryContainer else cs.surfaceContainerHigh,
                ) {
                    Text(
                        text = wifi.wifiStandardLabel,
                        style = CorewatchMonoTypography.hudSmall,
                        color = if (wifi.isConnected) cs.onPrimaryContainer else cs.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (wifi.rssiDbm > -120) {
                        "RSSI: ${wifi.rssiDbm} dBm · ${wifi.signalQualityLabel} (${wifi.signalQualityPercent}%)"
                    } else {
                        "Signal: ${wifi.linkHealthBadge}"
                    },
                    style = CorewatchMonoTypography.hudValue,
                    color = if (wifi.signalQualityPercent >= 60) cs.secondary else cs.primary,
                )
                Text(
                    text = "PHY: ${wifi.phySpeedSummary}",
                    style = CorewatchMonoTypography.hudValue,
                    color = cs.primary,
                )
            }

            LinearProgressIndicator(
                progress = { wifi.signalFraction },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                color = if (wifi.signalQualityPercent >= 60) cs.secondary else cs.primary,
                trackColor = cs.surfaceContainerHighest,
            )

            // Per-Interface Wi-Fi Throughput Strip (wlan*)
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = cs.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Wi-Fi Throughput (${wifi.interfaceName})",
                            style = CorewatchMonoTypography.hudSmall,
                            color = cs.onSurfaceVariant,
                        )
                        Text(
                            text = String.format(
                                Locale.US,
                                "↓ %.2f Mbps · ↑ %.2f Mbps",
                                wifi.throughput.rxMbps,
                                wifi.throughput.txMbps,
                            ),
                            style = CorewatchMonoTypography.hudValue,
                            color = if (wifi.isPrimaryDefaultRoute) cs.secondary else cs.onSurface,
                        )
                    }
                    Text(
                        text = String.format(
                            Locale.US,
                            "Pkts: ↓ %.0f · ↑ %.0f pkt/s · Boot: %s RX / %s TX",
                            wifi.throughput.rxPacketsPerSec,
                            wifi.throughput.txPacketsPerSec,
                            DiagnosticReportBuilder.formatGiB(wifi.throughput.totalRxBytes),
                            DiagnosticReportBuilder.formatGiB(wifi.throughput.totalTxBytes),
                        ),
                        style = CorewatchMonoTypography.hudSmall,
                        color = cs.onSurfaceVariant,
                    )
                }
            }

            Text(
                text = "Band: ${wifi.bandAndChannelLabel} · Security: ${wifi.securityLabel} · MLO: ${wifi.mloSummary}",
                style = CorewatchMonoTypography.hudSmall,
                color = cs.onSurfaceVariant,
            )
            Text(
                text = String.format(
                    Locale.US,
                    "Interface: %s (MTU %d) · %s · Role: %s",
                    wifi.interfaceName,
                    wifi.mtu,
                    wifi.ipStackLabel,
                    wifi.routeRoleLabel,
                ),
                style = CorewatchMonoTypography.hudSmall,
                color = cs.onSurfaceVariant,
            )
            Text(
                text = String.format(
                    Locale.US,
                    "Wi-Fi MAC Frames: TX Good %.1f/s · RX Good %.1f/s · Retries %.1f/s · Dropped %.1f/s",
                    wifi.txSuccessPktsPerSec,
                    wifi.rxSuccessPktsPerSec,
                    wifi.txRetriesPerSec,
                    wifi.txLostPktsPerSec,
                ),
                style = CorewatchMonoTypography.hudSmall,
                color = if (wifi.txLostPktsPerSec > 1.0) cs.error else cs.onSurfaceVariant,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "60s Signal Stability (RSSI -90 .. -25 dBm)",
                    style = CorewatchMonoTypography.hudSmall,
                    color = cs.onSurfaceVariant,
                )
                if (wifi.rssiDbm > -120) {
                    Text(
                        text = "${wifi.rssiDbm} dBm",
                        style = CorewatchMonoTypography.hudSmall,
                        color = cs.secondary,
                    )
                }
            }

            SparklineCanvas(
                samples = rssiSamples,
                lineColor = cs.secondary,
                gridColor = cs.outlineVariant.copy(alpha = 0.4f),
                minFloor = 0f,
                maxCeiling = 100f,
                modifier = Modifier.fillMaxWidth().height(42.dp),
            )
        }
    }
}

@Composable
private fun WiredEthernetLinkCard(
    eth: EthernetTelemetrySnapshot,
    ethMbpsSamples: List<Float>,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = cs.surfaceContainer,
        modifier = modifier.testTag("ethernet_link_card"),
    ) {
        Column(modifier = Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.SettingsEthernet,
                        contentDescription = null,
                        tint = cs.primary,
                        modifier = Modifier.size(17.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Wired Ethernet Link", style = MaterialTheme.typography.titleMedium)
                }
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = cs.primaryContainer,
                ) {
                    Text(
                        text = eth.linkSpeedTierLabel,
                        style = CorewatchMonoTypography.hudSmall,
                        color = cs.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Link: ${eth.negotiatedSpeedSummary}",
                    style = CorewatchMonoTypography.hudValue,
                    color = cs.primary,
                )
                Text(
                    text = "Role: ${eth.routeRoleLabel}",
                    style = CorewatchMonoTypography.hudValue,
                    color = if (eth.isPrimaryDefaultRoute) cs.secondary else cs.onSurfaceVariant,
                )
            }

            LinearProgressIndicator(
                progress = { eth.linkBarFraction },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                color = if (eth.dockBottleneckWarning != null) cs.tertiary else cs.primary,
                trackColor = cs.surfaceContainerHighest,
            )

            // Per-Interface Wired Ethernet Throughput Strip (eth* / en*)
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = cs.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Ethernet Throughput (${eth.interfaceName})",
                            style = CorewatchMonoTypography.hudSmall,
                            color = cs.onSurfaceVariant,
                        )
                        Text(
                            text = String.format(
                                Locale.US,
                                "↓ %.2f Mbps · ↑ %.2f Mbps",
                                eth.throughput.rxMbps,
                                eth.throughput.txMbps,
                            ),
                            style = CorewatchMonoTypography.hudValue,
                            color = cs.primary,
                        )
                    }
                    Text(
                        text = String.format(
                            Locale.US,
                            "Pkts: ↓ %.0f · ↑ %.0f pkt/s · Boot: %s RX / %s TX",
                            eth.throughput.rxPacketsPerSec,
                            eth.throughput.txPacketsPerSec,
                            DiagnosticReportBuilder.formatGiB(eth.throughput.totalRxBytes),
                            DiagnosticReportBuilder.formatGiB(eth.throughput.totalTxBytes),
                        ),
                        style = CorewatchMonoTypography.hudSmall,
                        color = cs.onSurfaceVariant,
                    )
                }
            }

            Text(
                text = String.format(
                    Locale.US,
                    "Interface: %s (MTU %d) · %s · %s (%s)",
                    eth.interfaceName,
                    eth.mtu,
                    eth.ipStackLabel,
                    if (eth.isValidated) "Validated" else "Unvalidated",
                    if (eth.isMetered) "Metered" else "Unmetered",
                ),
                style = CorewatchMonoTypography.hudSmall,
                color = cs.onSurfaceVariant,
            )

            Text(
                text = "DNS: ${eth.dnsSummaryLabel} · Wake-on-LAN: ${if (eth.wakeOnLanSupported) "Supported" else "Unsupported"}",
                style = CorewatchMonoTypography.hudSmall,
                color = cs.onSurfaceVariant,
            )

            val dockWarning = eth.dockBottleneckWarning
            if (dockWarning != null) {
                Text(
                    text = "⚠ $dockWarning",
                    style = CorewatchMonoTypography.hudSmall,
                    color = cs.tertiary,
                    modifier = Modifier.testTag("ethernet_bottleneck_warning"),
                )
            } else {
                Text(
                    text = "Carrier: IEEE 802.3 Full-Duplex · Dedicated Wired Link",
                    style = CorewatchMonoTypography.hudSmall,
                    color = cs.onSurfaceVariant,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "60s Wired Ethernet RX + TX (Mbps)",
                    style = CorewatchMonoTypography.hudSmall,
                    color = cs.onSurfaceVariant,
                )
                Text(
                    text = String.format(Locale.US, "%.2f Mbps", eth.throughput.rxMbps + eth.throughput.txMbps),
                    style = CorewatchMonoTypography.hudSmall,
                    color = cs.primary,
                )
            }

            SparklineCanvas(
                samples = ethMbpsSamples,
                lineColor = cs.primary,
                gridColor = cs.outlineVariant.copy(alpha = 0.4f),
                minFloor = 0f,
                modifier = Modifier.fillMaxWidth().height(42.dp),
            )
        }
    }
}

@Composable
private fun WifiRadioRegulatoryCard(
    capabilities: WifiRadioCapabilities,
    threeColumnBands: Boolean,
    onRequestNearbyWifiPermission: (() -> Unit)?,
) {
    val cs = MaterialTheme.colorScheme
    val viewCtx = LocalView.current.context
    val sixGhzWarn = capabilities.sixGhzRegulatoryWarning
    val isWorldOrBlocked = sixGhzWarn != null || capabilities.countryCode == "00"

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = cs.surfaceContainer,
        modifier = Modifier.fillMaxWidth().testTag("wifi_regulatory_card"),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f, fill = false),
                ) {
                    Icon(
                        Icons.Outlined.Speed,
                        contentDescription = null,
                        tint = cs.primary,
                        modifier = Modifier.size(17.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Device Wi-Fi Radio & Regulatory Capabilities",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = cs.primaryContainer,
                    ) {
                        Text(
                            text = "Max: ${capabilities.maxGenerationLabel}",
                            style = CorewatchMonoTypography.hudSmall,
                            color = cs.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (isWorldOrBlocked) cs.errorContainer else cs.secondaryContainer,
                        modifier = Modifier.testTag("wifi_reg_country_badge"),
                    ) {
                        Text(
                            text = "Region: ${capabilities.countryCode} · ${capabilities.countryCodeSource}",
                            style = CorewatchMonoTypography.hudSmall,
                            color = if (isWorldOrBlocked) cs.onErrorContainer else cs.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
            }

            if (sixGhzWarn != null) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = cs.errorContainer,
                    modifier = Modifier.fillMaxWidth().testTag("wifi_6ghz_reg_warning"),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            Icons.Outlined.WarningAmber,
                            contentDescription = null,
                            tint = cs.onErrorContainer,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = sixGhzWarn,
                            style = CorewatchMonoTypography.hudSmall,
                            color = cs.onErrorContainer,
                        )
                    }
                }
            }

            Surface(
                shape = RoundedCornerShape(9.dp),
                color = cs.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "Supported Generations: ${capabilities.supportedGenerationsSummary}",
                        style = CorewatchMonoTypography.hudSmall,
                        color = cs.onSurface,
                    )
                    Text(
                        text = "Supported Bands: ${capabilities.supportedBandsSummary} · Max Supported PHY: ${capabilities.maxPhySpeedSummary}",
                        style = CorewatchMonoTypography.hudSmall,
                        color = cs.primary,
                    )
                    Text(
                        text = "Security Capabilities: ${capabilities.securityCapabilitiesSummary}",
                        style = CorewatchMonoTypography.hudSmall,
                        color = cs.onSurfaceVariant,
                    )
                    Text(
                        text = "Concurrency & Roaming: ${capabilities.concurrencySummary}",
                        style = CorewatchMonoTypography.hudSmall,
                        color = cs.onSurfaceVariant,
                    )
                }
            }

            Text(
                text = "REGULATORY ALLOWED & USABLE STA CHANNELS IN CURRENT REGION (${capabilities.countryCode})",
                style = CorewatchMonoTypography.hudSmall,
                color = cs.onSurfaceVariant,
            )

            if (capabilities.channelQuerySucceeded) {
                if (threeColumnBands) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        BandChannelTile(
                            summary = capabilities.band24Ghz,
                            tag = "wifi_band_24g_tile",
                            modifier = Modifier.weight(1f),
                        )
                        BandChannelTile(
                            summary = capabilities.band5Ghz,
                            tag = "wifi_band_5g_tile",
                            modifier = Modifier.weight(1f),
                        )
                        BandChannelTile(
                            summary = capabilities.band6Ghz,
                            tag = "wifi_band_6g_tile",
                            modifier = Modifier.weight(1f),
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        BandChannelTile(
                            summary = capabilities.band24Ghz,
                            tag = "wifi_band_24g_tile",
                            modifier = Modifier.fillMaxWidth(),
                        )
                        BandChannelTile(
                            summary = capabilities.band5Ghz,
                            tag = "wifi_band_5g_tile",
                            modifier = Modifier.fillMaxWidth(),
                        )
                        BandChannelTile(
                            summary = capabilities.band6Ghz,
                            tag = "wifi_band_6g_tile",
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            } else if (!capabilities.hasNearbyWifiPermission && Build.VERSION.SDK_INT >= 33) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Grant NEARBY_WIFI_DEVICES (neverForLocation) to inspect allowed & usable 2.4 / 5 / 6 GHz channels in the active regulatory domain.",
                        style = CorewatchMonoTypography.hudSmall,
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    FilterChip(
                        selected = false,
                        onClick = {
                            if (onRequestNearbyWifiPermission != null) {
                                onRequestNearbyWifiPermission()
                            } else {
                                runCatching {
                                    val act = viewCtx as? android.app.Activity
                                    act?.requestPermissions(arrayOf(android.Manifest.permission.NEARBY_WIFI_DEVICES), 1003)
                                }
                            }
                        },
                        label = { Text("Grant Nearby Wi-Fi") },
                        modifier = Modifier.testTag("grant_nearby_wifi_permission_btn"),
                    )
                }
            } else {
                Text(
                    text = "Per-band regulatory channel table requires Android 14+ (API 34) WifiManager.getAllowedChannels().",
                    style = CorewatchMonoTypography.hudSmall,
                    color = cs.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BandChannelTile(
    summary: BandChannelSummary,
    tag: String,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val isRegBlocked = summary.isHardwareSupported && summary.allowedCount == 0
    val isUsableDegraded = summary.isHardwareSupported && summary.allowedCount > 0 && summary.usableCount == 0

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (isRegBlocked) cs.errorContainer.copy(alpha = 0.45f) else cs.surfaceContainerHigh,
        modifier = modifier.testTag(tag),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = summary.bandLabel,
                    style = CorewatchMonoTypography.hudValue,
                    color = cs.onSurface,
                )
                Text(
                    text = summary.countBadge,
                    style = CorewatchMonoTypography.hudSmall,
                    color = when {
                        isRegBlocked || isUsableDegraded -> cs.error
                        summary.isHardwareSupported -> cs.secondary
                        else -> cs.onSurfaceVariant
                    },
                )
            }
            Text(
                text = "Channels: ${summary.channelRangeSummary}",
                style = CorewatchMonoTypography.hudSmall,
                color = if (isRegBlocked) cs.error else cs.primary,
            )
            if (summary.isHardwareSupported && summary.maxBandwidthMhz > 0) {
                Text(
                    text = "Max Width: ${summary.maxBandwidthMhz} MHz",
                    style = CorewatchMonoTypography.hudSmall,
                    color = cs.onSurface,
                )
            }
            if (summary.subBandNote.isNotBlank()) {
                Text(
                    text = summary.subBandNote,
                    style = CorewatchMonoTypography.hudSmall,
                    color = cs.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SurroundingWifiApsCard(
    scan: WifiScanTelemetrySnapshot,
    bandFilter: WifiApBandFilter,
    sortOrder: WifiApSortOrder,
    maskBssids: Boolean,
    onSelectBandFilter: (WifiApBandFilter) -> Unit,
    onCycleSortOrder: () -> Unit,
    onToggleMaskBssids: () -> Unit,
    onTriggerScan: () -> Unit,
    onRequestWifiScanPermission: (() -> Unit)?,
    onOpenLocationSettings: (() -> Unit)?,
) {
    val cs = MaterialTheme.colorScheme
    val viewCtx = LocalView.current.context

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = cs.surfaceContainer,
        modifier = Modifier.fillMaxWidth().testTag("surrounding_wifi_aps_card"),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = "Surrounding Wi-Fi Access Points (Verbose Beacons)",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    val ageNote = if (scan.lastScanAgeSeconds >= 0f) {
                        String.format(Locale.US, " · Newest beacon %.1fs ago", scan.lastScanAgeSeconds)
                    } else {
                        ""
                    }
                    Text(
                        text = "Band, channel, bandwidth, Wi-Fi generation, AKM security, 802.11d/k/r/v & QBSS load$ageNote",
                        style = CorewatchMonoTypography.hudSmall,
                        color = cs.onSurfaceVariant,
                    )
                }
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (scan.hasScanLocationPermission && scan.isLocationEnabled && scan.isWifiEnabled) {
                        cs.secondaryContainer
                    } else {
                        cs.surfaceContainerHigh
                    },
                ) {
                    Text(
                        text = scan.permissionStateSummary,
                        style = CorewatchMonoTypography.hudSmall,
                        color = if (scan.hasScanLocationPermission && scan.isLocationEnabled && scan.isWifiEnabled) {
                            cs.onSecondaryContainer
                        } else {
                            cs.onSurfaceVariant
                        },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }

            if (scan.isWifiEnabled && scan.hasScanLocationPermission && scan.isLocationEnabled) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val filterLabels = listOf(
                        WifiApBandFilter.ALL to "All (${scan.totalApCount})",
                        WifiApBandFilter.BAND_24_GHZ to "2.4 GHz (${scan.count24Ghz})",
                        WifiApBandFilter.BAND_5_GHZ to "5 GHz (${scan.count5Ghz})",
                        WifiApBandFilter.BAND_6_GHZ to "6 GHz (${scan.count6Ghz})",
                    )
                    for ((f, label) in filterLabels) {
                        FilterChip(
                            selected = bandFilter == f,
                            onClick = { onSelectBandFilter(f) },
                            label = { Text(label) },
                            modifier = Modifier.testTag("wifi_filter_${f.name}"),
                        )
                    }
                    FilterChip(
                        selected = false,
                        onClick = onCycleSortOrder,
                        label = { Text(sortOrder.label) },
                        modifier = Modifier.testTag("wifi_ap_sort_chip"),
                    )
                    FilterChip(
                        selected = !maskBssids,
                        onClick = onToggleMaskBssids,
                        label = { Text(if (maskBssids) "BSSID: Masked" else "BSSID: Full") },
                        modifier = Modifier.testTag("wifi_ap_mask_bssid_chip"),
                    )
                    FilterChip(
                        selected = false,
                        onClick = onTriggerScan,
                        leadingIcon = {
                            Icon(
                                Icons.Outlined.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                            )
                        },
                        label = { Text("Scan Now") },
                        modifier = Modifier.testTag("wifi_scan_now_btn"),
                    )
                }
            }

            when {
                !scan.isWifiEnabled -> {
                    Text(
                        text = "Wi-Fi adapter is currently turned off. Enable Wi-Fi to inspect surrounding access points.",
                        style = CorewatchMonoTypography.hudSmall,
                        color = cs.onSurfaceVariant,
                    )
                }

                !scan.hasScanLocationPermission -> {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = cs.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Text(
                                    text = "Opt-In Location Permission Required by Android for Surrounding AP List",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = cs.onSurface,
                                )
                                Text(
                                    text = "Android gates WifiManager.getScanResults() (SSID, BSSID, channel, bandwidth, security, 802.11d/k/r/v IEs) behind Location permission. Corewatch holds zero INTERNET permission — AP beacons never leave your device and are omitted from exported reports.",
                                    style = CorewatchMonoTypography.hudSmall,
                                    color = cs.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            FilterChip(
                                selected = false,
                                onClick = {
                                    if (onRequestWifiScanPermission != null) {
                                        onRequestWifiScanPermission()
                                    } else {
                                        runCatching {
                                            val act = viewCtx as? android.app.Activity
                                            act?.requestPermissions(
                                                arrayOf(
                                                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                                                    android.Manifest.permission.ACCESS_COARSE_LOCATION,
                                                ),
                                                1002,
                                            )
                                        }
                                    }
                                },
                                label = { Text("Grant Location for AP Scan") },
                                modifier = Modifier.testTag("grant_wifi_scan_permission_btn"),
                            )
                        }
                    }
                }

                !scan.isLocationEnabled -> {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = cs.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Location permission is granted, but Android's system Location Services toggle is Off — Android blocks Wi-Fi scan results while Location is disabled.",
                                style = CorewatchMonoTypography.hudSmall,
                                color = cs.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(12.dp))
                            FilterChip(
                                selected = false,
                                onClick = {
                                    if (onOpenLocationSettings != null) {
                                        onOpenLocationSettings()
                                    } else {
                                        runCatching {
                                            viewCtx.startActivity(android.content.Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                                        }
                                    }
                                },
                                label = { Text("Open Location Settings") },
                                modifier = Modifier.testTag("open_location_settings_btn"),
                            )
                        }
                    }
                }

                else -> {
                    val filtered = remember(scan.accessPoints, bandFilter, sortOrder) {
                        val bandMatched = when (bandFilter) {
                            WifiApBandFilter.ALL -> scan.accessPoints
                            WifiApBandFilter.BAND_24_GHZ -> scan.accessPoints.filter { it.bandGhz == 2 }
                            WifiApBandFilter.BAND_5_GHZ -> scan.accessPoints.filter { it.bandGhz == 5 }
                            WifiApBandFilter.BAND_6_GHZ -> scan.accessPoints.filter { it.bandGhz == 6 }
                        }
                        when (sortOrder) {
                            WifiApSortOrder.SIGNAL_DBM -> bandMatched.sortedWith(
                                compareByDescending<SurroundingWifiAp> { it.isConnectedAp }
                                    .thenByDescending { it.rssiDbm },
                            )
                            WifiApSortOrder.BAND_CHANNEL -> bandMatched.sortedWith(
                                compareBy<SurroundingWifiAp> { it.bandGhz }
                                    .thenBy { it.channelNumber }
                                    .thenByDescending { it.rssiDbm },
                            )
                            WifiApSortOrder.SSID_NAME -> bandMatched.sortedWith(
                                compareBy<SurroundingWifiAp> { it.isHiddenSsid }
                                    .thenBy { it.ssid.lowercase(Locale.US) }
                                    .thenByDescending { it.rssiDbm },
                            )
                        }
                    }

                    if (filtered.isEmpty()) {
                        Text(
                            text = "No access points detected on ${bandFilter.label}. Tap 'Scan Now' to trigger an active scan.",
                            style = CorewatchMonoTypography.hudSmall,
                            color = cs.onSurfaceVariant,
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (ap in filtered.take(40)) {
                                SurroundingApRow(ap = ap, maskBssids = maskBssids)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SurroundingApRow(
    ap: SurroundingWifiAp,
    maskBssids: Boolean,
) {
    val cs = MaterialTheme.colorScheme
    val displayBssid = if (maskBssids) ap.maskedBssid else ap.bssid
    val signalColor = when {
        ap.rssiDbm >= -60 -> cs.secondary
        ap.rssiDbm >= -73 -> cs.primary
        else -> cs.tertiary
    }

    Surface(
        shape = RoundedCornerShape(9.dp),
        color = if (ap.isConnectedAp) cs.secondaryContainer.copy(alpha = 0.35f) else cs.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = ap.ssid,
                        style = CorewatchMonoTypography.hudValue,
                        color = when {
                            ap.isConnectedAp -> cs.secondary
                            ap.isHiddenSsid -> cs.onSurfaceVariant
                            else -> cs.onSurface
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (ap.isConnectedAp) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = cs.secondaryContainer,
                        ) {
                            Text(
                                text = "CONNECTED",
                                style = CorewatchMonoTypography.hudSmall,
                                color = cs.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                            )
                        }
                    }
                    Text(
                        text = displayBssid,
                        style = CorewatchMonoTypography.hudSmall,
                        color = cs.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(5.dp),
                        color = if (ap.bandGhz == 6) cs.primaryContainer else cs.surfaceVariant,
                    ) {
                        Text(
                            text = ap.wifiStandardLabel,
                            style = CorewatchMonoTypography.hudSmall,
                            color = if (ap.bandGhz == 6) cs.onPrimaryContainer else cs.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                    Text(
                        text = "${ap.rssiDbm} dBm (${ap.signalPercent}%)",
                        style = CorewatchMonoTypography.hudValue,
                        color = signalColor,
                    )
                }
            }

            val badgesPart = if (ap.capabilityBadges.isNotEmpty()) {
                " [${ap.capabilityBadges.joinToString(" · ")}]"
            } else {
                ""
            }
            val extras = buildList {
                ap.countryCode80211d?.let { add("802.11d: $it") }
                ap.bssLoadSummary?.let { add(it) }
                if (ap.ageSeconds > 0f) add(String.format(Locale.US, "%.1fs ago", ap.ageSeconds))
            }.joinToString(" · ")
            val extrasPart = if (extras.isNotEmpty()) " · $extras" else ""

            Text(
                text = "${ap.channelAndFreqSummary} · ${ap.securitySummary}$badgesPart$extrasPart",
                style = CorewatchMonoTypography.hudSmall,
                color = cs.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DisplayCard(disp: DisplaySpec) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = cs.surfaceContainer,
        modifier = Modifier.fillMaxWidth().testTag("display_card_${disp.displayId}"),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.DesktopWindows, contentDescription = null, tint = cs.primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    val modelSuffix = if (disp.panelModel.isNotEmpty()) " · ${disp.panelModel}" else ""
                    Text("${disp.name} (#${disp.displayId}$modelSuffix)", style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    text = "${disp.activeResolutionLabel} @ ${disp.activeRefreshLabel}",
                    style = CorewatchMonoTypography.hudValue,
                    color = if (disp.bottleneckWarning != null) cs.error else cs.secondary,
                )
            }

            Text(
                text = String.format(
                    Locale.US,
                    "%s · Logical DPI: %d (%d%% scale) · Physical DPI: %.0f×%.0f · Color: %d bpc · HDR: %s",
                    disp.connectionLabel,
                    disp.logicalDpi,
                    disp.scalePercent,
                    disp.physicalXDpi,
                    disp.physicalYDpi,
                    disp.maxBpc,
                    if (disp.hdrFormats.isEmpty()) "SDR only" else disp.hdrFormats.joinToString(", "),
                ),
                style = CorewatchMonoTypography.hudSmall,
                color = cs.onSurfaceVariant,
            )

            // Variable Refresh Rate (VRR) & Cadence Telemetry Sub-Card
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = cs.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth().testTag("display_vrr_section_${disp.displayId}"),
            ) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Variable Refresh Rate (VRR) & Cadence",
                            style = MaterialTheme.typography.labelLarge,
                            color = cs.onSurface,
                        )
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (disp.isVrrSupported) cs.primaryContainer else cs.surfaceVariant,
                        ) {
                            Text(
                                text = disp.vrrStatusBadge,
                                style = CorewatchMonoTypography.hudSmall,
                                color = if (disp.isVrrSupported) cs.onPrimaryContainer else cs.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                    Text(
                        text = "Live Cadence: ${disp.liveCadenceSummary} · Range: ${disp.vrrRangeSummary}",
                        style = CorewatchMonoTypography.hudSmall,
                        color = cs.onSurfaceVariant,
                    )
                    Text(
                        text = String.format(
                            Locale.US,
                            "DRM vrr_capable: %s · CRTC VRR_ENABLED: %s · Android ARR: %s · 5m VRR Switches: %d",
                            if (disp.vrrCapable) "Yes (1)" else "No (0)",
                            if (disp.vrrKernelEnabled) "Active (1)" else "Idle (0)",
                            if (disp.hasAndroidArr) "Supported" else "eDP Dynamic Steps",
                            disp.vrrSwitchCount5m,
                        ),
                        style = CorewatchMonoTypography.hudSmall,
                        color = cs.onSurfaceVariant,
                    )
                    if (disp.vrrResidencyBuckets.isNotEmpty()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "5-MINUTE VRR REFRESH RATE TIME-IN-STATE",
                            style = CorewatchMonoTypography.hudSmall,
                            color = cs.onSurface,
                        )
                        for (bucket in disp.vrrResidencyBuckets) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = String.format(Locale.US, "%3d Hz", bucket.hz),
                                    style = CorewatchMonoTypography.hudValue,
                                    color = cs.primary,
                                    modifier = Modifier.width(52.dp),
                                )
                                LinearProgressIndicator(
                                    progress = { bucket.fraction.coerceIn(0f, 1f) },
                                    modifier = Modifier.weight(1f).height(7.dp).clip(CircleShape),
                                    color = cs.primary,
                                    trackColor = cs.surfaceContainerHighest,
                                )
                                Text(
                                    text = bucket.summaryLabel,
                                    style = CorewatchMonoTypography.hudSmall,
                                    color = cs.onSurfaceVariant,
                                    modifier = Modifier.widthIn(min = 96.dp),
                                )
                            }
                        }
                    }
                }
            }

            disp.bottleneckWarning?.let { warning ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(cs.errorContainer)
                        .border(1.dp, cs.error, RoundedCornerShape(8.dp))
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = cs.onErrorContainer, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(warning, style = MaterialTheme.typography.bodySmall, color = cs.onErrorContainer)
                }
            }

            if (disp.supportedModes.isNotEmpty()) {
                Text(
                    text = "Supported Modes: " + disp.supportedModes.take(8).joinToString(" · ") { m ->
                        if (m.isActive) "[${m.label} ★]" else m.label
                    },
                    style = CorewatchMonoTypography.hudSmall,
                    color = cs.onSurfaceVariant,
                )
            }
        }
    }
}

// ------------------------------------------------------------------------ Tab 6: USB & Peripherals

private data class VisibleUsbTreeRow(
    val node: UsbTopologyNode,
    val treePrefix: String,
    val hasChildrenInScope: Boolean,
    val isCollapsed: Boolean,
)

private fun computeVisibleUsbRows(
    allNodes: List<UsbTopologyNode>,
    showInternal: Boolean,
    categoryFilter: UsbCategoryFilter,
    rawQuery: String,
    collapsedIds: Set<String>,
): List<VisibleUsbTreeRow> {
    if (allNodes.isEmpty()) return emptyList()
    val byId = allNodes.associateBy { it.id }

    // 1. Scope nodes by External-Only vs Include-Internal
    val scoped = allNodes.filter { showInternal || !it.isInternal }
    if (scoped.isEmpty()) return emptyList()

    val query = rawQuery.trim().lowercase(Locale.US)
    val hasActiveFilterOrQuery = categoryFilter != UsbCategoryFilter.All || query.isNotEmpty()

    fun matchesFilterAndQuery(node: UsbTopologyNode): Boolean {
        val filterOk = when (categoryFilter) {
            UsbCategoryFilter.All -> true
            UsbCategoryFilter.WarningsOnly -> node.healthStatus.isWarning
            else -> node.category.filterGroup == categoryFilter
        }
        if (!filterOk) return false
        if (query.isEmpty()) return true
        return node.displayTitle.lowercase(Locale.US).contains(query) ||
            node.vendorName.lowercase(Locale.US).contains(query) ||
            node.vidPidHex.lowercase(Locale.US).contains(query) ||
            node.portChain.lowercase(Locale.US).contains(query) ||
            node.category.label.lowercase(Locale.US).contains(query) ||
            node.interfaces.any {
                it.driverName.lowercase(Locale.US).contains(query) ||
                    it.classLabel.lowercase(Locale.US).contains(query)
            }
    }

    val includedIds = HashSet<String>()
    if (!hasActiveFilterOrQuery) {
        // Default view: include all non-root scoped nodes + root controllers that have children (or all roots if showInternal)
        val nonRoots = scoped.filter { !it.isRootController }
        for (nr in nonRoots) {
            includedIds += nr.id
            var curParent = nr.parentId
            while (curParent != null) {
                includedIds += curParent
                curParent = byId[curParent]?.parentId
            }
        }
        if (showInternal) {
            scoped.filter { it.isRootController }.forEach { includedIds += it.id }
        }
    } else {
        for (node in scoped) {
            if (node.isRootController && query.isEmpty() && categoryFilter != UsbCategoryFilter.HubsDocks) continue
            if (matchesFilterAndQuery(node)) {
                includedIds += node.id
                var curParent = node.parentId
                while (curParent != null) {
                    includedIds += curParent
                    curParent = byId[curParent]?.parentId
                }
            }
        }
    }

    val filteredOrdered = scoped.filter { it.id in includedIds }
    if (filteredOrdered.isEmpty()) return emptyList()

    val parentsWithChildren = filteredOrdered.mapNotNull { it.parentId }.toSet()

    // 2. Apply collapse state when search query is blank
    val expandedOrdered = if (query.isNotEmpty()) {
        filteredOrdered
    } else {
        filteredOrdered.filter { node ->
            var cur = node.parentId
            var hidden = false
            while (cur != null) {
                if (cur in collapsedIds) {
                    hidden = true
                    break
                }
                cur = byId[cur]?.parentId
            }
            !hidden
        }
    }

    // 3. Build Unicode box-drawing connector prefixes based on remaining visible siblings
    return expandedOrdered.mapIndexed { idx, node ->
        val prefix = if (node.depth <= 0) {
            ""
        } else {
            val ancestorContinuations = ArrayList<Boolean>()
            var cur: UsbTopologyNode? = node
            while (cur != null && cur.depth > 1) {
                val anc = cur.parentId?.let { byId[it] } ?: break
                val ancHasLaterSibling = expandedOrdered.drop(idx + 1).any { later ->
                    later.parentId == anc.parentId && later.depth == anc.depth
                }
                ancestorContinuations.add(0, ancHasLaterSibling)
                cur = anc
            }
            val hasLaterSibling = expandedOrdered.drop(idx + 1).any { later ->
                later.parentId == node.parentId && later.depth == node.depth
            }
            buildString {
                for (cont in ancestorContinuations) {
                    append(if (cont) "│  " else "   ")
                }
                append(if (hasLaterSibling) "├─ " else "└─ ")
            }
        }
        VisibleUsbTreeRow(
            node = node,
            treePrefix = prefix,
            hasChildrenInScope = node.id in parentsWithChildren,
            isCollapsed = query.isEmpty() && node.id in collapsedIds,
        )
    }
}

@Composable
private fun UsbPeripheralsTab(
    state: CorewatchUiState,
    vm: CorewatchViewModel,
    onSearchFocusChanged: (Boolean) -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    val snap = state.snapshot
    val usb = snap.usbPeripherals

    val visibleRows = remember(
        usb.nodes,
        state.showInternalUsbDevices,
        state.usbCategoryFilter,
        state.usbSearchQuery,
        state.collapsedUsbNodeIds,
    ) {
        computeVisibleUsbRows(
            allNodes = usb.nodes,
            showInternal = state.showInternalUsbDevices,
            categoryFilter = state.usbCategoryFilter,
            rawQuery = state.usbSearchQuery,
            collapsedIds = state.collapsedUsbNodeIds,
        )
    }

    val selectedNode: UsbTopologyNode? = remember(visibleRows, state.selectedUsbNodeId, usb.nodes) {
        visibleRows.firstOrNull { it.node.id == state.selectedUsbNodeId }?.node
            ?: visibleRows.firstOrNull { !it.node.isRootController && it.node.category != UsbNodeCategory.Hub }?.node
            ?: visibleRows.firstOrNull()?.node
            ?: usb.nodes.firstOrNull { !it.isInternal && !it.isRootController }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .testTag("usb_peripherals_tab"),
    ) {
        val sideBySideMasterDetail = maxWidth >= 820.dp
        val fourColumnSummary = maxWidth >= 700.dp

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 1. Fleet Summary & Type-C Port Status Card
            UsbSummaryAndTypeCCard(
                usb = usb,
                batterySummary = snap.battery.statusSummary,
                fourColumnSummary = fourColumnSummary,
                onCopyAdbDumpCommand = {
                    vm.copyTextToClipboard(
                        label = "ADB Grant DUMP",
                        text = "adb shell pm grant --user current com.droid.corewatch android.permission.DUMP",
                        bannerMessage = "Copied ADB DUMP permission command to clipboard",
                    )
                },
            )

            // 2. Responsive Master-Detail USB Topology & Node Inspector
            if (sideBySideMasterDetail) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    UsbTopologyTreeCard(
                        state = state,
                        usb = usb,
                        visibleRows = visibleRows,
                        selectedNodeId = selectedNode?.id,
                        onSelectNode = vm::selectUsbNode,
                        onToggleCollapse = vm::toggleUsbNodeCollapsed,
                        onSearchQueryChange = vm::setUsbSearchQuery,
                        onCategoryFilterChange = vm::setUsbCategoryFilter,
                        onToggleInternal = vm::toggleShowInternalUsbDevices,
                        onSearchFocusChanged = onSearchFocusChanged,
                        modifier = Modifier.weight(1.08f),
                    )
                    UsbNodeDetailsCard(
                        node = selectedNode,
                        allNodes = usb.nodes,
                        onSelectCompanionNode = { compId ->
                            val compNode = usb.nodes.firstOrNull { it.id == compId }
                            if (compNode?.isInternal == true && !state.showInternalUsbDevices) {
                                vm.toggleShowInternalUsbDevices()
                            }
                            vm.selectUsbNode(compId)
                        },
                        onJumpToTab = vm::selectTab,
                        modifier = Modifier.weight(0.92f),
                    )
                }
            } else {
                UsbTopologyTreeCard(
                    state = state,
                    usb = usb,
                    visibleRows = visibleRows,
                    selectedNodeId = selectedNode?.id,
                    onSelectNode = vm::selectUsbNode,
                    onToggleCollapse = vm::toggleUsbNodeCollapsed,
                    onSearchQueryChange = vm::setUsbSearchQuery,
                    onCategoryFilterChange = vm::setUsbCategoryFilter,
                    onToggleInternal = vm::toggleShowInternalUsbDevices,
                    onSearchFocusChanged = onSearchFocusChanged,
                    modifier = Modifier.fillMaxWidth(),
                )
                UsbNodeDetailsCard(
                    node = selectedNode,
                    allNodes = usb.nodes,
                    onSelectCompanionNode = { compId ->
                        val compNode = usb.nodes.firstOrNull { it.id == compId }
                        if (compNode?.isInternal == true && !state.showInternalUsbDevices) {
                            vm.toggleShowInternalUsbDevices()
                        }
                        vm.selectUsbNode(compId)
                    },
                    onJumpToTab = vm::selectTab,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // 3. Session Hotplug & Bottleneck Timeline Card
            UsbHotplugTimelineCard(
                events = usb.hotplugEvents,
                onSelectNode = { nodeId ->
                    if (usb.nodes.any { it.id == nodeId }) {
                        vm.selectUsbNode(nodeId)
                    }
                },
            )
        }
    }
}

@Composable
private fun UsbSummaryAndTypeCCard(
    usb: com.droid.corewatch.data.UsbPeripheralTelemetrySnapshot,
    batterySummary: String,
    fourColumnSummary: Boolean,
    onCopyAdbDumpCommand: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = cs.surfaceContainer,
        modifier = Modifier.fillMaxWidth().testTag("usb_summary_typec_card"),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Usb, contentDescription = null, tint = cs.primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("USB & Type-C Topology Overview", style = MaterialTheme.typography.titleMedium)
                }
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (usb.warningCount > 0) cs.errorContainer else cs.secondaryContainer,
                ) {
                    Text(
                        text = if (usb.warningCount > 0) {
                            "${usb.warningCount} Bottleneck / Port Warning(s)"
                        } else {
                            "All Links Nominal"
                        },
                        style = CorewatchMonoTypography.hudSmall,
                        color = if (usb.warningCount > 0) cs.onErrorContainer else cs.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }

            if (fourColumnSummary) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    UsbKpiTile(
                        label = "EXTERNAL PERIPHERALS",
                        value = "${usb.externalPeripheralCount}",
                        subLabel = "+${usb.internalDeviceCount} fixed internal",
                        modifier = Modifier.weight(1f),
                    )
                    UsbKpiTile(
                        label = "EXTERNAL HUBS & DOCKS",
                        value = "${usb.externalHubCount}",
                        subLabel = "Multi-tier xHCI tree",
                        modifier = Modifier.weight(1f),
                    )
                    UsbKpiTile(
                        label = "PEAK NEGOTIATED LINK",
                        value = usb.maxExternalSpeedLabel,
                        subLabel = "Host max: ${usb.maxControllerSpeedLabel}",
                        highlightPrimary = usb.maxExternalSpeedMbps >= 5_000f,
                        modifier = Modifier.weight(1f),
                    )
                    UsbKpiTile(
                        label = "USB-C PD POWER FLOW",
                        value = batterySummary.substringBefore(" ("),
                        subLabel = batterySummary.substringAfter("(", "").removeSuffix(")").ifBlank { "BatteryManager" },
                        modifier = Modifier.weight(1.2f),
                    )
                }
            } else {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    UsbKpiTile(
                        label = "EXTERNAL PERIPHERALS",
                        value = "${usb.externalPeripheralCount}",
                        subLabel = "+${usb.internalDeviceCount} fixed internal",
                        modifier = Modifier.weight(1f),
                    )
                    UsbKpiTile(
                        label = "PEAK NEGOTIATED LINK",
                        value = usb.maxExternalSpeedLabel,
                        subLabel = "Host max: ${usb.maxControllerSpeedLabel}",
                        highlightPrimary = usb.maxExternalSpeedMbps >= 5_000f,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // Type-C Port Roles & Compliance Strip
            if (usb.hasTypeCPorts) {
                Text(
                    text = "TYPE-C PORT ROLES & ALT-MODE TELEMETRY (${usb.typeCPorts.count { it.isConnected }}/${usb.typeCPorts.size} CONNECTED)",
                    style = CorewatchMonoTypography.hudSmall,
                    color = cs.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .testTag("usb_typec_ports_row"),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    for (port in usb.typeCPorts) {
                        TypeCPortTile(port = port)
                    }
                }
            } else if (!usb.hasDumpPermission) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = cs.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth().testTag("usb_typec_locked_banner"),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = "Per-Port Type-C Roles & Cable Compliance Locked (Topology Tree Active)",
                                style = MaterialTheme.typography.labelMedium,
                                color = cs.onSurface,
                            )
                            Text(
                                text = "Full /sys/bus/usb/devices dock & speed diagnostics work with zero permissions. Grant android.permission.DUMP once via ADB to unlock UsbPortManager port0..portN power/data roles:",
                                style = CorewatchMonoTypography.hudSmall,
                                color = cs.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        FilterChip(
                            selected = false,
                            onClick = onCopyAdbDumpCommand,
                            leadingIcon = {
                                Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                            },
                            label = { Text("Copy ADB Command") },
                            modifier = Modifier.testTag("copy_usb_dump_adb_btn"),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UsbKpiTile(
    label: String,
    value: String,
    subLabel: String,
    modifier: Modifier = Modifier,
    highlightPrimary: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = cs.surfaceContainerHigh,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(text = label, style = CorewatchMonoTypography.hudSmall, color = cs.onSurfaceVariant, maxLines = 1)
            Text(
                text = value,
                style = CorewatchMonoTypography.hudValue,
                color = if (highlightPrimary) cs.primary else cs.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subLabel,
                style = CorewatchMonoTypography.hudSmall,
                color = cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TypeCPortTile(port: TypeCPortStatus) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(9.dp),
        color = if (port.hasWarning) cs.errorContainer else cs.surfaceContainerHigh,
        modifier = Modifier.widthIn(min = 210.dp, max = 300.dp).testTag("typec_port_${port.portId}"),
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = port.portId.uppercase(Locale.US),
                    style = CorewatchMonoTypography.hudValue,
                    color = if (port.hasWarning) cs.onErrorContainer else cs.primary,
                )
                Spacer(Modifier.width(8.dp))
                Surface(
                    shape = RoundedCornerShape(5.dp),
                    color = when {
                        port.hasWarning -> cs.error
                        port.isConnected -> cs.secondaryContainer
                        else -> cs.surfaceVariant
                    },
                ) {
                    Text(
                        text = port.roleBadgeLabel,
                        style = CorewatchMonoTypography.hudSmall,
                        color = when {
                            port.hasWarning -> cs.onError
                            port.isConnected -> cs.onSecondaryContainer
                            else -> cs.onSurfaceVariant
                        },
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            val metaParts = buildList {
                add(port.supportedModes)
                if (port.isConnected) add("Data: ${port.usbDataStatus}")
                port.connectedDurationLabel?.let { add("Up $it") }
            }.joinToString(" · ")
            Text(
                text = metaParts,
                style = CorewatchMonoTypography.hudSmall,
                color = if (port.hasWarning) cs.onErrorContainer else cs.onSurfaceVariant,
            )
            port.displayPortAltModeSummary?.let { dp ->
                Text(
                    text = dp,
                    style = CorewatchMonoTypography.hudSmall,
                    color = cs.primary,
                )
            }
            if (port.isPowerTransferLimited || port.complianceWarnings.isNotEmpty()) {
                val warnText = buildList {
                    if (port.isPowerTransferLimited) add("Power Transfer Limited")
                    addAll(port.complianceWarnings)
                }.joinToString(" · ")
                Text(
                    text = "⚠️ $warnText",
                    style = CorewatchMonoTypography.hudSmall,
                    color = cs.onErrorContainer,
                )
            }
        }
    }
}

@Composable
private fun UsbTopologyTreeCard(
    state: CorewatchUiState,
    usb: com.droid.corewatch.data.UsbPeripheralTelemetrySnapshot,
    visibleRows: List<VisibleUsbTreeRow>,
    selectedNodeId: String?,
    onSelectNode: (String) -> Unit,
    onToggleCollapse: (String) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onCategoryFilterChange: (UsbCategoryFilter) -> Unit,
    onToggleInternal: () -> Unit,
    onSearchFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = cs.surfaceContainer,
        modifier = modifier.testTag("usb_topology_tree_card"),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.AccountTree, contentDescription = null, tint = cs.primary, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("USB Bus & Dock Topology", style = MaterialTheme.typography.titleMedium)
                }
                FilterChip(
                    selected = state.showInternalUsbDevices,
                    onClick = onToggleInternal,
                    label = {
                        Text(
                            if (state.showInternalUsbDevices) {
                                "Internal Included (${usb.internalDeviceCount})"
                            } else {
                                "Include Internal (${usb.internalDeviceCount})"
                            },
                        )
                    },
                    modifier = Modifier.testTag("usb_toggle_internal_chip"),
                )
            }

            OutlinedTextField(
                value = state.usbSearchQuery,
                onValueChange = onSearchQueryChange,
                singleLine = true,
                leadingIcon = {
                    Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                },
                trailingIcon = if (state.usbSearchQuery.isNotEmpty()) {
                    {
                        IconButton(onClick = { onSearchQueryChange("") }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Outlined.Close, contentDescription = "Clear search", modifier = Modifier.size(14.dp))
                        }
                    }
                } else {
                    null
                },
                placeholder = {
                    Text(
                        "Filter by device, vendor, VID:PID, port, or kernel driver...",
                        style = CorewatchMonoTypography.hudSmall,
                    )
                },
                textStyle = CorewatchMonoTypography.hudValue,
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { onSearchFocusChanged(it.isFocused) }
                    .testTag("usb_search_input"),
            )

            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (filter in UsbCategoryFilter.entries) {
                    FilterChip(
                        selected = state.usbCategoryFilter == filter,
                        onClick = { onCategoryFilterChange(filter) },
                        label = { Text(filter.label) },
                        modifier = Modifier.testTag("usb_filter_${filter.name}"),
                    )
                }
            }

            if (visibleRows.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = cs.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth().testTag("usb_empty_tree_state"),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = if (!state.showInternalUsbDevices && usb.internalDeviceCount > 0 && state.usbCategoryFilter == UsbCategoryFilter.All && state.usbSearchQuery.isBlank()) {
                                "No external USB peripherals currently attached."
                            } else {
                                "No USB nodes match the active filter."
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = cs.onSurface,
                        )
                        Text(
                            text = if (!state.showInternalUsbDevices && usb.internalDeviceCount > 0) {
                                "${usb.internalDeviceCount} fixed internal motherboard USB device(s) (such as webcam or security MCU) are hidden by External Only mode. Toggle Include Internal (Ctrl+Shift+I) to inspect them."
                            } else {
                                "Connect a USB-C dock, hub, storage drive, Ethernet adapter, DAC, or input peripheral to inspect its live bus tree."
                            },
                            style = CorewatchMonoTypography.hudSmall,
                            color = cs.onSurfaceVariant,
                        )
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    for (row in visibleRows) {
                        val node = row.node
                        val isSelected = node.id == selectedNodeId
                        val rowBg = when {
                            isSelected -> cs.primaryContainer.copy(alpha = 0.65f)
                            node.healthStatus.isWarning -> cs.errorContainer.copy(alpha = 0.45f)
                            else -> cs.surfaceContainerHigh
                        }
                        val borderMod = if (isSelected) {
                            Modifier.border(1.dp, cs.primary, RoundedCornerShape(8.dp))
                        } else if (node.healthStatus.isWarning) {
                            Modifier.border(1.dp, cs.error, RoundedCornerShape(8.dp))
                        } else {
                            Modifier
                        }
                        Surface(
                            onClick = { onSelectNode(node.id) },
                            shape = RoundedCornerShape(8.dp),
                            color = rowBg,
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(borderMod)
                                .testTag("usb_node_${node.id}"),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 7.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (row.treePrefix.isNotEmpty()) {
                                        Text(
                                            text = row.treePrefix,
                                            style = CorewatchMonoTypography.hudValue,
                                            color = cs.outline,
                                            maxLines = 1,
                                            softWrap = false,
                                        )
                                    }
                                    if (row.hasChildrenInScope) {
                                        IconButton(
                                            onClick = { onToggleCollapse(node.id) },
                                            modifier = Modifier.size(20.dp).testTag("usb_collapse_${node.id}"),
                                        ) {
                                            Icon(
                                                imageVector = if (row.isCollapsed) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess,
                                                contentDescription = if (row.isCollapsed) "Expand hub" else "Collapse hub",
                                                tint = cs.onSurfaceVariant,
                                                modifier = Modifier.size(15.dp),
                                            )
                                        }
                                        Spacer(Modifier.width(2.dp))
                                    }
                                    Icon(
                                        imageVector = usbCategoryIcon(node.category),
                                        contentDescription = null,
                                        tint = when {
                                            node.healthStatus.isWarning -> cs.error
                                            node.isRootController || node.category == UsbNodeCategory.Dock -> cs.primary
                                            node.isInternal -> cs.onSurfaceVariant
                                            else -> cs.secondary
                                        },
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(Modifier.width(7.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = node.displayTitle,
                                            style = CorewatchMonoTypography.hudValue,
                                            color = cs.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        val subText = if (node.isRootController) {
                                            "${node.speedTierDescription} · ${node.lanesLabel}"
                                        } else {
                                            val intTag = if (node.isInternal) " · [Internal]" else ""
                                            "${node.portChain} · ${node.vidPidHex} · ${node.vendorName}$intTag"
                                        }
                                        Text(
                                            text = subText,
                                            style = CorewatchMonoTypography.hudSmall,
                                            color = cs.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }

                                Spacer(Modifier.width(8.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                                ) {
                                    if (node.healthStatus.isWarning) {
                                        Icon(
                                            Icons.Outlined.WarningAmber,
                                            contentDescription = node.healthStatus.badgeLabel,
                                            tint = cs.error,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(5.dp),
                                        color = when {
                                            node.healthStatus.isWarning -> cs.errorContainer
                                            node.negotiatedSpeedMbps >= 5_000f -> cs.primaryContainer
                                            else -> cs.surfaceVariant
                                        },
                                    ) {
                                        Text(
                                            text = node.speedBadgeLabel,
                                            style = CorewatchMonoTypography.hudSmall,
                                            color = when {
                                                node.healthStatus.isWarning -> cs.onErrorContainer
                                                node.negotiatedSpeedMbps >= 5_000f -> cs.onPrimaryContainer
                                                else -> cs.onSurfaceVariant
                                            },
                                            maxLines = 1,
                                            softWrap = false,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UsbNodeDetailsCard(
    node: UsbTopologyNode?,
    allNodes: List<UsbTopologyNode>,
    onSelectCompanionNode: (String) -> Unit,
    onJumpToTab: (CorewatchTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = cs.surfaceContainer,
        modifier = modifier.testTag("usb_node_details_card"),
    ) {
        if (node == null) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Node & Endpoint Inspector", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "Select any xHCI controller, hub, dock, or peripheral in the topology tree to inspect negotiated lanes, bMaxPower draw, kernel drivers, and correlated Android capabilities.",
                    style = CorewatchMonoTypography.hudSmall,
                    color = cs.onSurfaceVariant,
                )
            }
        } else {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = node.displayTitle,
                            style = MaterialTheme.typography.titleMedium,
                            color = cs.onSurface,
                        )
                        Text(
                            text = "${node.category.label} · VID:PID ${node.vidPidHex} · ${node.vendorName}",
                            style = CorewatchMonoTypography.hudSmall,
                            color = cs.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (node.healthStatus.isWarning) cs.errorContainer else cs.secondaryContainer,
                    ) {
                        Text(
                            text = node.healthStatus.badgeLabel,
                            style = CorewatchMonoTypography.hudSmall,
                            color = if (node.healthStatus.isWarning) cs.onErrorContainer else cs.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }

                // Bottleneck / Billboard / Power Alert Banner
                node.healthMessage?.let { msg ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(cs.errorContainer)
                            .border(1.dp, cs.error, RoundedCornerShape(8.dp))
                            .padding(10.dp)
                            .testTag("usb_node_warning_banner"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Outlined.WarningAmber,
                            contentDescription = null,
                            tint = cs.onErrorContainer,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(text = msg, style = MaterialTheme.typography.bodySmall, color = cs.onErrorContainer)
                    }
                }

                node.passiveNote?.let { note ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = cs.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = note,
                            style = CorewatchMonoTypography.hudSmall,
                            color = cs.onSurfaceVariant,
                            modifier = Modifier.padding(9.dp),
                        )
                    }
                }

                // Link, Lanes & Power Telemetry Box
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = cs.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "LINK SPEED, TOPOLOGY & POWER BUDGET",
                            style = CorewatchMonoTypography.hudSmall,
                            color = cs.primary,
                        )
                        Text(
                            text = "Negotiated Rate: ${node.speedTierDescription} · Lanes: ${node.lanesLabel}",
                            style = CorewatchMonoTypography.hudValue,
                            color = cs.onSurface,
                        )
                        val locScope = if (node.isInternal) "Internal Fixed Port" else "External Port"
                        val pciStr = if (node.pciControllerLabel.isNotBlank()) " · Host ${node.pciControllerLabel}" else ""
                        Text(
                            text = "Descriptor USB Spec: bcdUSB ${node.usbVersionBcd} · Path: ${node.portChain}$pciStr",
                            style = CorewatchMonoTypography.hudSmall,
                            color = cs.onSurfaceVariant,
                        )
                        val downstreamPart = if (node.isHubOrRoot && node.downstreamRequestedMa > 0) {
                            " · Downstream Bus Draw: ${node.downstreamRequestedMa} mA"
                        } else {
                            ""
                        }
                        Text(
                            text = "Power: ${node.powerSummaryLabel}$downstreamPart · $locScope (${node.removableRaw})",
                            style = CorewatchMonoTypography.hudSmall,
                            color = cs.onSurfaceVariant,
                        )
                    }
                }

                // Companion Hub / Bus & Cross-Tab Jump Actions
                val companionNode = node.companionNodeId?.let { cid -> allNodes.firstOrNull { it.id == cid } }
                val isBluetoothUsbController = node.category == UsbNodeCategory.WirelessController && (
                    node.displayTitle.contains("bluetooth", ignoreCase = true) ||
                        node.interfaces.any { it.classCode == 0xe0 || it.driverName.contains("btusb", ignoreCase = true) }
                    )
                val showNetJump = node.category == UsbNodeCategory.NetworkAdapter ||
                    (node.category == UsbNodeCategory.WirelessController && !isBluetoothUsbController)
                val showBtJump = isBluetoothUsbController
                val showDispJump = node.category == UsbNodeCategory.Dock || node.category == UsbNodeCategory.Billboard
                if (companionNode != null || showNetJump || showBtJump || showDispJump) {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (companionNode != null) {
                            FilterChip(
                                selected = false,
                                onClick = { onSelectCompanionNode(companionNode.id) },
                                leadingIcon = {
                                    Icon(Icons.Outlined.Cable, contentDescription = null, modifier = Modifier.size(14.dp))
                                },
                                label = {
                                    Text("Companion ${companionNode.id} (${companionNode.speedBadgeLabel}) ->")
                                },
                                modifier = Modifier.testTag("usb_jump_companion_btn"),
                            )
                        }
                        if (showNetJump) {
                            FilterChip(
                                selected = false,
                                onClick = { onJumpToTab(CorewatchTab.Network) },
                                leadingIcon = {
                                    Icon(Icons.Outlined.SettingsEthernet, contentDescription = null, modifier = Modifier.size(14.dp))
                                },
                                label = { Text("Inspect on Network ->") },
                                modifier = Modifier.testTag("usb_jump_network_tab_btn"),
                            )
                        }
                        if (showBtJump) {
                            FilterChip(
                                selected = false,
                                onClick = { onJumpToTab(CorewatchTab.Bluetooth) },
                                leadingIcon = {
                                    Icon(Icons.Outlined.Bluetooth, contentDescription = null, modifier = Modifier.size(14.dp))
                                },
                                label = { Text("Inspect on Bluetooth ->") },
                                modifier = Modifier.testTag("usb_jump_bluetooth_tab_btn"),
                            )
                        }
                        if (showDispJump) {
                            FilterChip(
                                selected = false,
                                onClick = { onJumpToTab(CorewatchTab.Displays) },
                                leadingIcon = {
                                    Icon(Icons.Outlined.DesktopWindows, contentDescription = null, modifier = Modifier.size(14.dp))
                                },
                                label = { Text("Inspect on Displays ->") },
                                modifier = Modifier.testTag("usb_jump_displays_tab_btn"),
                            )
                        }
                    }
                }

                // Correlated Android Subsystem Capabilities (InputManager / AudioManager / CameraManager / StorageManager)
                val corr = node.correlatedInfo
                if (corr.hasAnyCorrelation) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = cs.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth().testTag("usb_correlated_capabilities_box"),
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "ANDROID SUBSYSTEM CORRELATION",
                                style = CorewatchMonoTypography.hudSmall,
                                color = cs.secondary,
                            )
                            if (corr.isPresentInUsbManager) {
                                Text(
                                    text = "UsbManager Host Device: ${corr.usbManagerConfigCount} config(s) · ${corr.usbManagerEndpointTotal} total endpoint(s)",
                                    style = CorewatchMonoTypography.hudSmall,
                                    color = cs.onSurface,
                                )
                            }
                            corr.inputSourcesSummary?.let { src ->
                                val battSuffix = if (corr.inputBatteryPercent in 0..100) " · Battery: ${corr.inputBatteryPercent}%" else ""
                                Text(
                                    text = "InputManager: $src$battSuffix",
                                    style = CorewatchMonoTypography.hudSmall,
                                    color = cs.onSurface,
                                )
                            }
                            corr.audioCapabilitiesSummary?.let { aud ->
                                Text(
                                    text = "AudioManager: $aud",
                                    style = CorewatchMonoTypography.hudSmall,
                                    color = cs.onSurface,
                                )
                            }
                            corr.cameraSummary?.let { cam ->
                                Text(
                                    text = "CameraManager: $cam",
                                    style = CorewatchMonoTypography.hudSmall,
                                    color = cs.onSurface,
                                )
                            }
                            corr.storageVolumeSummary?.let { vol ->
                                Text(
                                    text = "StorageManager: $vol",
                                    style = CorewatchMonoTypography.hudSmall,
                                    color = cs.onSurface,
                                )
                            }
                        }
                    }
                }

                // USB Interfaces & Kernel Drivers Table
                Text(
                    text = "USB INTERFACES & KERNEL DRIVERS (${node.interfaces.size})",
                    style = CorewatchMonoTypography.hudSmall,
                    color = cs.onSurfaceVariant,
                )
                if (node.interfaces.isEmpty()) {
                    Text(
                        text = if (node.isRootController) {
                            "Root xHCI Host Controller bus hub managed by kernel xhci_hcd."
                        } else {
                            "Device-level descriptor (Class 0x${String.format(Locale.US, "%02x", node.deviceClassCode)})."
                        },
                        style = CorewatchMonoTypography.hudSmall,
                        color = cs.onSurfaceVariant,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        for (iface in node.interfaces) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = cs.surfaceContainerHigh,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        val lblSuffix = if (iface.interfaceLabel.isNotBlank()) " · ${iface.interfaceLabel}" else ""
                                        Text(
                                            text = "If #${iface.interfaceNumber} · ${iface.classLabel}$lblSuffix",
                                            style = CorewatchMonoTypography.hudValue,
                                            color = cs.onSurface,
                                        )
                                        Text(
                                            text = "Class ${iface.classTripleHex} · Endpoints: ${iface.endpointCount}",
                                            style = CorewatchMonoTypography.hudSmall,
                                            color = cs.onSurfaceVariant,
                                        )
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(5.dp),
                                        color = cs.surfaceVariant,
                                    ) {
                                        Text(
                                            text = "drv=${iface.driverName}",
                                            style = CorewatchMonoTypography.hudSmall,
                                            color = cs.primary,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UsbHotplugTimelineCard(
    events: List<UsbHotplugEvent>,
    onSelectNode: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = cs.surfaceContainer,
        modifier = Modifier.fillMaxWidth().testTag("usb_hotplug_timeline_card"),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Session USB Hotplug & Bottleneck Log", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "In-memory 64-event ring buffer (zero disk persistence · cleared via F5 / Ctrl+R)",
                        style = CorewatchMonoTypography.hudSmall,
                        color = cs.onSurfaceVariant,
                    )
                }
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = cs.surfaceContainerHigh,
                ) {
                    Text(
                        text = "${events.size} Event(s)",
                        style = CorewatchMonoTypography.hudSmall,
                        color = cs.primary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }

            if (events.isEmpty()) {
                Text(
                    text = "No hotplug transitions or bottleneck warnings recorded since launch. Plug or unplug any USB peripheral or dock to capture live enumeration events.",
                    style = CorewatchMonoTypography.hudSmall,
                    color = cs.onSurfaceVariant,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (ev in events.take(16)) {
                        Surface(
                            onClick = { onSelectNode(ev.nodeId) },
                            shape = RoundedCornerShape(8.dp),
                            color = if (ev.action.isAlert) cs.errorContainer.copy(alpha = 0.45f) else cs.surfaceContainerHigh,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Text(
                                            text = ev.timestampLabel,
                                            style = CorewatchMonoTypography.hudSmall,
                                            color = cs.onSurfaceVariant,
                                        )
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = when {
                                                ev.action.isAlert -> cs.error
                                                ev.action == UsbHotplugAction.Attached -> cs.secondaryContainer
                                                else -> cs.surfaceVariant
                                            },
                                        ) {
                                            Text(
                                                text = ev.action.badgeLabel,
                                                style = CorewatchMonoTypography.hudSmall,
                                                color = when {
                                                    ev.action.isAlert -> cs.onError
                                                    ev.action == UsbHotplugAction.Attached -> cs.onSecondaryContainer
                                                    else -> cs.onSurfaceVariant
                                                },
                                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                            )
                                        }
                                        Text(
                                            text = ev.deviceName,
                                            style = CorewatchMonoTypography.hudValue,
                                            color = cs.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    Text(
                                        text = ev.detail,
                                        style = CorewatchMonoTypography.hudSmall,
                                        color = if (ev.action.isAlert) cs.onErrorContainer else cs.onSurfaceVariant,
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = ev.speedBadgeLabel,
                                    style = CorewatchMonoTypography.hudSmall,
                                    color = cs.primary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun usbCategoryIcon(category: UsbNodeCategory) = when (category) {
    UsbNodeCategory.RootController, UsbNodeCategory.Hub -> Icons.Outlined.AccountTree
    UsbNodeCategory.Dock -> Icons.Outlined.Cable
    UsbNodeCategory.NetworkAdapter -> Icons.Outlined.SettingsEthernet
    UsbNodeCategory.SerialAdapter -> Icons.Outlined.Cable
    UsbNodeCategory.WirelessController -> Icons.Outlined.Wifi
    UsbNodeCategory.Keyboard -> Icons.Outlined.Keyboard
    UsbNodeCategory.Pointer -> Icons.Outlined.Mouse
    UsbNodeCategory.GameController -> Icons.Outlined.SportsEsports
    UsbNodeCategory.HidControl -> Icons.Outlined.Usb
    UsbNodeCategory.Audio -> Icons.Outlined.Headphones
    UsbNodeCategory.VideoWebcam -> Icons.Outlined.Videocam
    UsbNodeCategory.Storage -> Icons.Outlined.Storage
    UsbNodeCategory.SecurityBiometric -> Icons.Outlined.Security
    UsbNodeCategory.Billboard -> Icons.Outlined.WarningAmber
    UsbNodeCategory.Other -> Icons.Outlined.Usb
}

// ------------------------------------------------------------------------ F1 Shortcuts Dialog

@Composable
private fun ShortcutsDialog(onDismiss: () -> Unit) {
    val shortcuts = listOf(
        "Ctrl+M" to "Toggle between Full Workbench and Compact Mini-HUD mode",
        "Ctrl+1 .. Ctrl+7" to "Switch tabs (Compute, Battery, Memory, Display(s), Network, Bluetooth, USB & Peripherals)",
        "Ctrl+K" to "Toggle Caffeine Keep-Awake active state",
        "Space" to "Pause / Resume live telemetry graph scrolling",
        "Ctrl+Shift+C" to "Copy Markdown Hardware & Thermal Diagnostic Snapshot",
        "Ctrl+Shift+S" to "Trigger an active Wi-Fi scan on Network",
        "Ctrl+Shift+B" to "Toggle surrounding AP BSSID MAC privacy masking",
        "Ctrl+Shift+I" to "Toggle internal motherboard USB devices on USB & Peripherals",
        "F5 / Ctrl+R" to "Reset session min/max/average & USB hotplug counters",
        "F1" to "Show / hide this Keyboard Shortcuts cheatsheet",
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Corewatch Keyboard Shortcuts") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((key, desc) in shortcuts) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(key, style = CorewatchMonoTypography.hudValue, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Text(desc, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

@Composable
private fun thermalColor(level: ThermalLevel): Color {
    val cs = MaterialTheme.colorScheme
    return when (level) {
        ThermalLevel.NONE -> cs.secondary
        ThermalLevel.LIGHT, ThermalLevel.MODERATE -> cs.tertiary
        else -> cs.error
    }
}

private fun formatTemp(celsius: Float, useFahrenheit: Boolean): String =
    if (useFahrenheit) {
        String.format(Locale.US, "%.1f °F", celsius * 9f / 5f + 32f)
    } else {
        String.format(Locale.US, "%.1f °C", celsius)
    }
