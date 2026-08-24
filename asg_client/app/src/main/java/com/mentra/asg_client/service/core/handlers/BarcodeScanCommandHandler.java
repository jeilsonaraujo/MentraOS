package com.mentra.asg_client.service.core.handlers;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.mentra.asg_client.camera.CameraNeoService;
import com.mentra.asg_client.service.communication.interfaces.ICommunicationManager;
import com.mentra.asg_client.service.legacy.interfaces.ICommandHandler;

import org.json.JSONObject;

import java.io.File;
import java.util.Set;

/**
 * DIM-560 glasses-native barcode scanning over BLE. Starts/stops the on-device continuous "barcode
 * sweep" ({@link CameraNeoService}) and forwards the decoded result back over BLE.
 *
 * <p>Commands (arrive over BLE from the phone, or over adb {@code ACTION_SEND_COMMAND} for testing):
 * <pre>
 *   {"type":"start_barcode_scan","requestId":"&lt;sessionId&gt;-&lt;epoch&gt;","sweep_max":35,
 *    "stop_on_found":true,"af_lock":true}
 *   {"type":"stop_barcode_scan"}
 * </pre>
 * Result — sent via {@code communicationManager.sendBluetoothResponse}, which is mirrored to adb via
 * IntentResponseBroadcaster so the HTML harness can observe it without a phone:
 * <pre>
 *   {"type":"barcode_scan_result","found":true,"value":"…","format":"…","ts":…,"requestId":"…"}
 *   {"type":"barcode_scan_result","found":false,"requestId":"…"}
 * </pre>
 */
public class BarcodeScanCommandHandler implements ICommandHandler {
    private static final String TAG = "BarcodeScanCmd";

    private final Context context;
    private final ICommunicationManager communicationManager;

    public BarcodeScanCommandHandler(Context context, ICommunicationManager communicationManager) {
        this.context = context;
        this.communicationManager = communicationManager;
    }

    @Override
    public Set<String> getSupportedCommandTypes() {
        return Set.of("start_barcode_scan", "stop_barcode_scan");
    }

    @Override
    public boolean handleCommand(String commandType, JSONObject data) {
        try {
            if ("stop_barcode_scan".equals(commandType)) {
                Intent i = new Intent(context, CameraNeoService.class);
                i.setAction(CameraNeoService.ACTION_STOP_BARCODE_SCAN);
                context.startForegroundService(i);
                return true;
            }

            // start_barcode_scan
            String requestId = data != null ? data.optString("requestId", "") : "";
            int maxFrames = data != null ? data.optInt("sweep_max", 35) : 35;
            boolean stopOnFound = data == null || data.optBoolean("stop_on_found", true);
            boolean afLock = data == null || data.optBoolean("af_lock", true);

            // Forward the sweep outcome over BLE (mirrored to adb for the harness).
            CameraNeoService.setSweepResultCallback(
                    result -> {
                        boolean sent = communicationManager.sendBluetoothResponse(result);
                        Log.i(TAG, "barcode_scan_result sent=" + sent + " " + result);
                    });

            String rid = requestId.isEmpty()
                    ? ("scan" + System.currentTimeMillis())
                    : requestId;
            File dir =
                    new File(new File(context.getExternalFilesDir(null), "barcode_sweep"),
                            sanitize(rid));

            Intent i = new Intent(context, CameraNeoService.class);
            i.setAction(CameraNeoService.ACTION_START_BARCODE_SWEEP);
            i.putExtra(CameraNeoService.EXTRA_SWEEP_DIR, dir.getAbsolutePath());
            i.putExtra(CameraNeoService.EXTRA_SWEEP_REQUEST_ID, rid);
            i.putExtra(CameraNeoService.EXTRA_SWEEP_MAX, maxFrames);
            i.putExtra(CameraNeoService.EXTRA_SWEEP_STOP_ON_FOUND, stopOnFound);
            i.putExtra(CameraNeoService.EXTRA_SWEEP_AF_LOCK, afLock);
            context.startForegroundService(i);
            Log.i(TAG, "start_barcode_scan requestId=" + rid + " max=" + maxFrames
                    + " stopOnFound=" + stopOnFound + " afLock=" + afLock);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "barcode scan command failed: " + commandType, e);
            return false;
        }
    }

    private static String sanitize(String s) {
        return s.replaceAll("[^A-Za-z0-9_.-]", "_");
    }
}
