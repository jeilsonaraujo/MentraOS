package com.mentra.asg_client.service.core.handlers;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import com.mentra.asg_client.camera.barcode.BarcodeScanController;
import com.mentra.asg_client.service.legacy.interfaces.ICommandHandler;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DIM-560 on-device barcode decode utility (diagnostics / offline evaluation). Two commands:
 *
 * <p><b>decode_image_dir</b> — decodes every JPEG in a directory with ML Kit and logs the results,
 * for evaluating captured images off the live camera path.
 *   adb shell "am broadcast -n <pkg>/com.mentra.asg_client.receiver.IntentCommandReceiver \
 *     -a com.mentra.asg_client.ACTION_SEND_COMMAND --es json '{\"type\":\"decode_image_dir\",\"dir\":\"/sdcard/scan_images\"}'"
 *
 * <p><b>mlkit_decode_file</b> — decodes ONE image with ML Kit (downscaled ladder, since full-res
 * 12 MP silently returns nothing) and writes a small result JSON next to it
 * ({@code <path>.mlkit.json}) so a host can read the outcome per file:
 *   ... --es json '{"type":"mlkit_decode_file","path":"/sdcard/.../base.jpg"}'
 * Result JSON: {"found":bool,"values":[{"value":"...","format":"..."}]}
 */
public class BarcodeDecodeCommandHandler implements ICommandHandler {
    private static final String TAG = "BarcodeDecode";

    // ML Kit needs a downscaled input; this short ladder covers the scales that read our dataset
    // (amazon ~800-1400, mango ~1200-2000) in one quick per-frame pass.
    private static final int[] LIVE_SCALES = new int[] {1280, 1000, 1600, 2000};

    @Override
    public Set<String> getSupportedCommandTypes() {
        return Set.of("decode_image_dir", "mlkit_decode_file");
    }

    @Override
    public boolean handleCommand(String commandType, JSONObject data) {
        if ("mlkit_decode_file".equals(commandType)) {
            String path = data != null ? data.optString("path", "") : "";
            if (path.isEmpty()) {
                Log.w(TAG, "mlkit_decode_file: missing path");
                return false;
            }
            new Thread(() -> decodeOne(path), "mlkit-decode-file").start();
            return true;
        }
        String dir = data != null ? data.optString("dir", "/sdcard/scan_images") : "/sdcard/scan_images";
        // Run off the main thread — decode of a 12 MP bitmap is heavy.
        new Thread(() -> runImageDirDecode(dir), "decode-image-dir").start();
        return true;
    }

    /**
     * Live burst-scan frame: ML Kit decode of a single captured JPEG, result written atomically to
     * {@code <path>.mlkit.json} (temp + rename, so the host never reads a half-written file).
     */
    private void decodeOne(String path) {
        // value -> format, insertion-ordered and de-duped across scales.
        Map<String, String> uniq = new LinkedHashMap<>();
        boolean decodedFile = false;
        long t0 = System.currentTimeMillis();
        try {
            Bitmap bmp = BitmapFactory.decodeFile(path);
            if (bmp != null) {
                decodedFile = true;
                List<String> multi =
                        new BarcodeScanController(null).decodeBitmapMlKitMultiScale(bmp, LIVE_SCALES);
                bmp.recycle();
                for (String m : multi) {
                    // entries look like "scale=1280:UPC_A|071464309510"
                    String body = m;
                    int colon = body.indexOf(':');
                    if (body.startsWith("scale=") && colon >= 0) {
                        body = body.substring(colon + 1);
                    }
                    int bar = body.indexOf('|');
                    String fmt = bar >= 0 ? body.substring(0, bar) : "?";
                    String val = bar >= 0 ? body.substring(bar + 1) : body;
                    if (!val.isEmpty()) {
                        uniq.putIfAbsent(val, fmt);
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "mlkit_decode_file decode failed path=" + path, t);
        }
        long ms = System.currentTimeMillis() - t0;

        File out = new File(path + ".mlkit.json");
        File tmp = new File(path + ".mlkit.json.tmp");
        try {
            JSONObject o = new JSONObject();
            o.put("path", path);
            o.put("decoded_file", decodedFile);
            o.put("found", !uniq.isEmpty());
            o.put("ms", ms);
            JSONArray arr = new JSONArray();
            for (Map.Entry<String, String> e : uniq.entrySet()) {
                arr.put(new JSONObject().put("value", e.getKey()).put("format", e.getValue()));
            }
            o.put("values", arr);
            try (FileWriter w = new FileWriter(tmp)) {
                w.write(o.toString());
            }
            if (!tmp.renameTo(out)) {
                Log.w(TAG, "mlkit_decode_file: rename to " + out.getName() + " failed");
            }
            Log.i(TAG, "MLKIT_DECODE file=" + new File(path).getName()
                    + " ms=" + ms + " found=" + (!uniq.isEmpty()) + " values=" + uniq.keySet());
        } catch (Throwable t) {
            Log.w(TAG, "mlkit_decode_file write failed", t);
        }
    }

    private void runImageDirDecode(String dir) {
        File d = new File(dir);
        File[] files = d.listFiles(f -> {
            String n = f.getName().toLowerCase();
            return n.endsWith(".jpg") || n.endsWith(".jpeg");
        });
        if (files == null || files.length == 0) {
            Log.w(TAG, "DECODE no images in " + dir);
            return;
        }
        Arrays.sort(files);
        BarcodeScanController ctrl = new BarcodeScanController(null);
        Log.i(TAG, "DECODE start dir=" + dir + " files=" + files.length);
        // ML Kit downscales large inputs internally, so a code can appear only at some scales.
        int[] mlScales = new int[] {2000, 1800, 1600, 1400, 1200, 1000, 900, 800, 700};
        for (File f : files) {
            Bitmap bmp = null;
            try {
                bmp = BitmapFactory.decodeFile(f.getAbsolutePath());
                if (bmp == null) {
                    Log.w(TAG, "DECODE file=" + f.getName() + " error=DECODE_FILE_FAILED");
                    continue;
                }
                long t0 = System.currentTimeMillis();
                List<String> ml = ctrl.decodeBitmapMlKitMultiScale(bmp, mlScales);
                long mlMs = System.currentTimeMillis() - t0;
                Log.i(TAG, "DECODE file=" + f.getName()
                        + " dims=" + bmp.getWidth() + "x" + bmp.getHeight()
                        + " mlkit_ms=" + mlMs + " mlkit=" + ml);
            } catch (Throwable t) {
                Log.w(TAG, "DECODE file=" + f.getName() + " error=" + t, t);
            } finally {
                if (bmp != null) {
                    bmp.recycle();
                }
            }
        }
        Log.i(TAG, "DECODE done");
    }
}
