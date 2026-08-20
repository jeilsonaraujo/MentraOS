package com.mentra.asg_client.camera;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.content.res.AssetFileDescriptor;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaPlayer;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Range;
import android.util.Rational;
import android.util.Size;
import android.view.Surface;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LifecycleService;
import com.mentra.asg_client.camera.barcode.BarcodeScanController;
import com.mentra.asg_client.camera.lifecycle.CameraCoordinator;
import com.mentra.asg_client.camera.lifecycle.CameraOpener;
import com.mentra.asg_client.camera.lifecycle.CameraRecoveryHelper;
import com.mentra.asg_client.camera.lifecycle.CameraServiceNotification;
import com.mentra.asg_client.camera.lifecycle.HandlerExecutor;
import com.mentra.asg_client.camera.lifecycle.ImageReaderTwin;
import com.mentra.asg_client.camera.lifecycle.PhotoSession;
import org.json.JSONObject;
import com.mentra.asg_client.camera.lifecycle.VideoRecordingSession;
import com.mentra.asg_client.camera.model.PhotoCaptureSettings;
import com.mentra.asg_client.camera.model.QueuedPhotoRequest;
import com.mentra.asg_client.camera.model.QueuedPhotoRequestQueue;
import com.mentra.asg_client.camera.policy.AeStateMachine;
import com.mentra.asg_client.camera.policy.CameraCapabilities;
import com.mentra.asg_client.camera.policy.FpsRangePolicy;
import com.mentra.asg_client.camera.policy.JpegOrientationResolver;
import com.mentra.asg_client.camera.request.PreviewRequestConfigurator;
import com.mentra.asg_client.io.hardware.core.HardwareManagerFactory;
import com.mentra.asg_client.io.hardware.interfaces.IHardwareManager;
import com.mentra.asg_client.sensors.ImuRecorder;
import com.mentra.asg_client.service.system.core.SystemControllerFactory;
import com.mentra.asg_client.settings.VideoSettings;
import com.mentra.asg_client.utils.WakeLockManager;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class CameraNeoService extends LifecycleService {
    private static final String TAG = "CameraNeo";

    private static final String CHANNEL_ID = "CameraNeoServiceChannel";
    private static final int NOTIFICATION_ID = 1;

    // =======================================================================
    // STATIC STATE MANAGEMENT FOR TRUE SINGLETON PATTERN
    // =======================================================================

    private static final Object SERVICE_LOCK = new Object();

    // =======================================================================

    // Camera variables
    private CaptureRequest.Builder previewBuilder; // Separate builder for preview
    private final CameraCoordinator cameraCoordinator = new CameraCoordinator();
    private Handler backgroundHandler;
    private String cameraId;
    // DIM-560 barcode-scan session state (Phase 0 spike).
    private volatile boolean scanning = false;
    private ImageReader scanPreviewReader;
    private ImageReader scanStillReader;
    private BarcodeScanController scanController;
    private long scanStartedAt = 0L;
    // DIM-560 spike diagnostics: dump a few preview frames to disk so we can SEE what the
    // camera captures (framing/focus/exposure) when a decode isn't happening. Capped.
    private static final boolean SCAN_DUMP_FRAMES = true;
    private static final int SCAN_DUMP_MAX = 8;
    private static final int SCAN_DUMP_EVERY = 10;
    private int scanFrameIndex = 0;
    private int scanDumpsWritten = 0;
    private int scanSizeWidth = SCAN_PREVIEW_WIDTH;
    private int scanSizeHeight = SCAN_PREVIEW_HEIGHT;
    // Slightly-negative AE compensation for scan stills (harder bar contrast, less blur);
    // clamped to the device's supported range at scan start. This device: [-4,+4] @ 1/2 EV.
    private int scanAeCompensation = -4;

    // DIM-560 continuous sweep scan: same open-camera session, but each still is SAVED and
    // ML Kit-decoded on-device, per-frame results written to a session dir, and the run self-stops
    // at a frame cap or on first decode. The camera stays open across frames (no take_photo cold
    // start), so cadence is far higher than the host-driven one-photo-at-a-time loop.
    private volatile boolean sweepActive = false;
    private File sweepDir;
    private int sweepMaxFrames = 35;
    private boolean sweepStopOnFound = true;
    private int sweepAfSettleMs = 200;   // MIN settle before honoring an AF lock (or fixed delay)
    private int sweepFrameCount = 0;
    private int sweepHitFrame = -1;
    private long sweepLastFrameAt = 0L;
    // Focus-lock: instead of a blind fixed delay, wait for the AF to actually converge (sharp
    // frames) before each still — capped by sweepAfMaxMs so a code the AF can't lock still shoots.
    private boolean sweepAfLock = true;
    private int sweepAfMaxMs = 1000;
    // Result-out (DIM-560 BLE bridge): correlation id from the start command + the decoded hit.
    private String sweepRequestId;
    private String sweepHitValue;
    private String sweepHitFormat;
    private boolean sweepResultEmitted = false;

    /**
     * One-shot sink for a sweep's outcome. The BLE command handler sets this so the result is sent
     * back over BLE (and mirrored to adb for the test harness). Cleared after a single emit.
     */
    public interface SweepResultCallback {
        void onSweepResult(org.json.JSONObject result);
    }

    private static volatile SweepResultCallback sSweepResultCallback;

    public static void setSweepResultCallback(SweepResultCallback cb) {
        sSweepResultCallback = cb;
    }

    /** Emit the sweep outcome exactly once (found value, or found=false when exhausted/stopped). */
    private void emitSweepResult(boolean found) {
        if (sweepResultEmitted) {
            return;
        }
        sweepResultEmitted = true;
        SweepResultCallback cb = sSweepResultCallback;
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("type", "barcode_scan_result");
            o.put("found", found);
            if (found) {
                o.put("value", sweepHitValue);
                o.put("format", sweepHitFormat);
            }
            o.put("ts", System.currentTimeMillis());
            o.put("frames", sweepFrameCount);
            o.put("hit_frame", sweepHitFrame);
            if (sweepRequestId != null) {
                o.put("requestId", sweepRequestId);
            }
            Log.i(BarcodeScanController.TAG, "SWEEP result " + o);
            if (cb != null) {
                cb.onSweepResult(o);
            }
        } catch (Exception e) {
            Log.w(BarcodeScanController.TAG, "sweep result emit failed", e);
        } finally {
            sSweepResultCallback = null; // one-shot
        }
    }
    private volatile boolean scanAwaitingAf = false;
    private long scanAfTriggeredAt = 0L;

    // User feedback sounds (assets/): a code locked, or the sweep ended with nothing found.
    private static final String SCAN_SOUND_SUCCESS = "scan_success.wav";
    private static final String SCAN_SOUND_FAIL = "scan_fail.wav";

    /** Fire-and-forget one-shot feedback tone from assets so the user knows the scan's outcome. */
    private void playFeedback(String assetName) {
        // Run on the MAIN looper, not the caller's thread. The sweep decode path
        // calls this immediately before stopScan() quits the camera background
        // thread; a MediaPlayer created on that thread loses its event Looper the
        // instant the thread dies ("sending message to a dead thread" /
        // "finalized without being released") and the clip never actually sounds.
        // The main looper outlives the scan, and the field reference keeps the
        // player alive through playback so it is not GC'd mid-clip.
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                if (feedbackPlayer != null) {
                    try { feedbackPlayer.release(); } catch (Exception ignored) {}
                    feedbackPlayer = null;
                }
                MediaPlayer mp = new MediaPlayer();
                AssetFileDescriptor afd = getAssets().openFd(assetName);
                mp.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
                afd.close();
                mp.setOnCompletionListener(m -> {
                    m.release();
                    if (feedbackPlayer == m) feedbackPlayer = null;
                });
                mp.setOnErrorListener(
                        (m, what, extra) -> {
                            m.release();
                            if (feedbackPlayer == m) feedbackPlayer = null;
                            return true;
                        });
                feedbackPlayer = mp;
                mp.prepare();
                mp.start();
            } catch (Exception e) {
                Log.w(BarcodeScanController.TAG, "feedback sound failed: " + assetName, e);
            }
        });
    }

    /** Held so a feedback clip is not GC'd/torn down mid-playback (see playFeedback). */
    private MediaPlayer feedbackPlayer;

    /** Fires the still capture once (guards against the AF-monitor and the timeout racing). */
    private void fireScanStill() {
        if (!scanAwaitingAf) {
            return;
        }
        scanAwaitingAf = false;
        if (backgroundHandler != null) {
            backgroundHandler.removeCallbacks(scanAfTimeoutRunnable);
        }
        doScanStillCapture();
    }

    private final Runnable scanAfTimeoutRunnable = this::fireScanStill;

    /** Watches AF state on the preview stream; captures the still the moment focus converges. */
    private final CameraCaptureSession.CaptureCallback scanAfMonitor =
            new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(
                        @NonNull CameraCaptureSession s,
                        @NonNull CaptureRequest req,
                        @NonNull TotalCaptureResult result) {
                    if (!scanning || !scanAwaitingAf) {
                        return;
                    }
                    Integer af = result.get(CaptureResult.CONTROL_AF_STATE);
                    long elapsed = System.currentTimeMillis() - scanAfTriggeredAt;
                    boolean locked =
                            af != null
                                    && (af == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
                                            || af == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED);
                    // Require a minimum settle so we don't fire on a stale lock from the prior cycle.
                    if (af == null || (locked && elapsed >= sweepAfSettleMs)) {
                        fireScanStill();
                    }
                }
            };

    // Photo resolution and quality constants are defined in CameraConstants.java

    // JPEG orientation mapping moved to {@link JpegOrientationResolver}.

    // Camera keep-alive settings
    private static final long CAMERA_KEEP_ALIVE_MS =
            3000; // Keep camera open for 3 seconds after photo

    private IHardwareManager hardwareManager;

    // MediaTek vendor-specific camera settings (ZSL, MFNR)
    private CameraSettings mCameraSettings;

    /** Photo capture lifecycle (queue, AE, still/HDR, image save). */
    private PhotoSession photoSession;

    // IMU recorder for bundling sensor data with captured media
    private ImuRecorder mImuRecorder;

    // Camera characteristics for dynamic auto-exposure and autofocus
    private int[] availableAeModes;
    private Range<Integer> exposureCompensationRange;
    private Rational exposureCompensationStep;
    private Range<Integer>[] availableFpsRanges;
    private Range<Integer> selectedFpsRange;

    /** Cached for per-request manual still capture (not persisted). */
    /**
     * Phase 3 prep: bundled AF + manual-sensor capabilities for the currently open camera. Replaces
     * the prior scattered {@code manualSensorSupported}/{@code sensorExposureTimeRange}/ {@code
     * sensorMaxFrameDurationNs}/{@code sensorSensitivityRange}/{@code availableAfModes}/ {@code
     * minimumFocusDistance}/{@code hasAutoFocus} fields. Null until {@link
     * #queryCameraCapabilities} runs.
     */
    private CameraCapabilities cameraCapabilities;

    /** Cached convenience flag mirroring {@link CameraCapabilities#hasContinuousPictureAf}. */
    private boolean hasAutoFocus;

    // Autofocus + manual-sensor capabilities are bundled into {@link #cameraCapabilities}.

    /** Delegates to {@link JpegOrientationResolver#getDisplayRotation(Context)}. */
    private int getDisplayRotation() {
        return JpegOrientationResolver.getDisplayRotation(this);
    }

    // User-settable exposure compensation (apply BEFORE capture, not during)
    private int userExposureCompensation = 0;

    // Electronic Image Stabilization (EIS) state
    private boolean eisEnabled = true; // Enabled by default

    // Callback and execution handling
    private final Executor executor = Executors.newSingleThreadExecutor();

    // Intent action definitions (MOVED TO TOP)
    public static final String ACTION_TAKE_PHOTO = "com.augmentos.camera.ACTION_TAKE_PHOTO";
    public static final String EXTRA_PHOTO_FILE_PATH = "com.augmentos.camera.EXTRA_PHOTO_FILE_PATH";
    public static final String ACTION_START_VIDEO_RECORDING =
            "com.augmentos.camera.ACTION_START_VIDEO_RECORDING";
    public static final String ACTION_STOP_VIDEO_RECORDING =
            "com.augmentos.camera.ACTION_STOP_VIDEO_RECORDING";
    public static final String EXTRA_VIDEO_FILE_PATH = "com.augmentos.camera.EXTRA_VIDEO_FILE_PATH";
    public static final String EXTRA_VIDEO_ID = "com.augmentos.camera.EXTRA_VIDEO_ID";
    public static final String EXTRA_VIDEO_SETTINGS = "com.augmentos.camera.EXTRA_VIDEO_SETTINGS";
    // DIM-560 glasses-native barcode scanner (Phase 0 spike). A preview-only scan session:
    // opens the camera through the same coordinator (so it can't collide with photo/video),
    // runs a continuous YUV preview, and decodes each frame on-device with ZXing.
    public static final String ACTION_START_BARCODE_SCAN =
            "com.augmentos.camera.ACTION_START_BARCODE_SCAN";
    public static final String ACTION_STOP_BARCODE_SCAN =
            "com.augmentos.camera.ACTION_STOP_BARCODE_SCAN";
    // DIM-560 continuous sweep scan: continuous session that saves + ML Kit-decodes each still
    // and writes per-frame results, self-stopping at a frame cap / first decode.
    public static final String ACTION_START_BARCODE_SWEEP =
            "com.augmentos.camera.ACTION_START_BARCODE_SWEEP";
    public static final String EXTRA_SWEEP_DIR = "sweep_dir";
    public static final String EXTRA_SWEEP_MAX = "sweep_max";
    public static final String EXTRA_SWEEP_STOP_ON_FOUND = "sweep_stop_on_found";
    public static final String EXTRA_SWEEP_AF_SETTLE = "sweep_af_settle";
    public static final String EXTRA_SWEEP_AF_LOCK = "sweep_af_lock";
    public static final String EXTRA_SWEEP_AF_MAX = "sweep_af_max";
    public static final String EXTRA_SWEEP_REQUEST_ID = "sweep_request_id";
    // Scan preview resolution — bigger than the 320x240 photo-AE preview so small/1D codes at
    // arm's length stay legible, still cheap to decode.
    private static final int SCAN_PREVIEW_WIDTH = 640;
    private static final int SCAN_PREVIEW_HEIGHT = 480;
    // Delay between full-res snapshots (after the previous one finishes decoding) — the loop
    // is self-paced by decode time, so this is just breathing room for AE/AF.
    private static final long SCAN_STILL_INTERVAL_MS = 120;
    // Still-capture size cap. Codes here are curved/awkward, so favor resolution (8MP) for
    // margin; decode is slower but consensus needs the detail. 4032x3024 is available but
    // 12MP decode is too slow for a snapshot loop.
    private static final int SCAN_STILL_MAX_W = 3264;
    private static final int SCAN_STILL_MAX_H = 2448;
    // Continuous sweep scan uses a smaller still: ML Kit reads reliably at ~1200-2000px (and can
    // silently fail near full-res), and a smaller frame captures + saves faster → higher cadence.
    private static final int SWEEP_STILL_MAX_W = 1920;
    private static final int SWEEP_STILL_MAX_H = 1440;
    // AF settle after the trigger before snapshotting.
    private static final long SCAN_AF_SETTLE_MS = 500;

    // Callback interface for photo capture
    public interface PhotoCaptureCallback {
        default void onPhotoConfigured(JSONObject resolvedConfig) {}

        default void onPhotoCapturing() {}

        default void onPhotoCapturing(
                JSONObject requestedCaptureConfig, JSONObject meteredPreview) {
            onPhotoCapturing();
        }

        default void onPhotoCaptured(String filePath) {
            onPhotoCaptured(filePath, null);
        }

        void onPhotoCaptured(String filePath, @Nullable JSONObject captureMetadata);

        void onPhotoError(String errorMessage);
    }

    // Video recording — owned by VideoRecordingSession (Phase 2.1).
    private VideoRecordingSession videoSession;

    private final PhotoSession.Hooks photoSessionHooks =
            new PhotoSession.Hooks() {
                @Override
                public Object serviceLock() {
                    return SERVICE_LOCK;
                }

                @Override
                public void openCameraInternal(String filePath, boolean forVideo) {
                    CameraNeoService.this.openCameraInternal(filePath, forVideo);
                }

                @Override
                public void closeCamera() {
                    CameraNeoService.this.closeCamera();
                }

                @Override
                public void startKeepAliveTimer() {
                    CameraNeoService.this.startKeepAliveTimer();
                }

                @Override
                public void cancelKeepAliveTimer() {
                    CameraNeoService.this.cancelKeepAliveTimer();
                }

                @Override
                public void wakeUpScreen() {
                    CameraNeoService.this.wakeUpScreen();
                }

                @Override
                public void stopService() {
                    CameraNeoService.this.stopSelf();
                }

                @Override
                public CameraCoordinator coordinator() {
                    return cameraCoordinator;
                }

                @Override
                public CameraCapabilities capabilities() {
                    return cameraCapabilities;
                }

                @Override
                public Range<Integer> selectedFpsRange() {
                    return selectedFpsRange;
                }

                @Override
                public boolean hasAutoFocus() {
                    return hasAutoFocus;
                }

                @Override
                public CameraSettings cameraSettings() {
                    return mCameraSettings;
                }

                @Override
                public Executor executor() {
                    return executor;
                }

                @Override
                public Handler backgroundHandler() {
                    return backgroundHandler;
                }

                @Override
                public int displayRotation() {
                    return getDisplayRotation();
                }

                @Override
                public boolean videoRecording() {
                    return videoSession != null && videoSession.isRecording();
                }

                @Override
                public CaptureRequest.Builder previewBuilder() {
                    return previewBuilder;
                }

                @Override
                public int userExposureCompensation() {
                    return userExposureCompensation;
                }

                @Override
                public ImuRecorder imuRecorderOrNull() {
                    return mImuRecorder;
                }

                @Override
                public ImuRecorder ensureImuRecorder() {
                    if (mImuRecorder == null) {
                        mImuRecorder = new ImuRecorder(CameraNeoService.this);
                    }
                    return mImuRecorder;
                }

                @Override
                public void cancelImuRecording() {
                    if (mImuRecorder != null) {
                        mImuRecorder.cancel();
                    }
                }
            };

    // Static instance for checking camera status
    private static CameraNeoService sInstance;

    /** Interface for video recording callbacks */
    public interface VideoRecordingCallback {
        void onRecordingStarted(String videoId);

        void onRecordingProgress(String videoId, long durationMs);

        void onRecordingStopped(String videoId, String filePath);

        void onRecordingError(String videoId, String errorMessage);
    }

    /**
     * Check if the camera is currently in use for photo capture or video recording. This relies on
     * the service instance being available.
     *
     * <p>IMPORTANT: This returns false when camera is only kept alive for rapid photos, allowing
     * the kept-alive camera to be closed if needed for other operations.
     *
     * @return true if the camera is actively busy, false if idle or just kept alive.
     */
    public static boolean isCameraInUse() {
        if (sInstance != null) {
            // If camera is kept alive but idle (waiting for next photo), don't block other
            // operations
            if (sInstance.cameraCoordinator.isCameraKeptAlive()
                    && sInstance.photoSession.shotState() == AeStateMachine.ShotState.IDLE) {
                // Camera is kept alive but not actively taking a photo
                // This allows other operations to close the camera if needed
                return false;
            }

            boolean recording =
                    sInstance.videoSession != null && sInstance.videoSession.isRecording();

            // Check if a photo capture session is active (actively taking a photo)
            boolean photoSessionActive =
                    (sInstance.cameraCoordinator.device() != null
                            && sInstance.photoSession.imageReaders() != null
                            && !recording
                            && sInstance.photoSession.shotState() != AeStateMachine.ShotState.IDLE);

            // Return true if actively recording video or taking a photo
            return photoSessionActive || recording;
        }
        return false; // Service not running or instance not set
    }

    /**
     * Predicts whether a photo with the given parameters would be a "warm" capture — one that
     * reuses the already-open camera/ISP instead of paying the 1–2s cold startup cost on Mentra
     * Live. Callers use this to choose between a short feedback sound (warm, capture is quick) and
     * a long one (cold, capture lags behind the button press while the camera spins up).
     *
     * <p>A capture is warm when both hold:
     *
     * <ul>
     *   <li>The HAL session is already open. This includes the case where a previous capture is
     *       still in progress — a rapid second press simply queues behind it (see {@code
     *       enqueuePhotoRequest}) and runs without a cold ISP start, so we must NOT gate on the
     *       shot state being idle.
     *   <li>The open session won't be reconfigured for this request. A differing size, SDK flag,
     *       or manual exposure forces a close + reopen (see {@code
     *       PhotoSession#willReuseConfiguredCamera}), which is effectively a cold start.
     * </ul>
     *
     * @param size requested photo size for the upcoming capture (nullable)
     * @param isFromSdk whether the upcoming capture is an SDK request (vs. a button photo)
     * @param exposureTimeNs requested manual exposure for the upcoming capture, or null for auto
     * @return true if the upcoming capture would reuse the open camera; false otherwise.
     */
    public static boolean isCameraWarm(String size, boolean isFromSdk, Long exposureTimeNs) {
        // Read the open-session state under SERVICE_LOCK — the same lock enqueuePhotoRequest()
        // holds — so this prediction is consistent with the state that request will actually see.
        // Without it, a keep-alive expiry / closeCamera() on the background thread could tear down
        // the HAL session between this read and the enqueue, making the short "hot" cue play for a
        // capture that ends up cold-starting.
        synchronized (SERVICE_LOCK) {
            return sInstance != null
                    && sInstance.cameraCoordinator.hasConfiguredCamera()
                    && sInstance.photoSession.willReuseConfiguredCamera(
                            size, isFromSdk, exposureTimeNs);
        }
    }

    /**
     * Force close the camera if it's only kept alive (not actively in use). This is called when
     * other operations like video/streaming need the camera.
     *
     * @return true if camera was closed, false if camera was busy or not open
     */
    public static boolean closeKeptAliveCamera() {
        if (sInstance != null
                && sInstance.cameraCoordinator.isCameraKeptAlive()
                && sInstance.photoSession.shotState() == AeStateMachine.ShotState.IDLE) {
            Log.d(TAG, "Force closing kept-alive camera for other operation");
            sInstance.cameraCoordinator.closeIfKeptAlive(sInstance::closeCamera);
            sInstance.stopSelf();
            return true;
        }
        return false;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // Initialize hardware manager for LED control
        hardwareManager = HardwareManagerFactory.getInstance(this);
        // Initialize camera settings for vendor-specific features (ZSL, MFNR)
        mCameraSettings = new CameraSettings(this);
        photoSession = new PhotoSession(photoSessionHooks);
        synchronized (SERVICE_LOCK) {
            Log.d(TAG, "CameraNeoService Camera2 service created");
            sInstance = this;
        }
        Log.i(
                TAG,
                "📹 Initializing EIS (Electronic Image Stabilization) - Default state: "
                        + (eisEnabled ? "ENABLED" : "DISABLED"));

        createNotificationChannel();
        showNotification("Camera Service", "Service is running");
        startBackgroundThread();

        // Phase 2.1: video session owns MediaRecorder, recorder surface, IMU sync, timer.
        videoSession = new VideoRecordingSession(this, backgroundHandler, executor, videoHooks);
    }

    /** Bridges {@link VideoRecordingSession} back into the camera service lifecycle. */
    private final VideoRecordingSession.Hooks videoHooks =
            new VideoRecordingSession.Hooks() {
                @Override
                public ImuRecorder ensureImuRecorder() {
                    if (mImuRecorder == null) {
                        mImuRecorder = new ImuRecorder(CameraNeoService.this);
                    }
                    return mImuRecorder;
                }

                @Override
                public ImuRecorder currentImuRecorder() {
                    return mImuRecorder;
                }

                @Override
                public int videoOrientation() {
                    int displayOrientation = getDisplayRotation();
                    return JpegOrientationResolver.lookupJpegOrientation(
                            displayOrientation, JpegOrientationResolver.DEFAULT_VIDEO_ORIENTATION);
                }

                @Override
                public void onSessionTerminated() {
                    closeCamera();
                    conditionalStopSelf();
                }
            };

    /** Bridge {@link VideoRecordingCallback} → {@link VideoRecordingSession.Callback}. */
    private final VideoRecordingSession.Callback videoSessionCallback =
            new VideoRecordingSession.Callback() {
                @Override
                public void onRecordingStarted(String videoId) {
                    VideoRecordingCallback cb = VideoRecordingSession.pendingVideoCallback();
                    if (cb != null) cb.onRecordingStarted(videoId);
                }

                @Override
                public void onRecordingProgress(String videoId, long durationMs) {
                    VideoRecordingCallback cb = VideoRecordingSession.pendingVideoCallback();
                    if (cb != null) cb.onRecordingProgress(videoId, durationMs);
                }

                @Override
                public void onRecordingStopped(String videoId, String filePath) {
                    VideoRecordingCallback cb = VideoRecordingSession.pendingVideoCallback();
                    if (cb != null) cb.onRecordingStopped(videoId, filePath);
                }

                @Override
                public void onRecordingError(String videoId, String errorMessage) {
                    VideoRecordingCallback cb = VideoRecordingSession.pendingVideoCallback();
                    if (cb != null) cb.onRecordingError(videoId, errorMessage);
                }
            };

    /**
     * Primary entry point for photo requests - uses global queue to prevent race conditions This
     * method immediately queues the request and ensures only one service instance exists
     *
     * @param context Application context
     * @param filePath File path to save the photo
     * @param size Photo size (small/medium/large)
     * @param enableLed Whether to enable LED flash for this photo
     * @param isFromSdk true for SDK photos (optimized sizes), false for button photos (high
     *     quality)
     * @param exposureTimeNs optional sensor exposure time in nanoseconds for this shot only; {@code
     *     null} = auto exposure
     * @param callback Callback to be notified when photo is captured
     */
    public static void enqueuePhotoRequest(
            Context context,
            String filePath,
            String size,
            boolean enableLed,
            boolean isFromSdk,
            Long exposureTimeNs,
            PhotoCaptureCallback callback) {
        enqueuePhotoRequest(
                context, filePath, size, enableLed, isFromSdk, exposureTimeNs, null, callback);
    }

    /**
     * Primary entry point for photo requests - uses global queue to prevent race conditions.
     *
     * @param iso optional sensor sensitivity for manual exposure captures only; {@code null} =
     *     derive ISO from preview metering
     */
    public static void enqueuePhotoRequest(
            Context context,
            String filePath,
            String size,
            boolean enableLed,
            boolean isFromSdk,
            Long exposureTimeNs,
            Integer iso,
            PhotoCaptureCallback callback) {
        enqueuePhotoRequest(
                context,
                filePath,
                size,
                enableLed,
                isFromSdk,
                exposureTimeNs,
                iso,
                PhotoCaptureSettings.EMPTY,
                callback);
    }

    public static void enqueuePhotoRequest(
            Context context,
            String filePath,
            String size,
            boolean enableLed,
            boolean isFromSdk,
            Long exposureTimeNs,
            Integer iso,
            PhotoCaptureSettings captureSettings,
            PhotoCaptureCallback callback) {
        synchronized (SERVICE_LOCK) {
            // Create and queue the request immediately
            QueuedPhotoRequest request =
                    new QueuedPhotoRequest(
                            filePath,
                            size,
                            enableLed,
                            isFromSdk,
                            exposureTimeNs,
                            iso,
                            captureSettings,
                            callback);
            QueuedPhotoRequestQueue.getInstance().offer(request);

            Log.d(
                    TAG,
                    "📸 Enqueued photo request: "
                            + request.requestId
                            + " | Queue size: "
                            + QueuedPhotoRequestQueue.getInstance().size()
                            + " | Service active: "
                            + (sInstance != null));
            Log.i(
                    TAG,
                    "SCAN_PARAMS enqueued requestId="
                            + request.requestId
                            + " isFromSdk="
                            + isFromSdk
                            + " size="
                            + size
                            + " exposureTimeNs="
                            + exposureTimeNs
                            + " iso="
                            + iso
                            + " captureTuning={"
                            + (captureSettings != null
                                    ? captureSettings.describeForLog()
                                    : "null")
                            + "}");

            // Check current service state and act accordingly
            boolean cameraReady =
                    sInstance != null && sInstance.cameraCoordinator.hasConfiguredCamera();
            if (cameraReady) {
                // Fast path - camera is ready, check if idle
                if (sInstance.photoSession.shotState() == AeStateMachine.ShotState.IDLE) {
                    Log.d(TAG, "Camera ready and idle - processing request immediately");
                    // Cancel any pending keep-alive timer to prevent it from closing camera
                    // mid-capture
                    sInstance.cancelKeepAliveTimer();
                    sInstance.dispatchNextPhotoRequest();
                } else {
                    Log.d(
                            TAG,
                            "Camera ready but busy (state: "
                                    + sInstance.photoSession.shotState()
                                    + ") - request queued");
                }
            } else if (sInstance != null) {
                // Service exists but camera/session is not ready yet.
                Log.d(
                        TAG,
                        "Service active but camera not ready - request will be processed when"
                                + " ready");
            } else {
                // Need to start the service
                Log.d(TAG, "Starting service to process photo request");

                Intent intent = new Intent(context, CameraNeoService.class);
                intent.setAction(ACTION_TAKE_PHOTO);
                intent.putExtra("USE_GLOBAL_QUEUE", true);
                context.startForegroundService(intent);
            }
        }
    }

    /**
     * Legacy method - redirects to enqueuePhotoRequest for backward compatibility Defaults to SDK
     * photo (isFromSdk=true) for optimized transfer sizes
     *
     * @deprecated Use enqueuePhotoRequest instead
     */
    @Deprecated
    public static void takePictureWithCallback(
            Context context, String filePath, PhotoCaptureCallback callback) {
        enqueuePhotoRequest(context, filePath, null, false, true, null, callback);
    }

    /**
     * Start video recording and get notified through callback
     *
     * @param context Application context
     * @param videoId Unique ID for this video recording session
     * @param filePath File path to save the video
     * @param callback Callback for recording events
     */
    public static void startVideoRecording(
            Context context, String videoId, String filePath, VideoRecordingCallback callback) {
        startVideoRecording(context, videoId, filePath, null, callback);
    }

    /**
     * Start video recording with custom settings
     *
     * @param context Application context
     * @param videoId Unique ID for this video recording session
     * @param filePath File path to save the video
     * @param settings Video settings (resolution, fps) or null for defaults
     * @param callback Callback for recording events
     */
    public static void startVideoRecording(
            Context context,
            String videoId,
            String filePath,
            VideoSettings settings,
            VideoRecordingCallback callback) {
        VideoRecordingSession.setPendingVideoCallback(callback);

        Intent intent = new Intent(context, CameraNeoService.class);
        intent.setAction(ACTION_START_VIDEO_RECORDING);
        intent.putExtra(EXTRA_VIDEO_ID, videoId);
        intent.putExtra(EXTRA_VIDEO_FILE_PATH, filePath);
        if (settings != null) {
            intent.putExtra(EXTRA_VIDEO_SETTINGS + "_width", settings.width);
            intent.putExtra(EXTRA_VIDEO_SETTINGS + "_height", settings.height);
            intent.putExtra(EXTRA_VIDEO_SETTINGS + "_fps", settings.fps);
        }
        context.startForegroundService(intent);
    }

    /**
     * Stop the current video recording session
     *
     * @param context Application context
     * @param videoId ID of the video recording session to stop (must match active session)
     */
    public static void stopVideoRecording(Context context, String videoId) {
        Intent intent = new Intent(context, CameraNeoService.class);
        intent.setAction(ACTION_STOP_VIDEO_RECORDING);
        intent.putExtra(EXTRA_VIDEO_ID, videoId);
        context.startForegroundService(intent);
    }

    /** DIM-560 spike: start on-glasses barcode/QR scanning (preview-only, decode on-device). */
    public static void startBarcodeScan(Context context) {
        Intent intent = new Intent(context, CameraNeoService.class);
        intent.setAction(ACTION_START_BARCODE_SCAN);
        context.startForegroundService(intent);
    }

    /** DIM-560 spike: stop barcode scanning and release the camera. */
    public static void stopBarcodeScan(Context context) {
        Intent intent = new Intent(context, CameraNeoService.class);
        intent.setAction(ACTION_STOP_BARCODE_SCAN);
        context.startForegroundService(intent);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);

        if (intent != null && intent.getAction() != null) {
            String action = intent.getAction();
            Log.d(TAG, "CameraNeoService received action: " + action);

            switch (action) {
                case ACTION_TAKE_PHOTO:
                    // Phase 1: only the global-queue path is wired up via enqueuePhotoRequest().
                    // The legacy intent-extras path (USE_GLOBAL_QUEUE=false) had zero callers and
                    // was
                    // removed; CameraNeoService is always started via the queue dispatcher now.
                    Log.d(TAG, "Processing photo requests from global queue");
                    dispatchNextPhotoRequest();
                    break;
                case ACTION_START_VIDEO_RECORDING:
                    {
                        String videoId = intent.getStringExtra(EXTRA_VIDEO_ID);
                        String videoPath = intent.getStringExtra(EXTRA_VIDEO_FILE_PATH);
                        if (videoPath == null || videoPath.isEmpty()) {
                            String timeStamp =
                                    new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                                            .format(new Date());
                            String videoCaptureDir = "VID_" + timeStamp;
                            File videoCaptureDirFile =
                                    new File(getExternalFilesDir(null), videoCaptureDir);
                            videoCaptureDirFile.mkdirs();
                            videoPath = new File(videoCaptureDirFile, "base.mp4").getAbsolutePath();
                        }
                        int width = intent.getIntExtra(EXTRA_VIDEO_SETTINGS + "_width", 0);
                        int height = intent.getIntExtra(EXTRA_VIDEO_SETTINGS + "_height", 0);
                        int fps = intent.getIntExtra(EXTRA_VIDEO_SETTINGS + "_fps", 0);
                        VideoSettings settings =
                                (width > 0 && height > 0 && fps > 0)
                                        ? new VideoSettings(width, height, fps)
                                        : null;
                        if (settings != null) {
                            Log.d(TAG, "Using custom video settings: " + settings);
                        }
                        SystemControllerFactory.get(this).setEisEnabled(true);
                        setupCameraAndStartRecording(videoId, videoPath, settings);
                        break;
                    }
                case ACTION_STOP_VIDEO_RECORDING:
                    String videoIdToStop = intent.getStringExtra(EXTRA_VIDEO_ID);
                    videoSession.stopRecording(videoIdToStop);
                    SystemControllerFactory.get(this).setEisEnabled(false);
                    break;
                case ACTION_START_BARCODE_SCAN:
                    sweepActive = false;
                    openCameraForScan();
                    break;
                case ACTION_START_BARCODE_SWEEP:
                    startBarcodeSweep(intent);
                    break;
                case ACTION_STOP_BARCODE_SCAN:
                    stopScan();
                    break;
            }
        }
        return START_STICKY;
    }

    private void dispatchNextPhotoRequest() {
        photoSession.dispatchNextPhotoRequest();
    }

    private void setupCameraForQueuedRequest(QueuedPhotoRequest request) {
        photoSession.setupCameraForQueuedRequest(request);
    }

    private void setupCameraAndStartRecording(
            String videoId, String filePath, VideoSettings settings) {
        videoSession.setCallback(videoSessionCallback);
        if (!videoSession.prepareRequest(videoId, filePath, settings)) {
            notifyVideoError(videoId, "Already recording another video.");
            return;
        }
        wakeUpScreen();
        openCameraInternal(filePath, true); // true indicates for video
    }

    /** Conditional stop self. */
    private void conditionalStopSelf() {
        stopSelf();
    }

    @SuppressLint("MissingPermission")
    private void openCameraInternal(String filePath, boolean forVideo) {
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        if (manager == null) {
            Log.e(TAG, "Could not get camera manager");
            if (forVideo)
                notifyVideoError(videoSession.currentVideoId(), "Camera service unavailable");
            else photoSession.notifyHostPhotoError("Camera service unavailable");
            conditionalStopSelf();
            return;
        }

        try {
            // First check if camera permission is granted
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                int cameraPermission = checkSelfPermission(android.Manifest.permission.CAMERA);
                if (cameraPermission != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    Log.e(TAG, "Camera permission not granted");
                    if (forVideo)
                        notifyVideoError(
                                videoSession.currentVideoId(), "Camera permission not granted");
                    else photoSession.notifyHostPhotoError("Camera permission not granted");
                    conditionalStopSelf();
                    return;
                }
            }

            this.cameraId = CameraOpener.selectPrimaryCameraId(manager);

            // Verify that we have a valid camera ID
            if (this.cameraId == null) {
                if (forVideo)
                    notifyVideoError(videoSession.currentVideoId(), "No suitable camera found");
                else photoSession.notifyHostPhotoError("No suitable camera found");
                conditionalStopSelf();
                return;
            }

            // Get characteristics for the selected camera
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(this.cameraId);

            // Initialize MediaTek vendor keys for ZSL/MFNR (if available)
            if (mCameraSettings != null) {
                mCameraSettings.init(characteristics);
                boolean zslSupported = mCameraSettings.isZslSupported();
                boolean mfnrSupported = mCameraSettings.isMfnrSupported();
                Log.d(
                        TAG,
                        "Vendor feature support - ZSL: "
                                + zslSupported
                                + ", MFNR: "
                                + mfnrSupported);
            }

            // Query camera capabilities for dynamic auto-exposure
            queryCameraCapabilities(characteristics);

            // Check if this camera supports JPEG format
            StreamConfigurationMap map = CameraOpener.streamMapOrNull(characteristics);
            if (map == null) {
                if (forVideo)
                    notifyVideoError(
                            videoSession.currentVideoId(),
                            "Camera " + this.cameraId + " doesn't support configuration maps");
                else
                    photoSession.notifyHostPhotoError(
                            "Camera " + this.cameraId + " doesn't support configuration maps");
                stopSelf();
                return;
            }

            // If this is for video, set up video size only
            if (forVideo) {
                // Find a suitable video size
                Size[] videoSizes = CameraOpener.videoOutputSizes(map);

                if (videoSizes == null || videoSizes.length == 0) {
                    notifyVideoError(
                            videoSession.currentVideoId(), "Camera doesn't support MediaRecorder");
                    conditionalStopSelf();
                    return;
                }

                // Log available video sizes with detailed analysis
                Log.i(
                        TAG,
                        "📹 VIDEO RESOLUTION DEBUG - Available video sizes for camera "
                                + this.cameraId
                                + " ("
                                + videoSizes.length
                                + " options):");
                boolean has1080p = false;
                boolean has720p = false;
                boolean has4K = false;
                for (Size size : videoSizes) {
                    String marker = "";
                    if (size.getWidth() == 1920 && size.getHeight() == 1080) {
                        has1080p = true;
                        marker = " ← 1080p";
                    } else if (size.getWidth() == 1280 && size.getHeight() == 720) {
                        has720p = true;
                        marker = " ← 720p";
                    } else if (size.getWidth() == 3840 && size.getHeight() == 2160) {
                        has4K = true;
                        marker = " ← 4K";
                    }
                    Log.i(TAG, "  " + size.getWidth() + "x" + size.getHeight() + marker);
                }
                Log.i(
                        TAG,
                        "📹 Resolution support: 4K="
                                + has4K
                                + ", 1080p="
                                + has1080p
                                + ", 720p="
                                + has720p);

                Size chosenVideoSize =
                        CameraOpener.resolveVideoSize(videoSizes, videoSession.pendingSettings());
                if (chosenVideoSize == null) {
                    notifyVideoError(
                            videoSession.currentVideoId(), "Camera doesn't support MediaRecorder");
                    conditionalStopSelf();
                    return;
                }

                videoSession.setVideoSize(chosenVideoSize);
                try {
                    videoSession.setupMediaRecorder();
                } catch (IOException ioe) {
                    Log.e(TAG, "Error setting up MediaRecorder", ioe);
                    notifyVideoError(
                            videoSession.currentVideoId(),
                            "Failed to set up video recorder: " + ioe.getMessage());
                }
            } else {
                // For photos, find the closest available JPEG size to our target
                Size[] jpegSizes = CameraOpener.jpegOutputSizes(map);
                if (jpegSizes != null) {
                    Log.d(TAG, "AAACamera " + this.cameraId + " JPEG output sizes: " + Arrays.toString(jpegSizes));
                    for (Size size : jpegSizes) {
                        Log.d(TAG, "Camera " + this.cameraId + " JPEG size: " +
                            size.getWidth() + "x" + size.getHeight());
                    }
                }
                if (jpegSizes == null || jpegSizes.length == 0) {
                    photoSession.notifyHostPhotoError("Camera doesn't support JPEG format");
                    stopSelf();
                    return;
                }

                boolean fromSdk = photoSession.photoRequestFromSdk();
                String requestedSizeTier = photoSession.photoRequestSizeTier();
                Log.d(
                        TAG,
                        fromSdk
                                ? "SDK photo - using optimized resolution"
                                : "Button photo - using high quality resolution");
                Size chosenJpeg =
                        CameraOpener.resolveJpegSize(jpegSizes, fromSdk, requestedSizeTier);
                if (chosenJpeg == null) {
                    photoSession.notifyHostPhotoError("Camera doesn't support JPEG format");
                    stopSelf();
                    return;
                }

                // Phase 0: preview + still readers are siblings. Still reader is the ONLY target of
                // explicit cameraCaptureSession.capture() calls; preview repeating request targets
                // the
                // small YUV preview reader, so manual-exposure captures no longer compete with
                // auto-exposed
                // preview frames in the same buffer queue.
                photoSession.setJpegSize(chosenJpeg);
                photoSession.prepareStillReaders(filePath, chosenJpeg, backgroundHandler);
            }

            // Open the camera
            if (!cameraCoordinator.tryAcquireOpenCloseLock(2500)) {
                throw new RuntimeException("Time out waiting to lock camera opening.");
            }

            Log.d(TAG, "Opening camera ID: " + this.cameraId);
            manager.openCamera(
                    this.cameraId, newCameraOpenStateCallback(forVideo), backgroundHandler);

        } catch (CameraAccessException e) {
            // Handle camera access exceptions more specifically
            Log.e(TAG, "Camera access exception: " + e.getReason(), e);
            String errorMsg = "Could not access camera";

            // Check for specific error reasons
            if (e.getReason() == CameraAccessException.CAMERA_DISABLED) {
                errorMsg =
                        "Camera disabled by policy - please check camera permissions in Settings";
                // Try to recover by restarting the camera service
                Log.d(TAG, "Attempting to restart camera service in safe mode");
                restartCameraServiceIfNeeded();
            } else if (e.getReason() == CameraAccessException.CAMERA_ERROR) {
                errorMsg = "Camera device encountered an error";
            } else if (e.getReason() == CameraAccessException.CAMERA_IN_USE) {
                errorMsg = "Camera is already in use by another app";
                // Try to close other camera sessions
                releaseCameraResources();
            }

            if (forVideo) notifyVideoError(videoSession.currentVideoId(), errorMsg);
            else photoSession.notifyHostPhotoError(errorMsg);
            stopSelf();
        } catch (InterruptedException e) {
            Log.e(TAG, "Interrupted while trying to lock camera", e);
            photoSession.notifyHostPhotoError("Camera operation interrupted");
            stopSelf();
        } catch (Exception e) {
            Log.e(TAG, "Error setting up camera", e);
            photoSession.notifyHostPhotoError("Error setting up camera: " + e.getMessage());
            stopSelf();
        }
    }

    /**
     * Single camera-open callback for both photo and video; behavior matches the former {@code
     * photoStateCallback} / {@code videoStateCallback} pair (Phase 2f prep).
     */
    private CameraDevice.StateCallback newCameraOpenStateCallback(final boolean forVideo) {
        return new CameraDevice.StateCallback() {
            @Override
            public void onOpened(@NonNull CameraDevice camera) {
                Log.d(TAG, "Camera device opened successfully");
                cameraCoordinator.releaseOpenCloseLock();
                cameraCoordinator.setDevice(camera);

                createCameraSessionInternal(forVideo);
            }

            @Override
            public void onDisconnected(@NonNull CameraDevice camera) {
                Log.d(TAG, "Camera device disconnected");
                cameraCoordinator.releaseOpenCloseLock();
                camera.close();
                cameraCoordinator.clearDevice();
                if (forVideo) {
                    notifyVideoError(videoSession.currentVideoId(), "Camera disconnected");
                } else {
                    photoSession.notifyHostPhotoError("Camera disconnected");
                }
                stopSelf();
            }

            @Override
            public void onError(@NonNull CameraDevice camera, int error) {
                Log.e(TAG, "Camera device error: " + error);
                cameraCoordinator.releaseOpenCloseLock();
                camera.close();
                cameraCoordinator.clearDevice();
                if (forVideo) {
                    notifyVideoError(
                            videoSession.currentVideoId(), "Camera device error: " + error);
                } else {
                    photoSession.notifyHostPhotoError("Camera device error: " + error);
                }
                stopSelf();
            }
        };
    }

    // ===================================================================================
    // DIM-560 — barcode scan session (Phase 0 spike)
    //
    // A preview-only camera session, deliberately independent of the photo/video paths:
    // its own YUV ImageReader + repeating TEMPLATE_PREVIEW request, decoded on-device by
    // ZXing. It goes through the SAME cameraCoordinator open/close lock as photo/video, so
    // the three modes can never own the camera at once. No still capture, nothing saved.
    // ===================================================================================

    /**
     * DIM-560 continuous sweep scan: start the open-camera scan session in sweep mode — each
     * still is saved + ML Kit-decoded on-device, per-frame results are written to {@code sweepDir},
     * and the run self-stops at {@code sweep_max} frames or on first decode. Reuses the whole scan
     * session; only decodeScanFrame branches on {@link #sweepActive}.
     */
    private void startBarcodeSweep(Intent intent) {
        if (scanning) {
            Log.i(BarcodeScanController.TAG, "scan already active — ignoring sweep start");
            return;
        }
        String dir = intent.getStringExtra(EXTRA_SWEEP_DIR);
        if (dir == null || dir.isEmpty()) {
            dir = new File(getExternalFilesDir(null), "barcode_sweep").getAbsolutePath();
        }
        sweepDir = new File(dir);
        if (!sweepDir.exists()) {
            sweepDir.mkdirs();
        }
        sweepMaxFrames = Math.max(1, intent.getIntExtra(EXTRA_SWEEP_MAX, 35));
        sweepStopOnFound = intent.getBooleanExtra(EXTRA_SWEEP_STOP_ON_FOUND, true);
        sweepAfSettleMs = Math.max(0, intent.getIntExtra(EXTRA_SWEEP_AF_SETTLE, 200));
        sweepAfLock = intent.getBooleanExtra(EXTRA_SWEEP_AF_LOCK, true);
        sweepAfMaxMs = Math.max(sweepAfSettleMs, intent.getIntExtra(EXTRA_SWEEP_AF_MAX, 1000));
        sweepRequestId = intent.getStringExtra(EXTRA_SWEEP_REQUEST_ID);
        sweepHitValue = null;
        sweepHitFormat = null;
        sweepResultEmitted = false;
        sweepFrameCount = 0;
        sweepHitFrame = -1;
        sweepLastFrameAt = 0L;
        scanAwaitingAf = false;
        sweepActive = true;
        writeSweepStatus(false);
        Log.i(BarcodeScanController.TAG, "sweep scan start dir=" + sweepDir
                + " max=" + sweepMaxFrames + " stopOnFound=" + sweepStopOnFound
                + " afSettle=" + sweepAfSettleMs);
        openCameraForScan();
    }

    /** One sweep frame: save the still, ML Kit-decode it, append a per-frame record, bound the run. */
    private void handleSweepFrame(Image image) {
        int width = image.getWidth();
        int height = image.getHeight();
        long now = System.currentTimeMillis();
        long sincePrev = sweepLastFrameAt == 0 ? 0 : (now - sweepLastFrameAt);
        sweepLastFrameAt = now;
        sweepFrameCount++;
        int n = sweepFrameCount;

        byte[] nv21 = yuv420ToNv21(image);
        long t0 = System.currentTimeMillis();
        java.util.List<String> raw =
                scanController != null
                        ? scanController.decodeMlKitRaw(nv21, width, height)
                        : new java.util.ArrayList<>();
        long decodeMs = System.currentTimeMillis() - t0;
        boolean found = !raw.isEmpty();

        // Save the frame JPEG (same NV21 -> JPEG path as the diagnostic dump).
        String file = "frame_" + String.format(java.util.Locale.US, "%02d", n) + ".jpg";
        try {
            YuvImage yuv = new YuvImage(nv21, ImageFormat.NV21, width, height, null);
            File out = new File(sweepDir, file);
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
                yuv.compressToJpeg(new Rect(0, 0, width, height), 90, fos);
            }
        } catch (Exception e) {
            Log.w(BarcodeScanController.TAG, "sweep frame save failed", e);
        }

        // Append a per-frame JSONL record.
        try {
            org.json.JSONArray vals = new org.json.JSONArray();
            for (String r : raw) {
                int bar = r.indexOf('|');
                org.json.JSONObject v = new org.json.JSONObject();
                v.put("format", bar >= 0 ? r.substring(0, bar) : "?");
                v.put("value", bar >= 0 ? r.substring(bar + 1) : r);
                vals.put(v);
            }
            org.json.JSONObject rec = new org.json.JSONObject();
            rec.put("n", n).put("ts", now).put("since_prev_ms", sincePrev)
                    .put("decode_ms", decodeMs).put("w", width).put("h", height)
                    .put("found", found).put("file", file).put("values", vals);
            try (java.io.FileWriter w = new java.io.FileWriter(new File(sweepDir, "results.jsonl"), true)) {
                w.write(rec.toString() + "\n");
            }
        } catch (Exception e) {
            Log.w(BarcodeScanController.TAG, "sweep record write failed", e);
        }

        if (found && sweepHitFrame < 0) {
            sweepHitFrame = n;
            // Remember the first decoded code ("FORMAT|value") for the BLE result.
            String first = raw.get(0);
            int bar = first.indexOf('|');
            sweepHitFormat = bar >= 0 ? first.substring(0, bar) : "?";
            sweepHitValue = bar >= 0 ? first.substring(bar + 1) : first;
            playFeedback(SCAN_SOUND_SUCCESS);  // first lock — tell the user it worked
        }
        Log.i(BarcodeScanController.TAG, "SWEEP n=" + n + " dt=" + sincePrev
                + "ms decode=" + decodeMs + "ms found=" + found + " " + raw);

        boolean stop = (sweepStopOnFound && found) || n >= sweepMaxFrames;
        // Sweep exhausted with nothing found → failure feedback so the user isn't left guessing.
        if (stop && sweepHitFrame < 0) {
            playFeedback(SCAN_SOUND_FAIL);
        }
        writeSweepStatus(stop);
        if (stop) {
            emitSweepResult(sweepHitFrame > 0);  // send the outcome over BLE (one-shot)
            if (backgroundHandler != null) {
                backgroundHandler.post(this::stopScan);
            }
        }
    }

    /** Write the live status file the host polls: frames done, hit frame, done flag. */
    private void writeSweepStatus(boolean done) {
        if (sweepDir == null) {
            return;
        }
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("frames", sweepFrameCount);
            o.put("max_frames", sweepMaxFrames);
            o.put("found", sweepHitFrame > 0);
            o.put("hit_frame", sweepHitFrame);
            o.put("done", done);
            File tmp = new File(sweepDir, "status.json.tmp");
            try (java.io.FileWriter w = new java.io.FileWriter(tmp)) {
                w.write(o.toString());
            }
            tmp.renameTo(new File(sweepDir, "status.json"));
        } catch (Exception e) {
            Log.w(BarcodeScanController.TAG, "sweep status write failed", e);
        }
    }

    @SuppressLint("MissingPermission")
    private void openCameraForScan() {
        if (scanning) {
            Log.i(BarcodeScanController.TAG, "scan already active — ignoring start");
            return;
        }
        // Wake the device + acquire CPU/screen wake locks before opening the camera.
        // On an idle/asleep device the camera open is rejected with "disabled by
        // policy" (ERROR_CAMERA_DISABLED) → the sweep returns 0 frames. The
        // photo/video path already does this (wakeUpScreen() before
        // openCameraInternal); the scan path omitting it is why a hands-free
        // "start scan" captured nothing while the glasses sat idle/asleep.
        wakeUpScreen();
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        if (manager == null) {
            Log.e(BarcodeScanController.TAG, "Camera service unavailable");
            conditionalStopSelf();
            return;
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M
                && checkSelfPermission(android.Manifest.permission.CAMERA)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.e(BarcodeScanController.TAG, "Camera permission not granted");
            conditionalStopSelf();
            return;
        }
        try {
            this.cameraId = CameraOpener.selectPrimaryCameraId(manager);
            if (this.cameraId == null) {
                Log.e(BarcodeScanController.TAG, "No suitable camera found");
                conditionalStopSelf();
                return;
            }

            scanController =
                    new BarcodeScanController(
                            (value, format) ->
                                    // Phase 1 will forward this over BLE; Phase 0 just proves the
                                    // decode loop, so the controller already logged it.
                                    Log.d(
                                            BarcodeScanController.TAG,
                                            "sink: " + format + " -> " + value));

            // This HAL only tolerates a SMALL repeating stream (it crashes — "camera provider
            // has died" — above ~640x480). So: a tiny repeating preview drives AE/AF, and the
            // real resolution comes from periodic full-res STILL captures that we decode. This
            // matches the product model (say "scan" -> snapshots -> find any code anywhere).
            scanPreviewReader =
                    ImageReader.newInstance(
                            SCAN_PREVIEW_WIDTH, SCAN_PREVIEW_HEIGHT, ImageFormat.YUV_420_888, 2);
            scanPreviewReader.setOnImageAvailableListener(
                    reader -> {
                        // Preview only keeps AE/AF alive; drain it, don't decode.
                        try (Image image = reader.acquireLatestImage()) {
                            // discard
                        } catch (RuntimeException ignored) {
                        }
                    },
                    backgroundHandler);

            // Still reader: the largest LANDSCAPE YUV size within the cap. Still capture
            // supports the big sizes (unlike the repeating preview) — this is what we decode.
            Size stillSize = new Size(SCAN_PREVIEW_WIDTH, SCAN_PREVIEW_HEIGHT);
            try {
                CameraCharacteristics ch = manager.getCameraCharacteristics(cameraId);
                StreamConfigurationMap scMap =
                        ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (scMap != null) {
                    Size[] yuvSizes = scMap.getOutputSizes(ImageFormat.YUV_420_888);
                    if (yuvSizes != null && yuvSizes.length > 0) {
                        Log.i(
                                BarcodeScanController.TAG,
                                "available YUV_420_888 sizes: " + Arrays.toString(yuvSizes));
                        int capW = sweepActive ? SWEEP_STILL_MAX_W : SCAN_STILL_MAX_W;
                        int capH = sweepActive ? SWEEP_STILL_MAX_H : SCAN_STILL_MAX_H;
                        Size best = null;
                        for (Size s : yuvSizes) {
                            if (s.getWidth() > capW
                                    || s.getHeight() > capH
                                    || s.getWidth() < s.getHeight()) {
                                continue;
                            }
                            long area = (long) s.getWidth() * s.getHeight();
                            if (best == null
                                    || area > (long) best.getWidth() * best.getHeight()) {
                                best = s;
                            }
                        }
                        if (best != null) {
                            stillSize = best;
                        }
                    }
                }
                // Clamp the scan AE compensation to what this camera supports.
                android.util.Range<Integer> aeRange =
                        ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
                if (aeRange != null) {
                    scanAeCompensation =
                            Math.max(aeRange.getLower(), Math.min(scanAeCompensation, aeRange.getUpper()));
                }
            } catch (Exception e) {
                Log.w(BarcodeScanController.TAG, "still YUV size query failed; using default", e);
            }
            scanSizeWidth = stillSize.getWidth();
            scanSizeHeight = stillSize.getHeight();
            Log.i(
                    BarcodeScanController.TAG,
                    "scan STILL resolution: "
                            + scanSizeWidth
                            + "x"
                            + scanSizeHeight
                            + " (preview "
                            + SCAN_PREVIEW_WIDTH
                            + "x"
                            + SCAN_PREVIEW_HEIGHT
                            + ")");
            scanStillReader =
                    ImageReader.newInstance(
                            scanSizeWidth, scanSizeHeight, ImageFormat.YUV_420_888, 2);
            scanStillReader.setOnImageAvailableListener(
                    reader -> {
                        try (Image image = reader.acquireLatestImage()) {
                            if (image != null && scanning) {
                                decodeScanFrame(image);
                            }
                        } catch (RuntimeException e) {
                            Log.w(BarcodeScanController.TAG, "still acquire/decode fault", e);
                        }
                        // Self-paced: schedule the next snapshot only after this one decoded.
                        if (scanning && backgroundHandler != null) {
                            backgroundHandler.postDelayed(
                                    this::captureScanStill, SCAN_STILL_INTERVAL_MS);
                        }
                    },
                    backgroundHandler);

            // EIS adds HAL load/heat (the streaming path disables it too); a scanner doesn't
            // need stabilization. Helps HAL stability at higher preview resolutions.
            try {
                SystemControllerFactory.get(this).setEisEnabled(false);
            } catch (Exception ignored) {
            }

            if (!cameraCoordinator.tryAcquireOpenCloseLock(2500)) {
                throw new RuntimeException("Timed out acquiring camera lock for scan");
            }
            scanning = true;
            scanStartedAt = System.currentTimeMillis();
            scanFrameIndex = 0;
            scanDumpsWritten = 0;
            Log.i(
                    BarcodeScanController.TAG,
                    "starting scan session — camera "
                            + cameraId
                            + " @ "
                            + SCAN_PREVIEW_WIDTH
                            + "x"
                            + SCAN_PREVIEW_HEIGHT);
            manager.openCamera(this.cameraId, newScanCameraStateCallback(), backgroundHandler);
        } catch (CameraAccessException e) {
            Log.e(BarcodeScanController.TAG, "camera access error starting scan", e);
            stopScan();
        } catch (Exception e) {
            Log.e(BarcodeScanController.TAG, "error starting scan", e);
            stopScan();
        }
    }

    private CameraDevice.StateCallback newScanCameraStateCallback() {
        return new CameraDevice.StateCallback() {
            @Override
            public void onOpened(@NonNull CameraDevice camera) {
                cameraCoordinator.releaseOpenCloseLock();
                cameraCoordinator.setDevice(camera);
                createScanSession();
            }

            @Override
            public void onDisconnected(@NonNull CameraDevice camera) {
                cameraCoordinator.releaseOpenCloseLock();
                camera.close();
                cameraCoordinator.clearDevice();
                Log.w(BarcodeScanController.TAG, "scan camera disconnected");
                stopScan();
            }

            @Override
            public void onError(@NonNull CameraDevice camera, int error) {
                cameraCoordinator.releaseOpenCloseLock();
                camera.close();
                cameraCoordinator.clearDevice();
                Log.e(BarcodeScanController.TAG, "scan camera error: " + error);
                stopScan();
            }
        };
    }

    private void createScanSession() {
        try {
            CameraDevice device = cameraCoordinator.device();
            if (device == null || scanPreviewReader == null || scanStillReader == null) {
                Log.e(BarcodeScanController.TAG, "scan session: device/reader null");
                stopScan();
                return;
            }
            Surface previewSurface = scanPreviewReader.getSurface();
            Surface stillSurface = scanStillReader.getSurface();
            previewBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewBuilder.addTarget(previewSurface);
            // Continuous auto-exposure + continuous AF for a moving hands-free view.
            previewBuilder.set(
                    CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
            previewBuilder.set(
                    CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            if (hasAutoFocus) {
                previewBuilder.set(
                        CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            }

            CameraCaptureSession.StateCallback cb =
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            Handler handler;
                            synchronized (SERVICE_LOCK) {
                                handler = backgroundHandler;
                                if (handler == null || cameraCoordinator.device() == null) {
                                    session.close();
                                    return;
                                }
                                cameraCoordinator.setSession(session);
                            }
                            try {
                                // Small repeating preview keeps AE/AF converged (HAL-safe size).
                                session.setRepeatingRequest(
                                        previewBuilder.build(), null, handler);
                                Log.i(
                                        BarcodeScanController.TAG,
                                        "scan session LIVE — snapshotting @ "
                                                + scanSizeWidth
                                                + "x"
                                                + scanSizeHeight);
                                // Let the preview's auto-exposure/focus converge (~1s) before
                                // the first snapshot, so it isn't dark/blurry. Then the loop is
                                // self-paced by decode time.
                                handler.postDelayed(
                                        CameraNeoService.this::captureScanStill, 1000);
                            } catch (CameraAccessException e) {
                                Log.e(BarcodeScanController.TAG, "setRepeatingRequest failed", e);
                                stopScan();
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Log.e(BarcodeScanController.TAG, "scan session configure failed");
                            stopScan();
                        }
                    };

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                List<OutputConfiguration> outs = new ArrayList<>();
                outs.add(new OutputConfiguration(previewSurface));
                outs.add(new OutputConfiguration(stillSurface));
                Executor exec =
                        backgroundHandler != null
                                ? new HandlerExecutor(backgroundHandler)
                                : executor;
                device.createCaptureSession(
                        new SessionConfiguration(
                                SessionConfiguration.SESSION_REGULAR, outs, exec, cb));
            } else {
                List<Surface> surfaces = new ArrayList<>();
                surfaces.add(previewSurface);
                surfaces.add(stillSurface);
                device.createCaptureSession(surfaces, cb, backgroundHandler);
            }
        } catch (CameraAccessException e) {
            Log.e(BarcodeScanController.TAG, "scan session access error", e);
            stopScan();
        }
    }

    /**
     * One snapshot cycle: force an autofocus scan, let it settle, THEN take the full-res still.
     * Continuous AF alone wasn't locking on close/low-contrast codes (blurry stills that no
     * decoder could read), so we explicitly trigger AF before each capture.
     */
    private void captureScanStill() {
        if (!scanning) {
            return;
        }
        CameraCaptureSession session = cameraCoordinator.session();
        CameraDevice device = cameraCoordinator.device();
        if (session == null || device == null || scanStillReader == null || previewBuilder == null) {
            return;
        }
        try {
            // Kick a fresh AF scan on the preview stream...
            previewBuilder.set(
                    CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START);
            if (sweepActive && sweepAfLock) {
                // Focus-lock: capture as soon as AF actually converges (watched by scanAfMonitor),
                // with sweepAfMaxMs as a hard fallback. Sharper stills than a blind fixed delay.
                scanAwaitingAf = true;
                scanAfTriggeredAt = System.currentTimeMillis();
                session.capture(previewBuilder.build(), scanAfMonitor, backgroundHandler);
                previewBuilder.set(
                        CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE);
                session.setRepeatingRequest(previewBuilder.build(), scanAfMonitor, backgroundHandler);
                backgroundHandler.postDelayed(scanAfTimeoutRunnable, sweepAfMaxMs);
            } else {
                session.capture(previewBuilder.build(), null, backgroundHandler);
                previewBuilder.set(
                        CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE);
                session.setRepeatingRequest(previewBuilder.build(), null, backgroundHandler);
                // ...then snapshot after a fixed settle (interactive scan, or sweep with lock off).
                long settle = sweepActive ? sweepAfSettleMs : SCAN_AF_SETTLE_MS;
                backgroundHandler.postDelayed(this::doScanStillCapture, settle);
            }
        } catch (CameraAccessException e) {
            Log.e(BarcodeScanController.TAG, "AF trigger failed", e);
        } catch (IllegalStateException e) {
            // Session closed under us (stop raced the capture) — benign.
        }
    }

    private void doScanStillCapture() {
        if (!scanning) {
            return;
        }
        CameraCaptureSession session = cameraCoordinator.session();
        CameraDevice device = cameraCoordinator.device();
        if (session == null || device == null || scanStillReader == null) {
            return;
        }
        try {
            // TEMPLATE_VIDEO_SNAPSHOT: a still grabbed WHILE a preview runs inherits the
            // converged auto-exposure (STILL_CAPTURE would need a separate AE precapture).
            CaptureRequest.Builder b =
                    device.createCaptureRequest(CameraDevice.TEMPLATE_VIDEO_SNAPSHOT);
            b.addTarget(scanStillReader.getSurface());
            b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            // Adopt Mentra's scan/document tuning (PhotoCaptureSettings): edge enhancement and
            // noise reduction OFF. On fine barcode bars, edge-enhancement rings and multi-frame
            // NR (MFNR) smears — both corrupt bar widths. Slightly negative exposure hardens the
            // black/white transitions and trims motion blur.
            b.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF);
            b.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF);
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, scanAeCompensation);
            if (hasAutoFocus) {
                // AUTO holds the focus the trigger just locked (don't let continuous AF drift
                // the lens between the lock and the capture).
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO);
            }
            session.capture(b.build(), null, backgroundHandler);
        } catch (CameraAccessException e) {
            Log.e(BarcodeScanController.TAG, "still capture failed", e);
        } catch (IllegalStateException e) {
            // Session closed under us — benign.
        }
    }

    /** Extract the Y (luminance) plane and hand it to ZXing. */
    private void decodeScanFrame(Image image) {
        // Continuous sweep mode owns the frame: save + ML Kit-decode + record, then bound.
        if (sweepActive) {
            handleSweepFrame(image);
            return;
        }
        int width = image.getWidth();
        int height = image.getHeight();

        // Diagnostic frame dump (spike only): write a few frames as JPEG so we can eyeball
        // what the camera is actually seeing.
        if (SCAN_DUMP_FRAMES
                && scanDumpsWritten < SCAN_DUMP_MAX
                && (scanFrameIndex % SCAN_DUMP_EVERY == 0)) {
            dumpScanFrameJpeg(image, scanFrameIndex);
        }
        scanFrameIndex++;

        if (scanController == null) {
            return;
        }
        // ML Kit wants NV21 (or a media Image). Build NV21 and let ML Kit locate+decode — it
        // handles off-center/angled/curved codes robustly and runs fully on-device (no GMS).
        byte[] nv21 = yuv420ToNv21(image);
        scanController.decodeMlKit(nv21, width, height, System.currentTimeMillis());
    }

    /** YUV_420_888 → NV21 → JPEG on disk (diagnostic only). */
    private void dumpScanFrameJpeg(Image image, int index) {
        try {
            int width = image.getWidth();
            int height = image.getHeight();
            byte[] nv21 = yuv420ToNv21(image);
            YuvImage yuv = new YuvImage(nv21, ImageFormat.NV21, width, height, null);
            File dir = new File(getExternalFilesDir(null), "scan_debug");
            if (!dir.exists()) {
                dir.mkdirs();
            }
            File out = new File(dir, "frame_" + index + ".jpg");
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
                yuv.compressToJpeg(new Rect(0, 0, width, height), 90, fos);
            }
            scanDumpsWritten++;
            Log.i(
                    BarcodeScanController.TAG,
                    "dumped frame " + index + " -> " + out.getAbsolutePath());
        } catch (Exception e) {
            Log.w(BarcodeScanController.TAG, "frame dump failed", e);
        }
    }

    /** Pack a YUV_420_888 Image into an NV21 byte array (Y plane, then interleaved V,U). */
    private static byte[] yuv420ToNv21(Image image) {
        int width = image.getWidth();
        int height = image.getHeight();
        Image.Plane[] planes = image.getPlanes();
        byte[] nv21 = new byte[width * height * 3 / 2];

        // Y
        java.nio.ByteBuffer yBuf = planes[0].getBuffer();
        int yRowStride = planes[0].getRowStride();
        int pos = 0;
        for (int row = 0; row < height; row++) {
            int rowStart = row * yRowStride;
            yBuf.position(rowStart);
            yBuf.get(nv21, pos, width);
            pos += width;
        }

        // VU interleaved (NV21 = Y + V,U,V,U...)
        java.nio.ByteBuffer uBuf = planes[1].getBuffer();
        java.nio.ByteBuffer vBuf = planes[2].getBuffer();
        int uvRowStride = planes[1].getRowStride();
        int uvPixelStride = planes[1].getPixelStride();
        int cHeight = height / 2;
        int cWidth = width / 2;
        for (int row = 0; row < cHeight; row++) {
            for (int col = 0; col < cWidth; col++) {
                int uvIndex = row * uvRowStride + col * uvPixelStride;
                vBuf.position(uvIndex);
                nv21[pos++] = vBuf.get();
                uBuf.position(uvIndex);
                nv21[pos++] = uBuf.get();
            }
        }
        return nv21;
    }

    private void stopScan() {
        boolean wasScanning = scanning;
        scanning = false;
        scanAwaitingAf = false;
        if (backgroundHandler != null) {
            backgroundHandler.removeCallbacks(scanAfTimeoutRunnable);
        }
        if (sweepActive) {
            writeSweepStatus(true);  // mark the run done so the host stops polling
            emitSweepResult(sweepHitFrame > 0);  // no-op if already emitted; covers external stop
            sweepActive = false;
        }
        if (wasScanning && scanController != null) {
            long elapsedMs = System.currentTimeMillis() - scanStartedAt;
            Log.i(
                    BarcodeScanController.TAG,
                    "stopping scan — "
                            + scanController.framesSeen()
                            + " frames, "
                            + scanController.decodeCount()
                            + " decodes over "
                            + elapsedMs
                            + "ms");
        }
        closeCamera();
        releaseWakeLocks(); // release the wake lock acquired for the scan
        try {
            SystemControllerFactory.get(this).setEisEnabled(true); // restore default
        } catch (Exception ignored) {
        }
        if (scanPreviewReader != null) {
            try {
                scanPreviewReader.close();
            } catch (RuntimeException ignored) {
            }
            scanPreviewReader = null;
        }
        if (scanStillReader != null) {
            try {
                scanStillReader.close();
            } catch (RuntimeException ignored) {
            }
            scanStillReader = null;
        }
        scanController = null;
        conditionalStopSelf();
    }

    private void createCameraSessionInternal(boolean forVideo) {
        try {
            CameraDevice activeCameraDevice = cameraCoordinator.device();
            if (activeCameraDevice == null) {
                Log.e(TAG, "Camera device is null in createCameraSessionInternal");
                if (forVideo)
                    notifyVideoError(videoSession.currentVideoId(), "Camera not initialized");
                else photoSession.notifyHostPhotoError("Camera not initialized");
                stopSelf();
                return;
            }

            List<Surface> surfaces = new ArrayList<>();
            if (forVideo) {
                Surface recSurface = videoSession.recorderSurface();
                if (recSurface == null) {
                    notifyVideoError(videoSession.currentVideoId(), "Recorder surface null");
                    conditionalStopSelf();
                    return;
                }
                surfaces.add(recSurface);
                previewBuilder =
                        activeCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                previewBuilder.addTarget(recSurface);
            } else {
                ImageReaderTwin readers = photoSession.imageReaders();
                if (readers == null) {
                    photoSession.notifyHostPhotoError("ImageReader surface null");
                    stopSelf();
                    return;
                }
                // Phase 0: both surfaces are session outputs; preview repeating request targets the
                // YUV preview reader only — still reader is reserved for explicit capture() calls.
                surfaces.addAll(readers.surfaces());

                previewBuilder =
                        activeCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                previewBuilder.addTarget(readers.getPreviewSurface());
                Log.d(
                        TAG,
                        "🔍 Using TEMPLATE_PREVIEW for repeating request, target=previewReader (ZSL"
                                + " compatible)");
            }

            VideoSettings pendingSettings = videoSession.pendingSettings();
            int videoFps = (pendingSettings != null) ? pendingSettings.fps : 30;
            Size sizeForMetering =
                    forVideo
                            ? videoSession.videoSize()
                            : new Size(
                                    ImageReaderTwin.PREVIEW_WIDTH, ImageReaderTwin.PREVIEW_HEIGHT);
            int displayOrientation = getDisplayRotation();
            int jpegOrientation =
                    JpegOrientationResolver.lookupJpegOrientation(
                            displayOrientation, JpegOrientationResolver.DEFAULT_JPEG_ORIENTATION);

            PreviewRequestConfigurator.configure(
                    previewBuilder,
                    forVideo,
                    videoFps,
                    eisEnabled,
                    selectedFpsRange,
                    hasAutoFocus,
                    userExposureCompensation,
                    sizeForMetering,
                    photoSession.previewJpegQuality(),
                    jpegOrientation,
                    mCameraSettings);

            CameraCaptureSession.StateCallback sessionStateCallback =
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            Handler handler;
                            CameraDevice device;
                            synchronized (SERVICE_LOCK) {
                                handler = backgroundHandler;
                                device = cameraCoordinator.device();
                                if (handler == null || device == null) {
                                    Log.w(
                                            TAG,
                                            "onConfigured after camera teardown; closing session");
                                    session.close();
                                    return;
                                }
                                cameraCoordinator.setSession(session);
                            }

                            if (forVideo) {
                                try {
                                    videoSession.startRecording(
                                            cameraCoordinator.session(), previewBuilder);
                                } catch (CameraAccessException ce) {
                                    Log.e(TAG, "Failed to start video recording", ce);
                                    notifyVideoError(
                                            videoSession.currentVideoId(),
                                            "Failed to start recording: " + ce.getMessage());
                                }
                            } else {
                                Log.d(TAG, "Camera session configured and ready");

                                photoSession.pollFirstQueuedRequestIntoCurrent();

                                // Start proper preview for photos with AE state monitoring
                                photoSession.startPreviewWithAeMonitoring();
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Handler handler;
                            CameraDevice device;
                            synchronized (SERVICE_LOCK) {
                                handler = backgroundHandler;
                                device = cameraCoordinator.device();
                            }
                            if (handler == null || device == null) {
                                Log.w(TAG, "onConfigureFailed after camera teardown; ignoring");
                                session.close();
                                return;
                            }
                            Log.e(
                                    TAG,
                                    "Failed to configure camera session for "
                                            + (forVideo ? "video" : "photo"));
                            if (forVideo)
                                notifyVideoError(
                                        videoSession.currentVideoId(),
                                        "Failed to configure camera for video");
                            else
                                photoSession.notifyHostPhotoError(
                                        "Failed to configure camera for photo");
                            conditionalStopSelf();
                        }
                    };

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                List<OutputConfiguration> outputConfigurations = new ArrayList<>();
                for (Surface surface : surfaces) {
                    outputConfigurations.add(new OutputConfiguration(surface));
                }
                Executor sessionExecutor =
                        backgroundHandler != null
                                ? new HandlerExecutor(backgroundHandler)
                                : executor;
                SessionConfiguration config =
                        new SessionConfiguration(
                                SessionConfiguration.SESSION_REGULAR,
                                outputConfigurations,
                                sessionExecutor,
                                sessionStateCallback);
                activeCameraDevice.createCaptureSession(config);
            } else {
                activeCameraDevice.createCaptureSession(
                        surfaces, sessionStateCallback, backgroundHandler);
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Camera access exception in createCameraSessionInternal", e);
            if (forVideo) notifyVideoError(videoSession.currentVideoId(), "Camera access error");
            else photoSession.notifyHostPhotoError("Camera access error");
            conditionalStopSelf();
        } catch (IllegalStateException e) {
            Log.e(TAG, "Illegal state in createCameraSessionInternal", e);
            if (forVideo) notifyVideoError(videoSession.currentVideoId(), "Camera illegal state");
            else photoSession.notifyHostPhotoError("Camera illegal state");
            conditionalStopSelf();
        }
    }

    private void notifyVideoError(String videoId, String errorMessage) {
        if (videoSession != null) {
            videoSession.notifyError(videoId, errorMessage);
        } else {
            VideoRecordingCallback cb = VideoRecordingSession.pendingVideoCallback();
            if (cb != null && videoId != null) {
                executor.execute(() -> cb.onRecordingError(videoId, errorMessage));
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        synchronized (SERVICE_LOCK) {
            Log.d(TAG, "CameraNeoService service destroying");

            // Cancel keep-alive timer if it's running
            cancelKeepAliveTimer();
            if (videoSession != null && videoSession.isRecording()) {
                videoSession.stopRecording(videoSession.currentVideoId());
            }
            closeCamera();
            releaseWakeLocks();

            sInstance = null;

            QueuedPhotoRequestQueue.getInstance()
                    .failAllPending("Camera service terminated unexpectedly");
        }
        // API 28+ session callbacks run on backgroundHandler and also take SERVICE_LOCK to detect
        // teardown. Do not join the handler thread while holding that lock.
        stopBackgroundThread();
    }

    /** Start background thread */
    private void startBackgroundThread() {
        backgroundHandler = cameraCoordinator.startBackgroundThread("CameraNeoBackground");
    }

    /** Stop background thread */
    private void stopBackgroundThread() {
        cameraCoordinator.stopBackgroundThread();
        backgroundHandler = null;
    }

    /** Close camera resources */
    private void closeCamera() {
        boolean lockAcquired = false;
        try {
            lockAcquired = cameraCoordinator.tryAcquireOpenCloseLock(5000);
            if (!lockAcquired) {
                Log.e(
                        TAG,
                        "closeCamera: Failed to acquire lock within 5 seconds, proceeding with"
                                + " cleanup anyway");
            }
            cameraCoordinator.closeDeviceAndSession();
            photoSession.closeImageReadersIfPresent();
            photoSession.onCameraClosed();
            if (videoSession != null) {
                videoSession.release();
            }
            // Reset keep-alive flag when camera is actually closed
            cameraCoordinator.markCameraClosed();

            releaseWakeLocks();
        } catch (InterruptedException e) {
            Log.e(TAG, "Interrupted while closing camera", e);
        } finally {
            if (lockAcquired) {
                cameraCoordinator.releaseOpenCloseLock();
            }
        }
    }

    /** Start the keep-alive timer to keep camera open for rapid successive shots */
    private void startKeepAliveTimer() {
        cameraCoordinator.startKeepAlive(
                CAMERA_KEEP_ALIVE_MS,
                () -> photoSession.shotState() != AeStateMachine.ShotState.IDLE,
                () -> {
                    // Tear down under SERVICE_LOCK so this background-thread close is atomic with
                    // respect to isCameraWarm() and enqueuePhotoRequest(), which both take the same
                    // lock. Otherwise the close could land between a warm read and the enqueue,
                    // making the short "hot" cue play for a capture that actually cold-starts.
                    // Lock order is SERVICE_LOCK -> openCloseLock (closeCamera takes openCloseLock
                    // internally), matching every other path, so this cannot deadlock.
                    synchronized (SERVICE_LOCK) {
                        closeCamera();
                        stopSelf();
                    }
                });
    }

    /** Cancel the keep-alive timer */
    private void cancelKeepAliveTimer() {
        cameraCoordinator.cancelKeepAlive();
    }

    /** Release wake locks to avoid battery drain */
    private void releaseWakeLocks() {
        // Use the WakeLockManager to release all wake locks
        WakeLockManager.releaseAllWakeLocks();
    }

    /** Force the screen to turn on so camera can be accessed */
    private void wakeUpScreen() {
        Log.d(TAG, "Waking up screen for camera access");
        // Use the WakeLockManager to acquire both CPU and screen wake locks
        WakeLockManager.acquireFullWakeLockAndBringToForeground(this, 180000, 5000);
    }

    /** Attempt to restart the camera service with different parameters if needed */
    private void restartCameraServiceIfNeeded() {
        CameraRecoveryHelper.restartCameraServiceIfNeeded(
                this::releaseCameraResources,
                this,
                () -> cameraId,
                id -> cameraId = id,
                this::wakeUpScreen,
                () -> cameraCoordinator.closeDeviceAndSession());
    }

    /** Release all camera system resources */
    private void releaseCameraResources() {
        CameraRecoveryHelper.releaseCameraResources(
                this::closeCamera, () -> cameraCoordinator.closeDeviceAndSession(), this);
    }

    // -----------------------------------------------------------------------------------
    // Notification handling
    // -----------------------------------------------------------------------------------

    private void showNotification(String title, String message) {
        CameraServiceNotification.showForeground(this, CHANNEL_ID, NOTIFICATION_ID, title, message);
    }

    private void createNotificationChannel() {
        CameraServiceNotification.createNotificationChannel(this, CHANNEL_ID);
    }

    /** Query camera capabilities for dynamic auto-exposure */
    private void queryCameraCapabilities(CameraCharacteristics characteristics) {
        // Get available AE modes
        availableAeModes = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES);
        if (availableAeModes == null) {
            availableAeModes = new int[] {CaptureRequest.CONTROL_AE_MODE_ON};
        }

        // Get exposure compensation range and step
        exposureCompensationRange =
                characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
        if (exposureCompensationRange == null) {
            exposureCompensationRange = Range.create(-2, 2); // Default range
        }

        exposureCompensationStep =
                characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
        if (exposureCompensationStep == null) {
            exposureCompensationStep = new Rational(1, 6); // Default 1/6 EV step
        }

        // Get available FPS ranges; selection logic lives in {@link FpsRangePolicy}.
        availableFpsRanges =
                characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        if (availableFpsRanges == null || availableFpsRanges.length == 0) {
            selectedFpsRange = FpsRangePolicy.DEFAULT_FPS_RANGE;
        } else {
            selectedFpsRange = FpsRangePolicy.chooseOptimalFpsRange(availableFpsRanges);
            Log.d(
                    TAG,
                    "Selected FPS range: "
                            + selectedFpsRange
                            + " from "
                            + availableFpsRanges.length
                            + " advertised ranges");
        }

        // Phase 3 prep: AF + manual-sensor capabilities bundled into one immutable value object.
        cameraCapabilities = CameraCapabilities.from(characteristics);
        hasAutoFocus = cameraCapabilities.hasContinuousPictureAf;

        Log.d(
                TAG,
                "Camera capabilities - AE modes: " + java.util.Arrays.toString(availableAeModes));
        Log.d(
                TAG,
                "Exposure compensation range: "
                        + exposureCompensationRange
                        + ", step: "
                        + exposureCompensationStep);
        Log.d(TAG, "Selected FPS range: " + selectedFpsRange);
        Log.d(
                TAG,
                "Autofocus available: "
                        + hasAutoFocus
                        + ", min focus distance: "
                        + cameraCapabilities.minimumFocusDistance);
        Log.d(
                TAG,
                "Manual sensor: supported="
                        + cameraCapabilities.manualSensorSupported
                        + ", exposureNsRange="
                        + cameraCapabilities.sensorExposureTimeRange
                        + ", maxFrameDurationNs="
                        + cameraCapabilities.sensorMaxFrameDurationNs
                        + ", isoRange="
                        + cameraCapabilities.sensorSensitivityRange);
    }
}
