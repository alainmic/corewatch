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
import com.droid.corewatch.service.CaffeineController
import com.droid.corewatch.ui.theme.ThemeMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

enum class CorewatchTab(
    val index: Int,
    val shortTitle: String,
    val compactTitle: String,
    val fullTitle: String,
    val shortcutLabel: String,
) {
    OverviewThermals(0, "Thermals & CPU", "CPU", "Overview & Thermals", "Ctrl+1"),
    BatteryPower(1, "Battery & W", "Battery", "Battery & Power", "Ctrl+2"),
    MemoryStorage(2, "Memory & Swap", "Memory", "Memory & Storage", "Ctrl+3"),
    DisplaysNetwork(3, "Displays & Net", "Displays", "Displays & Network", "Ctrl+4"),
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
)

data class CorewatchUiState(
    val activeTab: CorewatchTab = CorewatchTab.OverviewThermals,
    val isMiniHud: Boolean = false,
    val miniHudAlpha: Float = 0.88f,
    val isPaused: Boolean = false,
    val useFahrenheit: Boolean = false,
    val showShortcutsDialog: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.Dark,
    val statusBanner: String? = null,
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
    private var lastWindowNarrow: Boolean = false

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
                delay(1_000L)
                if (!_state.value.isPaused) {
                    sampleNow()
                }
            }
        }
    }

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

    fun resetSessionHistory() {
        cpuRing.clear()
        gpuRing.clear()
        npuRing.clear()
        thermalRing.clear()
        wattsRing.clear()
        memRing.clear()
        netRing.clear()
        sampleNow()
        _state.update { it.copy(statusBanner = "Session min/max/average telemetry counters reset") }
    }

    fun copyDiagnosticMarkdown(): String {
        val report = DiagnosticReportBuilder.buildMarkdownReport(_state.value.snapshot)
        runCatching {
            val ctx = getApplication<Application>().applicationContext
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("Corewatch Hardware & Thermal Snapshot", report))
        }
        _state.update { it.copy(statusBanner = "Copied Markdown Hardware & Thermal Snapshot to clipboard") }
        return report
    }

    internal fun setSnapshotForTesting(snapshot: SystemTelemetrySnapshot) {
        _state.update { it.copy(snapshot = snapshot) }
    }
}
