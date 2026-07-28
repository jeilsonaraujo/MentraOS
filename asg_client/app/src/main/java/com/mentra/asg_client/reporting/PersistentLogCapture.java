package com.mentra.asg_client.reporting;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import com.mentra.asg_client.BuildConfig;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mirrors logcat to rotating files on disk for as long as the app is installed, so glasses-side
 * logs survive app restarts, crashes and reboots without an ADB tether.
 *
 * <p>{@link GlassesLogBuffer} only reads the volatile kernel ring buffer on demand — anything that
 * scrolled out of it is gone. This class instead keeps a dedicated {@code logcat -f} child process
 * writing straight to storage, so the history is already on the device when a bug is noticed hours
 * later.
 *
 * <p>Design notes:
 * <ul>
 *   <li>Rotation is delegated to logcat itself ({@code -r}/{@code -n}) — no Java-side reader that
 *       could stall, block or OOM on a log burst.</li>
 *   <li>Each process start opens a new session file ({@code asg-000123.log}) so a restart can never
 *       truncate earlier history, whatever the platform's {@code logcat -f} open mode is.</li>
 *   <li>A watchdog re-spawns the writer if it dies and retries directory resolution, which matters
 *       during direct boot when external storage is not mounted yet.</li>
 *   <li>The writer's pid is persisted so a capture orphaned by an app kill is reaped instead of
 *       racing the new one on the same file.</li>
 * </ul>
 *
 * <p>Capturing other processes (kernel, ActivityManager, Bluetooth stack) requires
 * {@code android.permission.READ_LOGS}, a development permission granted once over ADB:
 * {@code adb shell pm grant <package> android.permission.READ_LOGS}. Without it logcat silently
 * narrows to this app's own UID; the session banner records which mode is active.
 */
public final class PersistentLogCapture {

    private static final String TAG = "PersistentLogCapture";

    private static final String LOG_DIRECTORY = "mentra_logs";
    private static final String PID_FILE_NAME = "logcat.pid";
    private static final String FILE_PREFIX = "asg-";
    private static final String FILE_SUFFIX = ".log";

    /** Size of a single log file, in KB, before logcat rotates it. */
    private static final int ROTATE_KBYTES = 10 * 1024; // 10 MB
    /** Rotated files kept per session, on top of the active one. */
    private static final int ROTATE_COUNT = 19;
    /** Total on-disk budget across every session; oldest files are purged past this. */
    private static final long MAX_TOTAL_SIZE_BYTES = 200L * 1024 * 1024; // 200 MB

    private static final long WATCHDOG_INTERVAL_MS = 60_000L;
    /** Stderr lines logged per spawn before we go quiet, so a failing spawn can't spam the log. */
    private static final int MAX_REPORTED_ERROR_LINES = 20;

    private static final String PREFS_NAME = "log_capture";
    private static final String KEY_SESSION_SEQ = "session_seq";

    /** Matches {@code asg-000123.log} and its rotations {@code asg-000123.log.4}. */
    private static final Pattern FILE_PATTERN =
            Pattern.compile("^" + FILE_PREFIX + "(\\d+)\\" + FILE_SUFFIX + "(?:\\.(\\d+))?$");

    private static volatile PersistentLogCapture sInstance;

    private final Context mContext;
    private final ScheduledExecutorService mWatchdog;

    private File mLogDirectory;
    private File mActiveFile;
    private Process mProcess;

    private PersistentLogCapture(Context context) {
        mContext = context;
        mWatchdog = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "log-capture-watchdog");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Start capturing, idempotently. Safe to call from {@code Application#onCreate}: the first
     * attempt runs on the watchdog thread, so a slow or unavailable storage volume never blocks
     * app startup.
     */
    public static synchronized void start(Context context) {
        if (sInstance != null) {
            return;
        }
        sInstance = new PersistentLogCapture(context.getApplicationContext());
        sInstance.mWatchdog.scheduleWithFixedDelay(
                sInstance::ensureRunning, 0, WATCHDOG_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /** Directory the logs are being written to, or null if it could not be resolved yet. */
    public static String getLogDirectoryPath() {
        PersistentLogCapture instance = sInstance;
        if (instance == null || instance.mLogDirectory == null) {
            return null;
        }
        return instance.mLogDirectory.getAbsolutePath();
    }

    /** Watchdog tick: (re)spawn the writer when needed and keep the directory within budget. */
    private synchronized void ensureRunning() {
        try {
            if (mProcess != null && mProcess.isAlive()) {
                purgeOverBudget();
                return;
            }

            if (mProcess != null) {
                Log.w(TAG, "logcat writer exited (code " + mProcess.exitValue()
                        + ") — restarting, logs may have a gap");
            }

            if (mLogDirectory == null) {
                mLogDirectory = resolveLogDirectory();
                if (mLogDirectory == null) {
                    // Common during direct boot: external storage is not mounted yet. Retry next tick.
                    Log.e(TAG, "No writable log directory yet — retrying in "
                            + (WATCHDOG_INTERVAL_MS / 1000) + "s");
                    return;
                }
            }

            killOrphanedCapture();
            spawn();
            purgeOverBudget();
        } catch (Exception e) {
            Log.e(TAG, "Watchdog tick failed", e);
        }
    }

    /**
     * Spawn the logcat writer through {@code sh} so the shell can record the writer's own pid
     * ({@code $$} survives the {@code exec}), which is how an orphaned capture is later reaped.
     */
    private void spawn() throws Exception {
        int sequence = nextSessionSequence();
        mActiveFile = new File(mLogDirectory,
                String.format(Locale.US, "%s%06d%s", FILE_PREFIX, sequence, FILE_SUFFIX));
        File pidFile = new File(mLogDirectory, PID_FILE_NAME);

        // No -T: logcat drains the existing ring buffer first, so a restart re-reads whatever
        // happened while we were dead rather than leaving a hole.
        String command = "echo $$ > '" + pidFile.getAbsolutePath() + "'; "
                + "exec logcat -b main -b system -b crash -b events -v threadtime"
                + " -f '" + mActiveFile.getAbsolutePath() + "'"
                + " -r " + ROTATE_KBYTES
                + " -n " + ROTATE_COUNT;

        ProcessBuilder builder = new ProcessBuilder("sh", "-c", command);
        builder.redirectErrorStream(true);
        mProcess = builder.start();

        drainOutputAsync(mProcess);
        logSessionBanner(sequence);
    }

    /**
     * logcat writes the log stream to the file, so its stdout/stderr only carries startup errors
     * (bad flag, unreadable buffer). Drain it anyway: an undrained pipe would eventually block the
     * child.
     */
    private void drainOutputAsync(Process process) {
        Thread drain = new Thread(() -> {
            int reported = 0;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (reported < MAX_REPORTED_ERROR_LINES) {
                        Log.w(TAG, "logcat: " + line);
                        reported++;
                    }
                }
            } catch (Exception e) {
                // Expected when the writer is killed; nothing actionable.
            }
        }, "log-capture-drain");
        drain.setDaemon(true);
        drain.start();
    }

    private void logSessionBanner(int sequence) {
        boolean systemWide = mContext.checkSelfPermission("android.permission.READ_LOGS")
                == PackageManager.PERMISSION_GRANTED;

        Log.i(TAG, "================ LOG CAPTURE SESSION " + sequence + " ================");
        Log.i(TAG, "File: " + mActiveFile.getAbsolutePath());
        Log.i(TAG, "Rotation: " + (ROTATE_KBYTES / 1024) + " MB x " + (ROTATE_COUNT + 1)
                + " files/session, " + (MAX_TOTAL_SIZE_BYTES / 1024 / 1024) + " MB total budget");
        Log.i(TAG, "Scope: " + (systemWide ? "system-wide (READ_LOGS granted)" : "this app only"));
        if (!systemWide) {
            Log.w(TAG, "READ_LOGS not granted — run: adb shell pm grant "
                    + mContext.getPackageName() + " android.permission.READ_LOGS");
        }
        Log.i(TAG, "App: " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")"
                + " | Device: " + Build.MANUFACTURER + " " + Build.MODEL
                + " | Android " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")");
        Log.i(TAG, "==========================================================");
    }

    /**
     * Session counter, kept in device-protected storage so it is readable during direct boot
     * (capture can start before the device is unlocked).
     */
    private int nextSessionSequence() {
        try {
            SharedPreferences prefs = mContext.createDeviceProtectedStorageContext()
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            int sequence = prefs.getInt(KEY_SESSION_SEQ, 0) + 1;
            prefs.edit().putInt(KEY_SESSION_SEQ, sequence).apply();
            return sequence;
        } catch (Exception e) {
            Log.w(TAG, "Could not persist session counter, deriving one from existing files", e);
            return highestSequenceOnDisk() + 1;
        }
    }

    private int highestSequenceOnDisk() {
        int highest = 0;
        File[] files = mLogDirectory != null ? mLogDirectory.listFiles() : null;
        if (files == null) {
            return highest;
        }
        for (File file : files) {
            Matcher matcher = FILE_PATTERN.matcher(file.getName());
            if (matcher.matches()) {
                try {
                    highest = Math.max(highest, Integer.parseInt(matcher.group(1)));
                } catch (NumberFormatException ignored) {
                    // Not one of ours; skip.
                }
            }
        }
        return highest;
    }

    /**
     * Kill a writer left behind by a previous app process. Two logcat processes appending to the
     * same file would interleave partial lines, so this runs before every spawn.
     */
    private void killOrphanedCapture() {
        File pidFile = new File(mLogDirectory, PID_FILE_NAME);
        if (!pidFile.exists()) {
            return;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(pidFile))) {
            String line = reader.readLine();
            int pid = line != null ? Integer.parseInt(line.trim()) : -1;
            // Guard against pid reuse: only kill something that is still a logcat process.
            if (pid > 0 && pid != android.os.Process.myPid() && isLogcatProcess(pid)) {
                android.os.Process.killProcess(pid);
                Log.i(TAG, "Reaped orphaned logcat writer pid=" + pid);
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not reap orphaned logcat writer: " + e.getMessage());
        }
        pidFile.delete();
    }

    private boolean isLogcatProcess(int pid) {
        File cmdline = new File("/proc/" + pid + "/cmdline");
        if (!cmdline.exists()) {
            return false;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(cmdline))) {
            String line = reader.readLine();
            return line != null && line.contains("logcat");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Prefer legacy public storage so logs can be pulled with a plain {@code adb pull /sdcard/...};
     * fall back to app-specific external storage under scoped storage, then internal storage.
     */
    private File resolveLogDirectory() {
        List<File> candidates = new ArrayList<>();
        try {
            File externalRoot = Environment.getExternalStorageDirectory();
            if (externalRoot != null) {
                candidates.add(new File(externalRoot, LOG_DIRECTORY));
            }
        } catch (Exception e) {
            Log.w(TAG, "Public storage unavailable: " + e.getMessage());
        }
        File appExternal = mContext.getExternalFilesDir(null);
        if (appExternal != null) {
            candidates.add(new File(appExternal, LOG_DIRECTORY));
        }
        candidates.add(new File(mContext.getFilesDir(), LOG_DIRECTORY));

        for (File candidate : candidates) {
            try {
                if (!candidate.exists() && !candidate.mkdirs()) {
                    continue;
                }
                if (candidate.canWrite()) {
                    Log.i(TAG, "Capturing logs to " + candidate.getAbsolutePath());
                    return candidate;
                }
            } catch (Exception e) {
                Log.w(TAG, "Rejected log directory " + candidate + ": " + e.getMessage());
            }
        }
        return null;
    }

    /**
     * Safety net on top of logcat's own rotation: sessions accumulate across restarts, so drop the
     * oldest files once the directory exceeds the total budget.
     */
    private void purgeOverBudget() {
        if (mLogDirectory == null) {
            return;
        }
        try {
            List<LogFile> logFiles = new ArrayList<>();
            long totalSize = 0;
            File[] files = mLogDirectory.listFiles();
            if (files == null) {
                return;
            }
            for (File file : files) {
                Matcher matcher = FILE_PATTERN.matcher(file.getName());
                if (!matcher.matches()) {
                    continue;
                }
                int sequence = Integer.parseInt(matcher.group(1));
                int rotation = matcher.group(2) != null ? Integer.parseInt(matcher.group(2)) : 0;
                logFiles.add(new LogFile(file, sequence, rotation));
                totalSize += file.length();
            }

            if (totalSize <= MAX_TOTAL_SIZE_BYTES) {
                return;
            }

            // Oldest first: lower session, and within a session the higher rotation index.
            Collections.sort(logFiles, (a, b) -> a.sequence != b.sequence
                    ? Integer.compare(a.sequence, b.sequence)
                    : Integer.compare(b.rotation, a.rotation));

            int deleted = 0;
            for (LogFile logFile : logFiles) {
                if (totalSize <= MAX_TOTAL_SIZE_BYTES) {
                    break;
                }
                // Never delete the file the writer is currently appending to.
                if (mActiveFile != null && logFile.file.getAbsolutePath()
                        .equals(mActiveFile.getAbsolutePath())) {
                    continue;
                }
                long size = logFile.file.length();
                if (logFile.file.delete()) {
                    totalSize -= size;
                    deleted++;
                }
            }
            if (deleted > 0) {
                Log.i(TAG, "Purged " + deleted + " old log files, "
                        + (totalSize / 1024 / 1024) + " MB remaining");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error purging old logs", e);
        }
    }

    private static final class LogFile {
        final File file;
        final int sequence;
        final int rotation;

        LogFile(File file, int sequence, int rotation) {
            this.file = file;
            this.sequence = sequence;
            this.rotation = rotation;
        }
    }
}
