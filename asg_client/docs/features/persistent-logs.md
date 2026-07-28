# Persistent log capture

Continuous, on-device logcat capture so glasses-side logs survive app restarts, crashes and reboots — no ADB tether required. Owned by `reporting/PersistentLogCapture.java`.

## Why it exists

The other log paths on the glasses are all *on demand*, and they read the volatile kernel ring buffer:

| Component | What it does | Limitation |
| --- | --- | --- |
| `GlassesLogBuffer` | `logcat -d -t N --pid=<app>` when an incident is created | Only what is still in the ring buffer |
| `UploadIncidentLogsCommandHandler` | Uploads logcat + BES logs via WiFi/BLE | Same ring-buffer window |
| `FileReportProvider` | Writes structured crash files to `/sdcard/mentra_crash_logs/` | ERROR/CRITICAL reports only, not the log stream |

The ring buffer is a few hundred KB per buffer and rolls over in minutes on a busy device. If a bug is noticed an hour later, the evidence is already gone. `PersistentLogCapture` keeps a dedicated `logcat -f` writer running so the history is already on disk.

## How it works

On every process start, `AsgClientApplication.onCreate()` calls `PersistentLogCapture.start(this)`. That covers boot, crash restart, `MY_PACKAGE_REPLACED` and manual relaunch — the app process starting *is* the trigger, so there is no separate lifecycle to keep in sync.

The capture itself is a child process:

```sh
sh -c "echo $$ > <dir>/logcat.pid; exec logcat -b main -b system -b crash -b events \
    -v threadtime -f <dir>/asg-000123.log -r 10240 -n 19"
```

Design decisions worth knowing:

- **Rotation is delegated to logcat** (`-r`/`-n`). There is no Java-side reader thread that could stall, block on I/O or OOM during a log burst.
- **One file per session.** Each process start allocates a new sequence number (`asg-000123.log`), so a restart can never truncate earlier history regardless of how the platform's `logcat -f` opens the file. The counter lives in device-protected `SharedPreferences` so it is readable during direct boot.
- **No `-T` flag.** logcat drains the existing ring buffer before following, so a restart re-reads whatever happened while the writer was dead. Some duplication, no gap.
- **The writer's pid is persisted.** If the app process is killed and the child survives, the next spawn reaps it (after confirming via `/proc/<pid>/cmdline` that it is still logcat, guarding against pid reuse). Two writers appending to one file would interleave partial lines.
- **A 60 s watchdog** re-spawns a dead writer and retries directory resolution — external storage is not mounted during direct boot, so the first attempts can legitimately fail.
- **`-b events` is included** because `am_kill` / `am_anr` land there. That is what tells you the app was killed rather than crashed.

## Where the logs land

Directory resolution, first writable wins:

1. `/sdcard/mentra_logs/` — preferred, plain `adb pull` works
2. `<app external files>/mentra_logs/` — under scoped storage
3. `<internal files>/mentra_logs/` — last resort

The resolved path is logged at startup (`Capturing logs to …`) and available via `PersistentLogCapture.getLogDirectoryPath()`.

## Retention

10 MB per file × 20 files per session (logcat's own rotation), with a **200 MB total budget** across all sessions enforced by the watchdog. Past the budget it deletes oldest-first — lower session sequence first, and within a session the higher rotation index (which is the older file). The file currently being written is never deleted.

## READ_LOGS

Without `android.permission.READ_LOGS`, Android silently narrows logcat to the app's own UID — you lose the kernel, ActivityManager and Bluetooth stack lines. It is a development-level permission, so it must be granted once over ADB:

```bash
adb shell pm grant com.mentra.asg_client android.permission.READ_LOGS
# dev builds installed by scripts/dev-setup.sh use the .thirdparty package:
adb shell pm grant com.mentra.asg_client.thirdparty android.permission.READ_LOGS
```

`scripts/dev-setup.sh` grants it automatically. The grant survives reboots but **not** a reinstall. Restart the app after granting — logcat's UID filter is decided when the writer starts.

The session banner records which mode is active:

```
PersistentLogCapture: Scope: system-wide (READ_LOGS granted)
PersistentLogCapture: Scope: this app only
```

## Retrieving the logs

```bash
adb pull /sdcard/mentra_logs/ ./glasses-logs/
```

Sessions are ordered by the sequence number in the filename, not by mtime — the device clock can be wrong before NTP sync, especially right after boot.

To find session boundaries in a file, grep the banner:

```bash
grep -n "LOG CAPTURE SESSION" asg-000123.log
```

## Known limitations

- logcat buffers file writes through stdio, so a hard power-off can lose the last few KB. Everything older is safely on disk.
- If the platform kills the whole process group (low-memory killer), the writer dies with the app. It restarts when the app process does, and the ring-buffer re-read recovers most of the gap — but a long time spent dead is a real hole. The `am_kill` line in the *next* session's file tells you it happened.
- Capture is unconditional and not gated by a setting. If it ever needs to be disabled in the field, that gate has to be added.
