package com.mentra.asg_client.service.core.handlers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Intent;

import com.mentra.asg_client.camera.CameraNeoService;
import com.mentra.asg_client.service.communication.interfaces.ICommunicationManager;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * The BLE wire contract of {@code start_barcode_scan} / {@code stop_barcode_scan}:
 * what JSON arriving from the phone turns into which {@link CameraNeoService} intent. This is the
 * contract a phone-side SDK (e.g. bluetooth_sdk) would program against, so it is pinned here.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class BarcodeScanCommandHandlerTest {

    private Application context;
    private BarcodeScanCommandHandler handler;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        handler =
                new BarcodeScanCommandHandler(
                        context, Mockito.mock(ICommunicationManager.class));
    }

    @Test
    public void declaresExactlyTheTwoScanCommands() {
        assertThat(handler.getSupportedCommandTypes())
                .containsExactlyInAnyOrder("start_barcode_scan", "stop_barcode_scan");
    }

    @Test
    public void startCommandStartsTheSweepWithTheCallersParameters() throws Exception {
        JSONObject data =
                new JSONObject()
                        .put("requestId", "scan-1-1787599423695")
                        .put("sweep_max", 8)
                        .put("stop_on_found", true)
                        .put("af_lock", true);

        boolean handled = handler.handleCommand("start_barcode_scan", data);

        assertThat(handled).isTrue();
        Intent i = shadowOf(context).getNextStartedService();
        assertThat(i.getAction()).isEqualTo(CameraNeoService.ACTION_START_BARCODE_SWEEP);
        assertThat(i.getComponent().getClassName()).isEqualTo(CameraNeoService.class.getName());
        assertThat(i.getStringExtra(CameraNeoService.EXTRA_SWEEP_REQUEST_ID))
                .isEqualTo("scan-1-1787599423695");
        assertThat(i.getIntExtra(CameraNeoService.EXTRA_SWEEP_MAX, -1)).isEqualTo(8);
        assertThat(i.getBooleanExtra(CameraNeoService.EXTRA_SWEEP_STOP_ON_FOUND, false)).isTrue();
        assertThat(i.getBooleanExtra(CameraNeoService.EXTRA_SWEEP_AF_LOCK, false)).isTrue();
    }

    @Test
    public void startCommandDefaultsMatchTheDocumentedContract() throws Exception {
        // A bare start (only a requestId) must behave like the documented defaults:
        // sweep_max 35, stop_on_found true, af_lock true.
        boolean handled =
                handler.handleCommand(
                        "start_barcode_scan", new JSONObject().put("requestId", "r1"));

        assertThat(handled).isTrue();
        Intent i = shadowOf(context).getNextStartedService();
        assertThat(i.getIntExtra(CameraNeoService.EXTRA_SWEEP_MAX, -1)).isEqualTo(35);
        assertThat(i.getBooleanExtra(CameraNeoService.EXTRA_SWEEP_STOP_ON_FOUND, false)).isTrue();
        assertThat(i.getBooleanExtra(CameraNeoService.EXTRA_SWEEP_AF_LOCK, false)).isTrue();
    }

    @Test
    public void aHostileRequestIdCannotEscapeTheSweepDirectory() throws Exception {
        // The requestId names the per-sweep debug directory; path separators must be
        // neutralized so a crafted id collapses to ONE directory segment directly
        // under barcode_sweep/ instead of traversing out of it.
        handler.handleCommand(
                "start_barcode_scan", new JSONObject().put("requestId", "../../etc/passwd"));

        Intent i = shadowOf(context).getNextStartedService();
        java.io.File dir =
                new java.io.File(i.getStringExtra(CameraNeoService.EXTRA_SWEEP_DIR));
        assertThat(dir.getParentFile().getName()).isEqualTo("barcode_sweep");
        assertThat(dir.getName()).doesNotContain("/");
    }

    @Test
    public void stopCommandStopsTheSweep() {
        boolean handled = handler.handleCommand("stop_barcode_scan", null);

        assertThat(handled).isTrue();
        Intent i = shadowOf(context).getNextStartedService();
        assertThat(i.getAction()).isEqualTo(CameraNeoService.ACTION_STOP_BARCODE_SCAN);
        assertThat(i.getComponent().getClassName()).isEqualTo(CameraNeoService.class.getName());
    }
}
