// Copyright 2026 The Corewatch Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.corewatch

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.provider.Settings
import android.view.FrameMetrics
import android.view.Gravity
import android.view.Window
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.droid.corewatch.ui.CorewatchAppRoot
import com.droid.corewatch.ui.CorewatchTab
import com.droid.corewatch.ui.CorewatchViewModel
import com.droid.corewatch.ui.PipOverlayRoot
import com.droid.corewatch.ui.theme.CorewatchTheme
import com.droid.corewatch.ui.theme.ThemeMode
import kotlin.math.roundToInt

internal class OverlayLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    fun onCreate() {
        savedStateController.performRestore(null)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun onDestroy() {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }
}

class MainActivity : ComponentActivity() {
    private val vm: CorewatchViewModel by viewModels()
    private var pipOverlayView: ComposeView? = null
    private var pipOverlayParams: WindowManager.LayoutParams? = null
    private var pipOverlayLifecycle: OverlayLifecycleOwner? = null
    private var pipWasStopped: Boolean = false
    private val canDrawOverlaysState = mutableStateOf(false)
    private var awaitingOverlayPermissionForPip: Boolean = false
    private var frameMetricsThread: HandlerThread? = null
    private val frameMetricsListener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
        if (metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L) return@OnFrameMetricsAvailableListener
        val totalNs = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
        val cpuStagesNs = metrics.getMetric(FrameMetrics.INPUT_HANDLING_DURATION).coerceAtLeast(0L) +
            metrics.getMetric(FrameMetrics.ANIMATION_DURATION).coerceAtLeast(0L) +
            metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION).coerceAtLeast(0L) +
            metrics.getMetric(FrameMetrics.DRAW_DURATION).coerceAtLeast(0L) +
            metrics.getMetric(FrameMetrics.SYNC_DURATION).coerceAtLeast(0L) +
            metrics.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION).coerceAtLeast(0L)
        val deadlineNs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            metrics.getMetric(FrameMetrics.DEADLINE)
        } else {
            -1L
        }
        val gpuNs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            metrics.getMetric(FrameMetrics.GPU_DURATION)
        } else {
            0L
        }
        val intendedNs = metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP)
        val vsyncNs = metrics.getMetric(FrameMetrics.VSYNC_TIMESTAMP)
        vm.recordFrameMetric(
            totalDurationNs = totalNs,
            deadlineNs = deadlineNs,
            gpuDurationNs = gpuNs,
            intendedVsyncNs = intendedNs,
            vsyncNs = vsyncNs,
            cpuStagesDurationNs = cpuStagesNs,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        canDrawOverlaysState.value = runCatching { Settings.canDrawOverlays(this) }.getOrDefault(false)
        window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
        runCatching {
            val ht = HandlerThread("corewatch-framemetrics").apply { start() }
            frameMetricsThread = ht
            window.addOnFrameMetricsAvailableListener(frameMetricsListener, Handler(ht.looper))
        }

        setContent {
            val state by vm.state.collectAsState()
            val systemDark = isSystemInDarkTheme()
            val dark = when (state.themeMode) {
                ThemeMode.System -> systemDark
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
            }
            val keepScreenOn = state.snapshot.caffeineEvaluation.isActive
            LaunchedEffect(dark, keepScreenOn) {
                if (keepScreenOn) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                if (Build.VERSION.SDK_INT >= 30) {
                    window.insetsController?.setSystemBarsAppearance(
                        if (dark) 0 else WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                    )
                }
                if (Build.VERSION.SDK_INT >= 35) {
                    val c = window.insetsController
                    c?.setSystemBarsAppearance(
                        WindowInsetsController.APPEARANCE_TRANSPARENT_CAPTION_BAR_BACKGROUND,
                        WindowInsetsController.APPEARANCE_TRANSPARENT_CAPTION_BAR_BACKGROUND,
                    )
                    c?.setSystemBarsAppearance(
                        if (dark) 0 else WindowInsetsController.APPEARANCE_LIGHT_CAPTION_BARS,
                        WindowInsetsController.APPEARANCE_LIGHT_CAPTION_BARS,
                    )
                }
            }
            LaunchedEffect(state.isMiniHud, state.miniHudAlpha) {
                window.attributes = window.attributes.apply {
                    alpha = if (state.isMiniHud) state.miniHudAlpha else 1.0f
                }
                updatePipOverlayAlpha(state.miniHudAlpha)
            }
            LaunchedEffect(state.activeTab) {
                when (state.activeTab) {
                    CorewatchTab.Network -> {
                        if (Build.VERSION.SDK_INT >= 33) {
                            runCatching {
                                if (checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                                    requestPermissions(arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES), 1003)
                                }
                            }
                        }
                    }
                    CorewatchTab.Bluetooth -> {
                        if (Build.VERSION.SDK_INT >= 31) {
                            runCatching {
                                if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                                    requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1001)
                                }
                            }
                        }
                    }
                    else -> Unit
                }
            }
            val canDrawOverlays by canDrawOverlaysState
            CorewatchTheme(dark = dark) {
                CorewatchAppRoot(
                    vm = vm,
                    canDrawOverlays = canDrawOverlays,
                    onRequestOverlayPermission = { requestOverlayPermissionForPip() },
                    onRequestWifiScanPermission = { requestWifiScanLocationPermission() },
                    onRequestNearbyWifiPermission = { requestNearbyWifiDevicesPermission() },
                    onOpenLocationSettings = { openLocationSettings() },
                    onResizeWindowForMode = { isMini ->
                        val liveOverlay = runCatching { Settings.canDrawOverlays(this) }.getOrDefault(false)
                        canDrawOverlaysState.value = liveOverlay
                        handleMiniHudModeTransition(isMini, canDrawOverlays = liveOverlay)
                    },
                )
            }
        }
    }

    internal fun handleMiniHudModeTransition(
        isMini: Boolean,
        canDrawOverlays: Boolean = Settings.canDrawOverlays(this),
    ) {
        if (isMini) {
            val density = resources.displayMetrics.density
            val isWideWindow = window.decorView.width == 0 || window.decorView.width >= (500f * density).roundToInt()
            if (canDrawOverlays && isWideWindow) {
                showPipOverlay()
                moveTaskToBack(true)
            }
        } else {
            dismissPipOverlay()
        }
    }

    private fun resolveScreenSize(wm: WindowManager): Pair<Int, Int> {
        val dm = resources.displayMetrics
        if (Build.VERSION.SDK_INT >= 30) {
            val b = runCatching { wm.maximumWindowMetrics.bounds }.getOrNull()
            if (b != null && b.width() > 0 && b.height() > 0) {
                return b.width() to b.height()
            }
        }
        return dm.widthPixels to dm.heightPixels
    }

    internal fun showPipOverlay() {
        if (pipOverlayView != null) return
        val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val density = resources.displayMetrics.density
        val (screenW, screenH) = resolveScreenSize(wm)
        val widthPx = (340f * density).roundToInt()
        val heightPx = (398f * density).roundToInt()
        val marginPx = (24f * density).roundToInt()
        val bottomMarginPx = (88f * density).roundToInt()

        val params = WindowManager.LayoutParams(
            widthPx,
            heightPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenW - widthPx - marginPx).coerceAtLeast(marginPx)
            y = (screenH - heightPx - bottomMarginPx).coerceAtLeast(marginPx)
            alpha = vm.state.value.miniHudAlpha
            title = "Corewatch Mini-HUD PiP"
        }

        val owner = OverlayLifecycleOwner().also { it.onCreate() }
        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent {
                PipOverlayRoot(
                    vm = vm,
                    onExpandToFull = { expandFromPipOverlay() },
                    onDragOverlay = { dx, dy -> dragPipOverlayBy(dx, dy) },
                )
            }
        }

        runCatching {
            wm.addView(view, params)
            pipOverlayView = view
            pipOverlayParams = params
            pipOverlayLifecycle = owner
            pipWasStopped = false
        }.onFailure {
            owner.onDestroy()
        }
    }

    internal fun dragPipOverlayBy(dx: Float, dy: Float) {
        val view = pipOverlayView ?: return
        val lp = pipOverlayParams ?: return
        val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val (screenW, screenH) = resolveScreenSize(wm)
        lp.x = (lp.x + dx.roundToInt()).coerceIn(0, (screenW - lp.width).coerceAtLeast(0))
        lp.y = (lp.y + dy.roundToInt()).coerceIn(0, (screenH - lp.height).coerceAtLeast(0))
        runCatching { wm.updateViewLayout(view, lp) }
    }

    internal fun updatePipOverlayAlpha(alphaValue: Float) {
        val view = pipOverlayView ?: return
        val params = pipOverlayParams ?: return
        val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        params.alpha = alphaValue
        runCatching { wm.updateViewLayout(view, params) }
    }

    internal fun expandFromPipOverlay() {
        dismissPipOverlay()
        vm.setMiniHud(false)
        runCatching {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            am?.appTasks?.firstOrNull()?.moveToFront()
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
                ),
            )
        }
    }

    internal fun dismissPipOverlay() {
        val view = pipOverlayView ?: return
        val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        runCatching { wm?.removeViewImmediate(view) }
        pipOverlayLifecycle?.onDestroy()
        pipOverlayView = null
        pipOverlayParams = null
        pipOverlayLifecycle = null
        pipWasStopped = false
    }

    internal fun requestOverlayPermissionForPip() {
        awaitingOverlayPermissionForPip = true
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                ),
            )
        }.onFailure {
            runCatching {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if (pipOverlayView != null) {
            pipWasStopped = true
        }
    }

    internal fun requestWifiScanLocationPermission() {
        runCatching {
            requestPermissions(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
                1002,
            )
        }
    }

    internal fun requestNearbyWifiDevicesPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            runCatching {
                requestPermissions(arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES), 1003)
            }
        }
    }

    internal fun openLocationSettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        }
    }

    override fun onResume() {
        super.onResume()
        vm.onPermissionsUpdated()
        val hasOverlay = runCatching { Settings.canDrawOverlays(this) }.getOrDefault(false)
        canDrawOverlaysState.value = hasOverlay
        if (awaitingOverlayPermissionForPip) {
            awaitingOverlayPermissionForPip = false
            if (vm.state.value.isMiniHud && hasOverlay) {
                handleMiniHudModeTransition(isMini = true, canDrawOverlays = true)
                return
            }
        }
        if (pipOverlayView != null && pipWasStopped) {
            dismissPipOverlay()
            vm.setMiniHud(false)
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        vm.onTrimMemoryLevel(level)
    }

    @Deprecated("Deprecated in ComponentActivity")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        @Suppress("DEPRECATION")
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        vm.onPermissionsUpdated()
    }

    override fun onDestroy() {
        runCatching { window.removeOnFrameMetricsAvailableListener(frameMetricsListener) }
        runCatching { frameMetricsThread?.quitSafely() }
        frameMetricsThread = null
        dismissPipOverlay()
        super.onDestroy()
    }
}
