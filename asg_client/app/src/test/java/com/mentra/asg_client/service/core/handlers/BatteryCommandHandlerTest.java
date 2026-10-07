package com.mentra.asg_client.service.core.handlers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mentra.asg_client.io.bluetooth.interfaces.ICompanionTransport;
import com.mentra.asg_client.io.hardware.interfaces.IHardwareManager;
import com.mentra.asg_client.service.legacy.managers.AsgClientServiceManager;
import com.mentra.asg_client.service.system.interfaces.IStateManager;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Verifies that request_battery_state answers with the battery. It used to be a stub that only
 * logged, so a client that asked heard nothing until the BES happened to push a new reading.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class BatteryCommandHandlerTest {

    private IHardwareManager hardwareManager;
    private ICompanionTransport transport;
    private BatteryCommandHandler handler;

    @Before
    public void setUp() {
        hardwareManager = mock(IHardwareManager.class);
        transport = mock(ICompanionTransport.class);
        AsgClientServiceManager serviceManager = mock(AsgClientServiceManager.class);
        when(serviceManager.getBluetoothManager()).thenReturn(transport);
        when(transport.isConnected()).thenReturn(true);
        handler =
                new BatteryCommandHandler(
                        mock(IStateManager.class), hardwareManager, serviceManager);
    }

    @Test
    public void requestBatteryState_answersWithTheCurrentReading() throws Exception {
        when(hardwareManager.getBatteryLevel()).thenReturn(72);
        when(hardwareManager.getChargingStatus()).thenReturn(true);

        boolean handled = handler.handleCommand("request_battery_state", new JSONObject());

        assertThat(handled).isTrue();
        ArgumentCaptor<byte[]> sent = ArgumentCaptor.forClass(byte[].class);
        verify(transport).sendMessage(sent.capture());
        JSONObject status = new JSONObject(new String(sent.getValue(), StandardCharsets.UTF_8));
        assertThat(status.getString("type")).isEqualTo("battery_status");
        assertThat(status.getInt("percent")).isEqualTo(72);
        assertThat(status.getBoolean("charging")).isTrue();
    }

    @Test
    public void requestBatteryState_unknownReading_sendsNothing() {
        when(hardwareManager.getBatteryLevel()).thenReturn(-1);

        boolean handled = handler.handleCommand("request_battery_state", new JSONObject());

        assertThat(handled).isTrue();
        verify(transport, never()).sendMessage(any(byte[].class));
    }
}
