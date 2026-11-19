package com.budjetame.android

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * The Quick Settings tile "Record Budjetame" (ticket #65): a tap opens the
 * Quick Dictation card. On the lock screen it asks to unlock first, then
 * shows the card. Exercised only on a device.
 */
class RecordTileService : TileService() {

    override fun onStartListening() {
        // A launcher, not a toggle: it never shows as on.
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        if (isLocked) unlockAndRun { openCard() } else openCard()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openCard() {
        val intent = Intent(this, QuickDictationActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE),
            )
        } else {
            // The Intent overload is the only one before API 34 (and throws
            // from 34 on for apps targeting it).
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
