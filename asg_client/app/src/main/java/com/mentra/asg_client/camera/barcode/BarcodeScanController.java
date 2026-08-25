package com.mentra.asg_client.camera.barcode;

import android.graphics.Bitmap;
import android.util.Log;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;
import java.util.ArrayList;
import java.util.List;

/**
 * On-glasses barcode/QR decode (DIM-560 glasses-native).
 *
 * <p>Uses the BUNDLED ML Kit barcode scanner — the model is statically linked into the APK, so it
 * runs fully on-device with NO Google Play Services (this glasses build is GMS-less). ML Kit was
 * validated as the best decoder for the curved/soft/angled/small real-world codes this camera sees
 * (ZXing and a commercial SDK were benchmarked and dropped).
 *
 * <p>Decoded values are logged (tag {@code BarcodeScan}) and forwarded to an optional {@link
 * ResultSink}. A short dedup window plus a per-value consensus guard keeps a code that sits in frame
 * from re-reporting, and rejects the checksum-valid MISREADS that curved 1D codes can produce.
 */
public final class BarcodeScanController {
    public static final String TAG = "BarcodeScan";

    /** Same value is not re-reported within this window (ms). */
    private static final long DEDUP_WINDOW_MS = 3000L;
    /** Frames that must agree on a value before it is CONFIRMED (anti-misread on curved codes). */
    private static final int CONSENSUS_N = 2;

    private final CodeConsensus consensus = new CodeConsensus(CONSENSUS_N, DEDUP_WINDOW_MS);

    private long framesSeen = 0;
    private long decodeCount = 0;

    public interface ResultSink {
        void onBarcode(String value, String format);
    }

    private final ResultSink sink;
    private final BarcodeScanner mlkit;

    public BarcodeScanController(ResultSink sink) {
        this.sink = sink;
        // Bundled ML Kit scanner (all formats). Runs on-device, no GMS.
        this.mlkit =
                BarcodeScanning.getClient(
                        new BarcodeScannerOptions.Builder()
                                .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
                                .build());
    }

    /**
     * ML Kit decode over an NV21 buffer, fed through the consensus guard. ML Kit is async; we block
     * on the worker thread via {@link Tasks#await}. Returns the confirmed value or null.
     */
    public String decodeMlKit(byte[] nv21, int width, int height, long nowMs) {
        framesSeen++;
        try {
            InputImage image =
                    InputImage.fromByteArray(
                            nv21, width, height, 0, InputImage.IMAGE_FORMAT_NV21);
            List<Barcode> barcodes = Tasks.await(mlkit.process(image));
            String confirmed = null;
            for (Barcode b : barcodes) {
                String value = b.getRawValue();
                if (value == null) {
                    value = b.getDisplayValue();
                }
                if (value == null || value.isEmpty()) {
                    continue;
                }
                String format = mlkitFormatName(b.getFormat());
                String c = handleCandidate(value, format, nowMs);
                if (c != null) {
                    confirmed = c;
                }
            }
            return confirmed;
        } catch (Exception e) {
            Log.w(TAG, "ML Kit decode failed", e);
            return null;
        }
    }

    /**
     * Raw per-frame ML Kit decode straight from an NV21 buffer (no consensus/dedup) — returns every
     * code in the frame as "FORMAT|value". Used by the continuous scan where each frame is scored
     * independently.
     */
    public List<String> decodeMlKitRaw(byte[] nv21, int width, int height) {
        List<String> out = new ArrayList<>();
        try {
            InputImage image =
                    InputImage.fromByteArray(
                            nv21, width, height, 0, InputImage.IMAGE_FORMAT_NV21);
            for (Barcode b : Tasks.await(mlkit.process(image))) {
                String v = b.getRawValue();
                if (v == null) {
                    v = b.getDisplayValue();
                }
                if (v != null && !v.isEmpty()) {
                    out.add(mlkitFormatName(b.getFormat()) + "|" + v);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "ML Kit raw decode failed", e);
        }
        return out;
    }

    /**
     * Decode a still Bitmap with ML Kit (no consensus/dedup — raw per-image results for scoring).
     * Returns "FORMAT|value" strings, one per detected barcode.
     */
    public List<String> decodeBitmapMlKit(Bitmap bmp) {
        List<String> out = new ArrayList<>();
        try {
            InputImage image = InputImage.fromBitmap(bmp, 0);
            for (Barcode b : Tasks.await(mlkit.process(image))) {
                String v = b.getRawValue();
                if (v == null) {
                    v = b.getDisplayValue();
                }
                if (v != null && !v.isEmpty()) {
                    out.add(mlkitFormatName(b.getFormat()) + "|" + v);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "ML Kit bitmap decode failed", e);
        }
        return out;
    }

    /**
     * ML Kit downscales large inputs internally, which can collapse thin 1D bars on a 12 MP still.
     * Run ML Kit at several longer-side caps and report which scale (if any) recovers the code.
     * Returns "scale=<px>:FORMAT|value" strings across all attempted scales.
     */
    public List<String> decodeBitmapMlKitMultiScale(Bitmap bmp, int[] longerSideCaps) {
        List<String> out = new ArrayList<>();
        int w = bmp.getWidth(), h = bmp.getHeight();
        for (int cap : longerSideCaps) {
            Bitmap scaled = bmp;
            boolean recycle = false;
            int longer = Math.max(w, h);
            if (cap > 0 && longer > cap) {
                float f = (float) cap / longer;
                scaled = Bitmap.createScaledBitmap(bmp, Math.round(w * f), Math.round(h * f), true);
                recycle = true;
            }
            try {
                InputImage image = InputImage.fromBitmap(scaled, 0);
                for (Barcode b : Tasks.await(mlkit.process(image))) {
                    String v = b.getRawValue();
                    if (v == null) {
                        v = b.getDisplayValue();
                    }
                    if (v != null && !v.isEmpty()) {
                        out.add("scale=" + (cap > 0 ? cap : longer) + ":"
                                + mlkitFormatName(b.getFormat()) + "|" + v);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "ML Kit multiscale decode failed cap=" + cap, e);
            } finally {
                if (recycle) {
                    scaled.recycle();
                }
            }
        }
        return out;
    }

    /** Package-private for unit tests (pure int → name mapping). */
    static String mlkitFormatName(int fmt) {
        switch (fmt) {
            case Barcode.FORMAT_QR_CODE: return "QR";
            case Barcode.FORMAT_EAN_13: return "EAN_13";
            case Barcode.FORMAT_EAN_8: return "EAN_8";
            case Barcode.FORMAT_UPC_A: return "UPC_A";
            case Barcode.FORMAT_UPC_E: return "UPC_E";
            case Barcode.FORMAT_CODE_128: return "CODE_128";
            case Barcode.FORMAT_CODE_39: return "CODE_39";
            case Barcode.FORMAT_DATA_MATRIX: return "DATA_MATRIX";
            default: return "FMT_" + fmt;
        }
    }

    /**
     * Consensus + reporting. Curved/awkward 1D codes make decoders occasionally return a WRONG but
     * checksum-valid value — a different wrong value each frame. A genuine code decodes to the SAME
     * value repeatedly, so only CONFIRM once a value agrees across >= CONSENSUS_N frames. Single
     * reads are logged as tentative (visible, not trusted).
     *
     * @return the value when newly confirmed, else null.
     */
    private String handleCandidate(String value, String format, long nowMs) {
        decodeCount++;
        Log.i(
                TAG,
                "tentative format=" + format + " value=\"" + value + "\" (frame #" + framesSeen
                        + ", decode #" + decodeCount + ")");

        // The guard decides; this class only logs and delivers (see CodeConsensus).
        if (!consensus.observe(value, nowMs)) {
            return null; // not yet confirmed, or a dup of a just-confirmed code
        }

        Log.i(
                TAG,
                "DECODED (confirmed x" + CONSENSUS_N + ") format=" + format + " value=\"" + value
                        + "\"");
        if (sink != null) {
            try {
                sink.onBarcode(value, format);
            } catch (RuntimeException e) {
                Log.w(TAG, "result sink threw", e);
            }
        }
        return value;
    }

    public long framesSeen() {
        return framesSeen;
    }

    public long decodeCount() {
        return decodeCount;
    }

    public void reset() {
        consensus.reset();
    }
}
