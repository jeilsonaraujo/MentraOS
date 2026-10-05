package com.mentra.asg_client.service.core.handlers;

import android.util.Log;

import com.mentra.asg_client.io.media.utils.MediaUtils;
import com.mentra.asg_client.service.communication.interfaces.ICommunicationManager;
import com.mentra.asg_client.service.legacy.interfaces.ICommandHandler;
import com.mentra.asg_client.service.legacy.managers.AsgClientServiceManager;

import org.json.JSONObject;

import java.util.Set;

/**
 * Handler for the public capture folder setting.
 *
 * <p>With {@code save_on_public_folder} enabled, photos and videos are written to a public
 * directory instead of the app's private media directory, so another app on the same device can
 * pick them up. It is off by default and persisted, because the private directory is what the
 * on-glasses gallery, the thumbnail manager, the orphan cleanup and the BLE transfer all read
 * from — turning this on takes captures out of those flows deliberately.
 */
public class PublicFolderCommandHandler implements ICommandHandler {
    private static final String TAG = "PublicFolderCommandHandler";

    private final AsgClientServiceManager serviceManager;
    private final ICommunicationManager communicationManager;

    public PublicFolderCommandHandler(
            AsgClientServiceManager serviceManager,
            ICommunicationManager communicationManager) {
        this.serviceManager = serviceManager;
        this.communicationManager = communicationManager;
    }

    @Override
    public Set<String> getSupportedCommandTypes() {
        return Set.of("save_on_public_folder");
    }

    @Override
    public boolean handleCommand(String commandType, JSONObject data) {
        try {
            switch (commandType) {
                case "save_on_public_folder":
                    return handlePublicFolderState(data);
                default:
                    Log.w(TAG, "Unsupported command type: " + commandType);
                    return false;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error handling public folder command: " + commandType, e);
            return false;
        }
    }

    /**
     * Handle the public folder setting update.
     *
     * @param data JSON data containing an 'enabled' boolean field
     * @return true if handled successfully
     */
    private boolean handlePublicFolderState(JSONObject data) {
        try {
            boolean enabled = data.optBoolean("enabled", false);
            String requestId = getRequestId(data);

            if (serviceManager == null
                    || serviceManager.getAsgSettings() == null
                    || serviceManager.getFileManager() == null) {
                Log.e(TAG, "Service manager, settings or file manager not available");
                sendSettingsError(requestId, "settings_unavailable", "Settings are not available.");
                return false;
            }

            serviceManager.getAsgSettings().setSaveOnPublicFolder(enabled);
            serviceManager
                    .getFileManager()
                    .setPublicMediaDirectory(
                            enabled ? MediaUtils.getPublicCaptureDirectory() : null);

            Log.i(
                    TAG,
                    "📂 Public folder capture "
                            + (enabled ? "ENABLED" : "DISABLED")
                            + " - captures go to "
                            + (enabled
                                    ? MediaUtils.getPublicCaptureDirectory().getAbsolutePath()
                                    : "the private media directory"));
            sendSettingsAck(requestId, enabled);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error processing public folder state", e);
            return false;
        }
    }

    private String getRequestId(JSONObject data) {
        String requestId = data.optString("requestId", "");
        if (requestId == null || requestId.isEmpty()) {
            requestId = data.optString("request_id", "");
        }
        return requestId == null ? "" : requestId;
    }

    private void sendSettingsAck(String requestId, boolean enabled) {
        if (requestId == null || requestId.isEmpty()) {
            return;
        }
        try {
            JSONObject ack = new JSONObject();
            ack.put("type", "settings_ack");
            ack.put("request_id", requestId);
            ack.put("setting", "save_on_public_folder");
            ack.put("status", "applied");
            ack.put("enabled", enabled);
            ack.put("ready", true);
            ack.put("timestamp", System.currentTimeMillis());
            communicationManager.sendBluetoothResponse(ack);
        } catch (Exception e) {
            Log.e(TAG, "Failed to send public folder settings ack", e);
        }
    }

    private void sendSettingsError(String requestId, String errorCode, String errorMessage) {
        if (requestId == null || requestId.isEmpty()) {
            return;
        }
        try {
            JSONObject ack = new JSONObject();
            ack.put("type", "settings_ack");
            ack.put("request_id", requestId);
            ack.put("setting", "save_on_public_folder");
            ack.put("status", "error");
            ack.put("ready", false);
            ack.put("error_code", errorCode);
            ack.put("error_message", errorMessage);
            ack.put("timestamp", System.currentTimeMillis());
            communicationManager.sendBluetoothResponse(ack);
        } catch (Exception e) {
            Log.e(TAG, "Failed to send public folder settings error", e);
        }
    }
}
