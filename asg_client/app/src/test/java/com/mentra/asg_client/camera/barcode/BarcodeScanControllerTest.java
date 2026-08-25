package com.mentra.asg_client.camera.barcode;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.mlkit.vision.barcode.common.Barcode;

import org.junit.Test;

/**
 * The pure pieces of {@link BarcodeScanController} (DIM-560). The decode paths need the on-device
 * ML Kit runtime and real frames, so they are exercised on hardware; what CAN be pinned on the JVM
 * is the format naming every downstream consumer (BLE result, barcode.json, session_scans.format)
 * displays and stores.
 */
public class BarcodeScanControllerTest {

    @Test
    public void namesEveryFormatTheLabActuallyScans() {
        assertThat(BarcodeScanController.mlkitFormatName(Barcode.FORMAT_QR_CODE)).isEqualTo("QR");
        assertThat(BarcodeScanController.mlkitFormatName(Barcode.FORMAT_DATA_MATRIX))
                .isEqualTo("DATA_MATRIX");
        assertThat(BarcodeScanController.mlkitFormatName(Barcode.FORMAT_EAN_13)).isEqualTo("EAN_13");
        assertThat(BarcodeScanController.mlkitFormatName(Barcode.FORMAT_EAN_8)).isEqualTo("EAN_8");
        assertThat(BarcodeScanController.mlkitFormatName(Barcode.FORMAT_UPC_A)).isEqualTo("UPC_A");
        assertThat(BarcodeScanController.mlkitFormatName(Barcode.FORMAT_UPC_E)).isEqualTo("UPC_E");
        assertThat(BarcodeScanController.mlkitFormatName(Barcode.FORMAT_CODE_128))
                .isEqualTo("CODE_128");
        assertThat(BarcodeScanController.mlkitFormatName(Barcode.FORMAT_CODE_39))
                .isEqualTo("CODE_39");
    }

    @Test
    public void anUnmappedFormatStillGetsAStableName() {
        // Formats outside the named set (Aztec, PDF417, …) must never crash or vanish —
        // they surface as FMT_<mlkit id> so the value is still stored and displayable.
        assertThat(BarcodeScanController.mlkitFormatName(Barcode.FORMAT_AZTEC))
                .isEqualTo("FMT_" + Barcode.FORMAT_AZTEC);
        assertThat(BarcodeScanController.mlkitFormatName(-42)).isEqualTo("FMT_-42");
    }
}
