// Copyright 2026 The Corewatch Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.corewatch.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.droid.corewatch.data.CaffeineConfig
import com.droid.corewatch.data.CaffeineDuration
import com.droid.corewatch.data.DiagnosticReportBuilder
import com.droid.corewatch.data.FloatRingBuffer
import com.droid.corewatch.data.SystemTelemetrySnapshot
import com.droid.corewatch.data.TelemetryCollector
import com.droid.corewatch.data.UsbCategoryFilter
import com.droid.corewatch.data.WifiApBandFilter
import com.droid.corewatch.data.WifiApSortOrder
import com.droid.corewatch.service.CaffeineController
import com.droid.corewatch.ui.theme.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

enum class CorewatchTab(
    val index: Int,
    val shortTitle: String,
    val compactTitle: String,
    val ultraCompactTitle: String,
    val fullTitle: String,
    val shortcutLabel: String,
) {
    OverviewThermals(0, "Thermals & Compute", "Compute", "Compute", "Thermals & Compute", "Ctrl+1"),
    BatteryPower(1, "Battery & W", "Battery", "Battery", "Battery & Power", "Ctrl+2"),
    MemoryStorage(2, "Memory & Swap", "Memory", "Memory", "Memory & Storage", "Ctrl+3"),
    Displays(3, "Display", "Display", "Display", "Displays", "Ctrl+4"),
    NetworkBluetooth(4, "Network & Bluetooth", "Net & Bluetooth", "Net & BT", "Network & Bluetooth", "Ctrl+5"),
    UsbPeripherals(5, "USB & Peripherals", "USB & I/O", "USB", "USB & Peripherals", "Ctrl+6");

    fun labelForDisplayCount(displayCount: Int, compact: Boolean, ultraCompact: Boolean = false): String = when (this) {
        Displays -> if (displayCount > 1) "Displays" else "Display"
        else -> when {
            ultraCompact -> ultraCompactTitle
            compact -> compactTitle
            else -> shortTitle
        }
    }
}

enum class SamplingInterval(val delayMs: Long, val label: String) {
    FAST_250MS(250L, "250ms"),
    FAST_500MS(500L, "500ms"),
    NORMAL_1S(1_000L, "1s"),
    SLOW_2S(2_000L, "2s");

    fun next(): SamplingInterval = when (this) {
        FAST_250MS -> FAST_500MS
        FAST_500MS -> NORMAL_1S
        NORMAL_1S -> SLOW_2S
        SLOW_2S -> FAST_250MS
    }
}

data class TelemetryHistoryStats(
    val cpuLoadSamples: List<Float> = emptyList(),
    val cpuLoadPeak: Float = 0f,
    val cpuLoadAvg: Float = 0f,
    val gpuLoadSamples: List<Float> = emptyList(),
    val gpuLoadPeak: Float = 0f,
    val gpuLoadAvg: Float = 0f,
    val npuLoadSamples: List<Float> = emptyList(),
    val npuLoadPeak: Float = 0f,
    val npuLoadAvg: Float = 0f,
    val thermalCelsiusSamples: List<Float> = emptyList(),
    val thermalPeakCelsius: Float = 0f,
    val batteryWattsSamples: List<Float> = emptyList(),
    val batteryPeakWatts: Float = 0f,
    val batteryAvgWatts: Float = 0f,
    val memoryUsedPctSamples: List<Float> = emptyList(),
    val networkMbpsSamples: List<Float> = emptyList(),
    val wifiMbpsSamples: List<Float> = emptyList(),
    val ethernetMbpsSamples: List<Float> = emptyList(),
    val wifiSignalSamples: List<Float> = emptyList(),
    val jankPercentSamples: List<Float> = emptyList(),
)

data class CorewatchUiState(
    val activeTab: CorewatchTab = CorewatchTab.OverviewThermals,
    val isMiniHud: Boolean = false,
    val miniHudAlpha: Float = 0.88f,
    val isPaused: Boolean = false,
    val samplingInterval: SamplingInterval = SamplingInterval.NORMAL_1S,
    val useFahrenheit: Boolean = false,
    val showShortcutsDialog: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.Dark,
    val statusBanner: String? = null,
    val wifiApBandFilter: WifiApBandFilter = WifiApBandFilter.ALL,
    val wifiApSortOrder: WifiApSortOrder = WifiApSortOrder.SIGNAL_DBM,
    val maskApBssids: Boolean = true,
    val selectedUsbNodeId: String? = null,
    val usbSearchQuery: String = "",
    val usbCategoryFilter: UsbCategoryFilter = UsbCategoryFilter.All,
    val showInternalUsbDevices: Boolean = false,
    val collapsedUsbNodeIds: Set<String> = emptySet(),
    val caffeineConfig: CaffeineConfig = CaffeineConfig(),
    val snapshot: SystemTelemetrySnapshot = SystemTelemetrySnapshot(),
    val history: TelemetryHistoryStats = TelemetryHistoryStats(),
)

class CorewatchViewModel(app: Application) : AndroidViewModel(app) {
    private val collector = TelemetryCollector(app.applicationContext)
    private val cpuRing = FloatRingBuffer(60)
    private val gpuRing = FloatRingBuffer(60)
    private val npuRing = FloatRingBuffer(60)
    private val thermalRing = FloatRingBuffer(60)
    private val wattsRing = FloatRingBuffer(60)
    private val memRing = FloatRingBuffer(60)
    private val netRing = FloatRingBuffer(60)
    private val wifiMbpsRing = FloatRingBuffer(60)
    private val ethMbpsRing = FloatRingBuffer(60)
    private val wifiSignalRing = FloatRingBuffer(60)
    private val jankRing = FloatRingBuffer(60)
    private var lastWindowNarrow: Boolean = false
    @Volatile
    private var testingSnapshotPinned: Boolean = false

    private val _state = MutableStateFlow(CorewatchUiState())
    val state: StateFlow<CorewatchUiState> = _state.asStateFlow()

    init {
        CaffeineController.init(app.applicationContext)
        sampleNow()
        viewModelScope.launch {
            CaffeineController.config.collect { cfg ->
                _state.update { it.copy(caffeineConfig = cfg) }
            }
        }
        viewModelScope.launch {
            while (isActive) {
                delay(_state.value.samplingInterval.delayMs)
                if (!_state.value.isPaused && !testingSnapshotPinned) {
                    withContext(Dispatchers.Default) {
                        sampleNow()
                    }
                }
            }
        }
    }

    @Synchronized
    fun sampleNow(nowElapsedMs: Long = SystemClock.elapsedRealtime()) {
        val ctx = getApplication<Application>().applicationContext
        val cfg = CaffeineController.config.value
        val snap = collector.sample(cfg, nowElapsedMs)
        CaffeineController.syncWakeLock(ctx, snap.caffeineEvaluation.isActive)

        cpuRing.push(snap.avgCpuUtilizationFraction * 100f)
        gpuRing.push(snap.gpu.utilizationFraction * 100f)
        npuRing.push(snap.npu.utilizationFraction * 100f)
        thermalRing.push(snap.primaryCpuTempCelsius)
        wattsRing.push(abs(snap.battery.watts))
        memRing.push(snap.memory.usedFraction * 100f)
        netRing.push(snap.network.rxMbps + snap.network.txMbps)
        wifiMbpsRing.push(snap.wifi.throughput.rxMbps + snap.wifi.throughput.txMbps)
        ethMbpsRing.push(snap.ethernet.throughput.rxMbps + snap.ethernet.throughput.txMbps)
        val normalizedRssiPct = if (snap.wifi.rssiDbm > -120) {
            ((snap.wifi.rssiDbm.coerceIn(-90, -25) - (-90)).toFloat() / 65f) * 100f
        } else {
            0f
        }
        wifiSignalRing.push(normalizedRssiPct)
        jankRing.push(snap.frameJank.latestIntervalJankPercent)

        val hist = TelemetryHistoryStats(
            cpuLoadSamples = cpuRing.toList(),
            cpuLoadPeak = cpuRing.maxOrNull() ?: 0f,
            cpuLoadAvg = cpuRing.average(),
            gpuLoadSamples = gpuRing.toList(),
            gpuLoadPeak = gpuRing.maxOrNull() ?: 0f,
            gpuLoadAvg = gpuRing.average(),
            npuLoadSamples = npuRing.toList(),
            npuLoadPeak = npuRing.maxOrNull() ?: 0f,
            npuLoadAvg = npuRing.average(),
            thermalCelsiusSamples = thermalRing.toList(),
            thermalPeakCelsius = thermalRing.maxOrNull() ?: snap.primaryCpuTempCelsius,
            batteryWattsSamples = wattsRing.toList(),
            batteryPeakWatts = wattsRing.maxOrNull() ?: abs(snap.battery.watts),
            batteryAvgWatts = wattsRing.average(),
            memoryUsedPctSamples = memRing.toList(),
            networkMbpsSamples = netRing.toList(),
            wifiMbpsSamples = wifiMbpsRing.toList(),
            ethernetMbpsSamples = ethMbpsRing.toList(),
            wifiSignalSamples = wifiSignalRing.toList(),
            jankPercentSamples = jankRing.toList(),
        )

        _state.update {
            it.copy(
                caffeineConfig = cfg,
                snapshot = snap,
                history = hist,
            )
        }
    }

    fun selectTab(tab: CorewatchTab) {
        _state.update { it.copy(activeTab = tab) }
    }

    fun toggleMiniHud() {
        _state.update { it.copy(isMiniHud = !it.isMiniHud) }
    }

    fun setMiniHud(mini: Boolean) {
        _state.update { it.copy(isMiniHud = mini) }
    }

    fun onWindowNarrowChanged(isNarrow: Boolean) {
        if (isNarrow != lastWindowNarrow) {
            lastWindowNarrow = isNarrow
            _state.update { it.copy(isMiniHud = isNarrow) }
        }
    }

    fun cycleMiniHudOpacity() {
        _state.update {
            val next = when {
                it.miniHudAlpha > 0.94f -> 0.88f
                it.miniHudAlpha > 0.80f -> 0.75f
                else -> 1.0f
            }
            it.copy(miniHudAlpha = next)
        }
    }

    fun togglePaused() {
        _state.update {
            val next = !it.isPaused
            it.copy(
                isPaused = next,
                statusBanner = if (next) "Live telemetry paused (Press Space to resume)" else null,
            )
        }
    }

    fun toggleTemperatureUnit() {
        _state.update { it.copy(useFahrenheit = !it.useFahrenheit) }
    }

    fun cycleTheme() {
        _state.update {
            val next = when (it.themeMode) {
                ThemeMode.Dark -> ThemeMode.Light
                ThemeMode.Light -> ThemeMode.System
                ThemeMode.System -> ThemeMode.Dark
            }
            it.copy(themeMode = next)
        }
    }

    fun setShortcutsDialogVisible(visible: Boolean) {
        _state.update { it.copy(showShortcutsDialog = visible) }
    }

    fun dismissBanner() {
        _state.update { it.copy(statusBanner = null) }
    }

    fun toggleCaffeineQuick() {
        val ctx = getApplication<Application>().applicationContext
        CaffeineController.toggleQuick(ctx)
        sampleNow()
    }

    fun setCaffeineDuration(duration: CaffeineDuration) {
        val ctx = getApplication<Application>().applicationContext
        CaffeineController.setDuration(ctx, duration)
        sampleNow()
    }

    fun setCaffeineAutoRules(autoOnAc: Boolean, autoOnExtDisplay: Boolean) {
        val ctx = getApplication<Application>().applicationContext
        CaffeineController.setAutoRules(ctx, autoOnAcCharging = autoOnAc, autoOnExternalDisplay = autoOnExtDisplay)
        sampleNow()
    }

    fun cycleSamplingInterval() {
        _state.update {
            val next = it.samplingInterval.next()
            it.copy(samplingInterval = next)
        }
    }

    fun setSamplingInterval(interval: SamplingInterval) {
        _state.update { it.copy(samplingInterval = interval) }
    }

    fun recordFrameMetric(
        totalDurationNs: Long,
        deadlineNs: Long,
        gpuDurationNs: Long,
        intendedVsyncNs: Long,
        vsyncNs: Long,
        cpuStagesDurationNs: Long = -1L,
        nowElapsedMs: Long = SystemClock.elapsedRealtime(),
    ) {
        val fallbackHz = _state.value.snapshot.displays.firstOrNull()?.renderFrameRateHz ?: 120f
        collector.vrrAndFrameTracker.recordFrameMetric(
            nowElapsedMs = nowElapsedMs,
            totalDurationNs = totalDurationNs,
            deadlineNs = deadlineNs,
            gpuDurationNs = gpuDurationNs,
            intendedVsyncNs = intendedVsyncNs,
            vsyncNs = vsyncNs,
            fallbackHz = fallbackHz,
            cpuStagesDurationNs = cpuStagesDurationNs,
        )
    }

    fun onTrimMemoryLevel(level: Int) {
        collector.lastTrimLevelLabel = when {
            level >= 80 -> "Complete ($level)"
            level >= 60 -> "Moderate ($level)"
            level >= 40 -> "Background ($level)"
            level == 20 -> "UI Hidden (20)"
            level >= 15 -> "Running Critical (15)"
            level >= 10 -> "Running Low (10)"
            level >= 5 -> "Running Moderate (5)"
            else -> "Normal"
        }
    }

    fun resetSessionHistory() {
        cpuRing.clear()
        gpuRing.clear()
        npuRing.clear()
        thermalRing.clear()
        wattsRing.clear()
        memRing.clear()
        netRing.clear()
        wifiMbpsRing.clear()
        ethMbpsRing.clear()
        wifiSignalRing.clear()
        jankRing.clear()
        collector.resetWindowCounters()
        sampleNow()
        _state.update { it.copy(statusBanner = "Session min/max/average & 5-minute VRR/Jank counters reset") }
    }

    fun copyTextToClipboard(label: String, text: String, bannerMessage: String) {
        runCatching {
            val ctx = getApplication<Application>().applicationContext
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText(label, text))
        }
        _state.update { it.copy(statusBanner = bannerMessage) }
    }

    fun copyDiagnosticMarkdown(): String {
        val report = DiagnosticReportBuilder.buildMarkdownReport(_state.value.snapshot)
        copyTextToClipboard(
            label = "Corewatch Hardware & Thermal Snapshot",
            text = report,
            bannerMessage = "Copied Markdown Hardware & Thermal Snapshot to clipboard",
        )
        return report
    }

    fun setWifiApBandFilter(filter: WifiApBandFilter) {
        _state.update { it.copy(wifiApBandFilter = filter) }
    }

    fun cycleWifiApSortOrder() {
        _state.update { it.copy(wifiApSortOrder = it.wifiApSortOrder.next()) }
    }

    fun toggleMaskApBssids() {
        _state.update { it.copy(maskApBssids = !it.maskApBssids) }
    }

    fun triggerWifiScan() {
        val (_, msg) = collector.triggerActiveWifiScan()
        sampleNow()
        _state.update { it.copy(statusBanner = msg) }
    }

    fun selectUsbNode(nodeId: String?) {
        _state.update { it.copy(selectedUsbNodeId = nodeId) }
    }

    fun setUsbSearchQuery(query: String) {
        _state.update { it.copy(usbSearchQuery = query.take(64)) }
    }

    fun setUsbCategoryFilter(filter: UsbCategoryFilter) {
        _state.update { it.copy(usbCategoryFilter = filter) }
    }

    fun toggleShowInternalUsbDevices() {
        _state.update {
            val next = !it.showInternalUsbDevices
            it.copy(
                showInternalUsbDevices = next,
                statusBanner = if (next) {
                    "Showing external + internal motherboard USB devices"
                } else {
                    "Showing external USB peripherals only"
                },
            )
        }
    }

    fun toggleUsbNodeCollapsed(nodeId: String) {
        _state.update {
            val nextSet = if (nodeId in it.collapsedUsbNodeIds) {
                it.collapsedUsbNodeIds - nodeId
            } else {
                it.collapsedUsbNodeIds + nodeId
            }
            it.copy(collapsedUsbNodeIds = nextSet)
        }
    }

    fun onPermissionsUpdated() {
        collector.invalidateWifiCaches()
        collector.invalidateUsbCaches()
        sampleNow()
    }

    internal fun setSnapshotForTesting(snapshot: SystemTelemetrySnapshot) {
        testingSnapshotPinned = true
        _state.update { it.copy(snapshot = snapshot) }
    }
}
