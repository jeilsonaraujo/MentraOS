package com.mentra.asg_client.service.core.handlers;

import android.util.Log;

import com.mentra.asg_client.io.bluetooth.interfaces.ICompanionTransport;
import com.mentra.asg_client.io.hardware.interfaces.IHardwareManager;
import com.mentra.asg_client.service.legacy.interfaces.ICommandHandler;
import com.mentra.asg_client.service.legacy.managers.AsgClientServiceManager;
import com.mentra.asg_client.service.system.interfaces.IStateManager;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Handler for battery-related commands.
 * Follows Single Responsibility Principle by handling only battery commands.
 */
public class BatteryCommandHandler implements ICommandHandler {
    private static final String TAG = "BatteryCommandHandler";
    
    private final IStateManager stateManager;
    private final IHardwareManager hardwareManager;
    private final AsgClientServiceManager serviceManager;

    public BatteryCommandHandler(
            IStateManager stateManager,
            IHardwareManager hardwareManager,
            AsgClientServiceManager serviceManager) {
        this.stateManager = stateManager;
        this.hardwareManager = hardwareManager;
        this.serviceManager = serviceManager;
    }

    @Override
    public Set<String> getSupportedCommandTypes() {
        return Set.of("battery_status", "request_battery_state");
    }

    @Override
    public boolean handleCommand(String commandType, JSONObject data) {
        try {
            switch (commandType) {
                case "battery_status":
                    return handleBatteryStatus(data);
                case "request_battery_state":
                    return handleRequestBatteryState();
                default:
                    Log.e(TAG, "Unsupported battery command: " + commandType);
                    return false;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error handling battery command: " + commandType, e);
            return false;
        }
    }

    /**
     * Handle battery status command
     */
    private boolean handleBatteryStatus(JSONObject data) {
        try {
            int level = data.optInt("level", -1);
            boolean charging = data.optBoolean("charging", false);
            long timestamp = data.optLong("timestamp", System.currentTimeMillis());
            
            stateManager.updateBatteryStatus(level, charging, timestamp);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error handling battery status command", e);
            return false;
        }
    }

    /**
     * Handle request battery state command
     */
    private boolean handleRequestBatteryState() {
        try {
            // On the K900 a stale cache makes this query the BES (mh_batv). An answer that misses
            // the query's short wait still arrives later as hm_batv, and BatteryEventSubscriber
            // sends that one — so an unknown level here is not the end of the request.
            int level = hardwareManager.getBatteryLevel();
            if (level < 0) {
                Log.d(TAG, "Requesting battery state - no reading yet, waiting for the BES");
                return true;
            }
            boolean charging = hardwareManager.getChargingStatus();

            ICompanionTransport transport = serviceManager.getBluetoothManager();
            if (transport == null || !transport.isConnected()) {
                Log.w(TAG, "Cannot answer battery state - transport not connected");
                return true;
            }
            JSONObject status = new JSONObject();
            status.put("type", "battery_status");
            status.put("charging", charging);
            status.put("percent", level);
            transport.sendMessage(status.toString().getBytes(StandardCharsets.UTF_8));
            Log.d(TAG, "Answered battery state: " + level + "% charging=" + charging);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error handling request battery state command", e);
            return false;
        }
    }

    /**
     * Handle battery status from K900 protocol
     */
    public boolean handleK900BatteryStatus(JSONObject bData) {
        try {
            if (bData != null) {
                int newBatteryPercentage = bData.optInt("pt", -1);
                int newBatteryVoltage = bData.optInt("vt", -1);
                
                if (newBatteryPercentage != -1) {
                    Log.d(TAG, "🔋 Battery percentage: " + newBatteryPercentage + "%");
                }
                if (newBatteryVoltage != -1) {
                    Log.d(TAG, "🔋 Battery voltage: " + newBatteryVoltage + "mV");
                }
                
                // Calculate charging status based on voltage
                boolean isCharging = newBatteryVoltage > 3900;
                
                // Update state manager
                if (newBatteryPercentage != -1 || newBatteryVoltage != -1) {
                    stateManager.updateBatteryStatus(newBatteryPercentage, isCharging, System.currentTimeMillis());
                    return true;
                }
            } else {
                Log.w(TAG, "hm_batv received but no B field data");
            }
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Error handling K900 battery status", e);
            return false;
        }
    }
} 