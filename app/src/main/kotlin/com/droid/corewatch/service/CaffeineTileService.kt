// Copyright 2026 The Corewatch Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.corewatch.service

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.droid.corewatch.data.CaffeineDuration

/**
 * System Quick Settings Tile (`Corewatch Caffeine`) allowing one-click keep-awake toggling
 * from the Googlebook system tray without switching windows.
 */
class CaffeineTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        CaffeineController.init(this)
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val updated = CaffeineController.toggleQuick(this)
        val active = updated.duration != CaffeineDuration.Off
        CaffeineController.syncWakeLock(this, active)
        updateTileState()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val cfg = CaffeineController.config.value
        val active = cfg.duration != CaffeineDuration.Off || cfg.autoOnAcCharging || cfg.autoOnExternalDisplay
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Corewatch Caffeine"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when {
                cfg.duration != CaffeineDuration.Off -> cfg.duration.label
                cfg.autoOnAcCharging || cfg.autoOnExternalDisplay -> "Auto"
                else -> "Off"
            }
        }
        tile.updateTile()
    }
}
