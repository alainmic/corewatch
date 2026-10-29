// Copyright 2026 The Corewatch Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.corewatch.service

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import com.droid.corewatch.data.CaffeineConfig
import com.droid.corewatch.data.CaffeineDuration
import com.droid.corewatch.data.CaffeinePolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide controller for the "Caffeine" Keep-Awake utility, shared between
 * [com.droid.corewatch.MainActivity] and [CaffeineTileService].
 */
object CaffeineController {
    private const val PREFS_NAME = "corewatch_caffeine_prefs"
    private const val KEY_AUTO_AC = "auto_ac"
    private const val KEY_AUTO_EXT_DISPLAY = "auto_ext_display"
    private const val KEY_LOW_BATT_CUTOFF = "low_batt_cutoff"
    private const val MAX_WAKELOCK_HOLD_MS = 4L * 60L * 60_000L // 4-hour bounded lease renewed on tick

    private val _config = MutableStateFlow(CaffeineConfig())
    val config: StateFlow<CaffeineConfig> = _config.asStateFlow()

    private var wakeLock: PowerManager.WakeLock? = null
    private var initialized = false

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _config.value = CaffeineConfig(
            duration = CaffeineDuration.Off,
            activatedAtElapsedMs = 0L,
            autoOnAcCharging = prefs.getBoolean(KEY_AUTO_AC, false),
            autoOnExternalDisplay = prefs.getBoolean(KEY_AUTO_EXT_DISPLAY, false),
            lowBatteryCutoffPercent = prefs.getInt(KEY_LOW_BATT_CUTOFF, CaffeineConfig.DEFAULT_LOW_BATTERY_CUTOFF),
        )
    }

    fun setDuration(context: Context, duration: CaffeineDuration, nowElapsedMs: Long = SystemClock.elapsedRealtime()) {
        init(context)
        _config.value = _config.value.copy(
            duration = duration,
            activatedAtElapsedMs = if (duration == CaffeineDuration.Off) 0L else nowElapsedMs,
        )
    }

    fun toggleQuick(context: Context, nowElapsedMs: Long = SystemClock.elapsedRealtime()): CaffeineConfig {
        init(context)
        val next = CaffeinePolicy.toggleQuick(_config.value, nowElapsedMs)
        _config.value = next
        return next
    }

    fun setAutoRules(
        context: Context,
        autoOnAcCharging: Boolean = _config.value.autoOnAcCharging,
        autoOnExternalDisplay: Boolean = _config.value.autoOnExternalDisplay,
    ) {
        init(context)
        val next = _config.value.copy(
            autoOnAcCharging = autoOnAcCharging,
            autoOnExternalDisplay = autoOnExternalDisplay,
        )
        _config.value = next
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_AC, autoOnAcCharging)
            .putBoolean(KEY_AUTO_EXT_DISPLAY, autoOnExternalDisplay)
            .apply()
    }

    /** Synchronizes the system partial wake lock with the evaluated [shouldKeepAwake] state. */
    @Synchronized
    fun syncWakeLock(context: Context, shouldKeepAwake: Boolean) {
        try {
            val pm = context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            if (shouldKeepAwake) {
                val lock = wakeLock ?: pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "Corewatch:CaffeineKeepAwake",
                ).apply { setReferenceCounted(false) }.also { wakeLock = it }
                if (!lock.isHeld) {
                    lock.acquire(MAX_WAKELOCK_HOLD_MS)
                }
            } else {
                wakeLock?.let { if (it.isHeld) it.release() }
            }
        } catch (_: Exception) {
        }
    }
}
