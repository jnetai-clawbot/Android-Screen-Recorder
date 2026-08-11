package com.jnet.screenrecorder.quick;

import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;

import com.jnet.screenrecorder.MainActivity;
import com.jnet.screenrecorder.R;
import com.jnet.screenrecorder.recorder.RecorderService;

/**
 * Quick Settings tile that starts/stops the screen recorder WITHOUT needing the
 * overlay (SYSTEM_ALERT_WINDOW) permission. Tapping the tile:
 *  - If not recording: opens the screen-capture consent prompt (via MainActivity)
 *    then starts recording.
 *  - If already recording: stops the recording immediately.
 * The tile icon/state reflects the current recording status.
 */
public class RecorderTileService extends TileService {

    @Override
    public void onStartListening() {
        // Keep the tile state in sync with the real recording status.
        updateTileState();
    }

    @Override
    public void onTileAdded() {
        // Default to "not recording" state when first added.
        updateTileState();
    }

    @Override
    public void onClick() {
        boolean recording = RecorderService.isRecording();
        if (recording) {
            // Already recording -> stop it right now (no consent needed).
            try {
                Intent stop = new Intent(this, RecorderService.class)
                        .setAction(RecorderService.ACTION_STOP);
                startService(stop);
                Toast.makeText(this, R.string.stop_recording, Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                com.jnet.screenrecorder.ErrorLog.e("tile stop error", e);
                Toast.makeText(this, "Could not stop recording", Toast.LENGTH_LONG).show();
            }
        } else {
            // Not recording -> request screen-capture consent, then start.
            // Screen capture MUST be requested from an Activity (system dialog),
            // so route through MainActivity exactly like the notification Start button.
            try {
                Intent start = new Intent(this, MainActivity.class)
                        .setAction(MainActivity.ACTION_REQUEST_CAPTURE)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(start);
            } catch (Exception e) {
                com.jnet.screenrecorder.ErrorLog.e("tile start error", e);
                Toast.makeText(this, "Could not start recording", Toast.LENGTH_LONG).show();
            }
        }
        updateTileState();
    }

    /** Refreshes the tile label/icon to match whether a recording is in progress. */
    private void updateTileState() {
        try {
            Tile tile = getQsTile();
            if (tile == null) return;
            boolean recording = RecorderService.isRecording();
            if (recording) {
                tile.setLabel(getString(R.string.stop_recording));
                tile.setIcon(Icon.createWithResource(this, R.drawable.ic_stop));
                tile.setState(Tile.STATE_ACTIVE);
            } else {
                tile.setLabel(getString(R.string.start_recording));
                tile.setIcon(Icon.createWithResource(this, R.drawable.ic_record));
                tile.setState(Tile.STATE_INACTIVE);
            }
            tile.updateTile();
        } catch (Exception e) {
            com.jnet.screenrecorder.ErrorLog.e("tile update error", e);
        }
    }

    @Override
    public void onStopListening() {
        // Nothing needed here.
    }
}
