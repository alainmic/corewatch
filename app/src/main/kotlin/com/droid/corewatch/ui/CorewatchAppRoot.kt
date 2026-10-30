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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Coffee
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PhotoSizeSelectSmall
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
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
import com.droid.corewatch.data.CaffeineDuration
import com.droid.corewatch.data.CoreCluster
import com.droid.corewatch.data.DiagnosticReportBuilder
import com.droid.corewatch.data.DisplaySpec
import com.droid.corewatch.data.ThermalLevel
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
    onResizeWindowForMode: (Boolean) -> Unit = {},
) {
    val state by vm.state.collectAsState()
    val focusRequester = remember { FocusRequester() }

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
                        vm.selectTab(CorewatchTab.DisplaysNetwork); true
                    }
                    !ev.isCtrlPressed && ev.key == Key.Spacebar -> {
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
                )
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    CorewatchTitleBar(state = state, vm = vm, cap = cap)
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        when (state.activeTab) {
                            CorewatchTab.OverviewThermals -> OverviewThermalsTab(state = state, vm = vm)
                            CorewatchTab.BatteryPower -> BatteryPowerTab(state = state, vm = vm)
                            CorewatchTab.MemoryStorage -> MemoryStorageTab(state = state)
                            CorewatchTab.DisplaysNetwork -> DisplaysNetworkTab(state = state)
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
        val splitTwoRowHeader = hasDesktopCaption || usableCaptionWidth < 435.dp
        val showBrandText = usableCaptionWidth >= 560.dp || cap.start == 0.dp || splitTwoRowHeader
        val useFullTabTitles = if (splitTwoRowHeader) totalWidth >= 680.dp else usableCaptionWidth >= 720.dp
        val showWattagePill = usableCaptionWidth >= 820.dp || splitTwoRowHeader
        val showThermalPill = usableCaptionWidth >= 420.dp || splitTwoRowHeader
        val showCaffeineLabel = if (splitTwoRowHeader) totalWidth >= 560.dp else usableCaptionWidth >= 640.dp
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
                        CorewatchTabStrip(
                            activeTab = state.activeTab,
                            useFullTabTitles = useFullTabTitles,
                            onSelectTab = vm::selectTab,
                        )
                    }
                }

                if (!splitTwoRowHeader) {
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
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(if (useFullTabTitles) 4.dp else 2.dp),
                    ) {
                        CorewatchTabStrip(
                            activeTab = state.activeTab,
                            useFullTabTitles = useFullTabTitles,
                            onSelectTab = vm::selectTab,
                        )
                    }
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
    useFullTabTitles: Boolean,
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
                text = if (useFullTabTitles) tab.shortTitle else tab.compactTitle,
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) cs.onPrimaryContainer else cs.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.padding(
                    horizontal = if (useFullTabTitles) 9.dp else 7.dp,
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
    onExpandToFull: () -> Unit = { vm.setMiniHud(false) },
    onDragOverlay: ((Float, Float) -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val snap = state.snapshot
    val hist = state.history
    val caffeineActive = snap.caffeineEvaluation.isActive
    val density = LocalDensity.current
    val hasDesktopCaption = cap.start > 0.dp || cap.end > 0.dp
    val statusTopDp = if (!hasDesktopCaption && onDragOverlay == null) {
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

        // Interactive Mini-HUD Control Bar (below OS caption when in freeform window; draggable when in PiP overlay)
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
                .padding(horizontal = 8.dp, vertical = 4.dp),
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
                    text = "PiP HUD",
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

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .widthIn(max = 460.dp)
                .align(Alignment.CenterHorizontally)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
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
                title = "GPU & NPU (${snap.gpu.driverName} · ${snap.npu.driverName})",
                primaryValue = String.format(
                    Locale.US,
                    "GPU %d%% (%d MHz) · NPU %d%%",
                    (snap.gpu.utilizationFraction * 100f).roundToInt(),
                    snap.gpu.actualFreqMhz,
                    (snap.npu.utilizationFraction * 100f).roundToInt(),
                ),
                subValue = String.format(
                    Locale.US,
                    "GPU %s · NPU %s (%d MHz max)",
                    snap.gpu.scalingStateLabel,
                    snap.npu.scalingStateLabel,
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

            // 3. Battery Wattage & Time Remaining Sparkline Row
            MiniHudSparklineCard(
                title = "BATTERY (${snap.battery.levelPercent}% · ${snap.battery.powerSource.label})",
                primaryValue = String.format(Locale.US, "%+.1f W", snap.battery.watts),
                subValue = String.format(Locale.US, "%d mV · Peak %.1f W", snap.battery.voltageMv, hist.batteryPeakWatts),
                samples = hist.batteryWattsSamples,
                lineColor = if (snap.battery.isCharging) cs.secondary else cs.tertiary,
                maxCeiling = 65f,
                cardAlpha = cardAlpha,
            )

            // 4. Memory / zram Swap + Network Throughput Row
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
                    "Swap %d%% · Net ↓%.1f ↑%.1f Mbps",
                    (snap.memory.swapUsedFraction * 100f).roundToInt(),
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
        Column(modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)) {
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
            Spacer(Modifier.height(2.dp))
            SparklineCanvas(
                samples = samples,
                lineColor = lineColor,
                gridColor = cs.outlineVariant.copy(alpha = 0.35f),
                minFloor = minFloor,
                maxCeiling = maxCeiling,
                modifier = Modifier.fillMaxWidth().height(21.dp),
            )
            Spacer(Modifier.height(2.dp))
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
                            "Arch: %s (%d Cores) · Session Avg %.1f%% · Peak %.1f%%",
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
                                snap.gpu.actualFreqMhz,
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
                    Text(
                        text = String.format(
                            Locale.US,
                            "Target: %d MHz · Range: %d–%d MHz (RPe %d MHz) · Throttle: %s",
                            snap.gpu.requestedFreqMhz,
                            snap.gpu.minFreqMhz,
                            snap.gpu.maxFreqMhz,
                            snap.gpu.efficientFreqMhz,
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
                            "Max DPU Clock: %d MHz · Contexts: %d · Heartbeat: %d · Peak: %.1f%%",
                            snap.npu.maxFreqMhz,
                            snap.npu.maxContexts,
                            snap.npu.engineHeartbeat,
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
            modifier = Modifier.fillMaxWidth(),
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
                            "%s used of %s (%s free)",
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
}

// ------------------------------------------------------------------------ Tab 4: Displays & Network

@Composable
private fun DisplaysNetworkTab(state: CorewatchUiState) {
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
        Text(
            text = "Connected Displays (${snap.displays.size} total · ${snap.externalDisplayCount} external)",
            style = MaterialTheme.typography.titleMedium,
        )

        for (disp in snap.displays) {
            DisplayCard(disp = disp)
        }

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
                    Text("Network Throughput (TrafficStats RX / TX)", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = String.format(Locale.US, "↓ %.2f Mbps · ↑ %.2f Mbps", snap.network.rxMbps, snap.network.txMbps),
                        style = CorewatchMonoTypography.hudValue,
                        color = cs.primary,
                    )
                }
                Text(
                    text = String.format(
                        Locale.US,
                        "Boot Cumulative RX: %s · Cumulative TX: %s",
                        DiagnosticReportBuilder.formatGiB(snap.network.totalRxBytes),
                        DiagnosticReportBuilder.formatGiB(snap.network.totalTxBytes),
                    ),
                    style = CorewatchMonoTypography.hudSmall,
                    color = cs.onSurfaceVariant,
                )
                SparklineCanvas(
                    samples = hist.networkMbpsSamples,
                    lineColor = cs.primary,
                    gridColor = cs.outlineVariant.copy(alpha = 0.4f),
                    minFloor = 0f,
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                )
            }
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
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.DesktopWindows, contentDescription = null, tint = cs.primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("${disp.name} (#${disp.displayId})", style = MaterialTheme.typography.titleMedium)
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
                    "%s · Logical DPI: %d (%d%% scale) · Physical DPI: %.0f×%.0f · HDR: %s",
                    disp.connectionLabel,
                    disp.logicalDpi,
                    disp.scalePercent,
                    disp.physicalXDpi,
                    disp.physicalYDpi,
                    if (disp.hdrFormats.isEmpty()) "SDR only" else disp.hdrFormats.joinToString(", "),
                ),
                style = CorewatchMonoTypography.hudSmall,
                color = cs.onSurfaceVariant,
            )

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

// ------------------------------------------------------------------------ F1 Shortcuts Dialog

@Composable
private fun ShortcutsDialog(onDismiss: () -> Unit) {
    val shortcuts = listOf(
        "Ctrl+M" to "Toggle between Full Workbench and Compact Mini-HUD mode",
        "Ctrl+1 .. Ctrl+4" to "Switch tabs (Thermals, Battery, Memory, Displays)",
        "Ctrl+K" to "Toggle Caffeine Keep-Awake active state",
        "Space" to "Pause / Resume live telemetry graph scrolling",
        "Ctrl+Shift+C" to "Copy Markdown Hardware & Thermal Diagnostic Snapshot",
        "F5 / Ctrl+R" to "Reset session min/max/average telemetry counters",
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
