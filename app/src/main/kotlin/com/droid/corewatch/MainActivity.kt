// Copyright 2026 The Corewatch Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.corewatch

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))

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
            CorewatchTheme(dark = dark) {
                CorewatchAppRoot(
                    vm = vm,
                    onResizeWindowForMode = { isMini ->
                        handleMiniHudModeTransition(isMini)
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

    override fun onStop() {
        super.onStop()
        if (pipOverlayView != null) {
            pipWasStopped = true
        }
    }

    override fun onResume() {
        super.onResume()
        if (pipOverlayView != null && pipWasStopped) {
            dismissPipOverlay()
            vm.setMiniHud(false)
        }
    }

    override fun onDestroy() {
        dismissPipOverlay()
        super.onDestroy()
    }
}
