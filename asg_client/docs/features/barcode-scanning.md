# Barcode / QR scanning (glasses-native)

The glasses decode barcodes and QR codes **on-device** and send back the already-decoded value over BLE — no image ever needs to leave the device for a scan. A phone (or any BLE host) starts a bounded "sweep", the glasses tick audibly while it runs, and the outcome comes back as a single small JSON message. On a successful decode, the hit frame is also promoted into the camera gallery as a normal capture so the usual media-sync path can carry the photographic evidence off the device later.

Public API entry points: [`start_barcode_scan` / `stop_barcode_scan` / `barcode_scan_result`](../ASG_CLIENT_API.md#barcode-scanning).

## Components

| Piece | Where | Role |
|---|---|---|
| Sweep engine | `camera/CameraNeoService.java` (`ACTION_START_BARCODE_SWEEP` / `ACTION_STOP_BARCODE_SCAN`) | Opens the camera, captures up to `sweep_max` full-res stills (~1.6 s each), decodes each, self-stops at the cap or on first decode (`stop_on_found`). Wakes the screen and holds a wake lock for the run; full teardown (camera, wake locks, EIS) on every end. |
| Decoder | `camera/barcode/BarcodeScanController.java` | The **bundled** ML Kit barcode scanner (`com.google.mlkit:barcode-scanning`, model statically linked — works on this GMS-less build), configured for `FORMAT_ALL_FORMATS`. ZXing and a commercial SDK were benchmarked and dropped for curved/soft/angled real-world codes. |
| Consensus guard | `camera/barcode/CodeConsensus.java` | Pure state machine: a value is only CONFIRMED once it repeats across ≥ 2 observations (curved 1D codes can decode to a *wrong but checksum-valid* value once; a genuine code repeats), and a confirmed code sitting in frame is not re-reported (sliding dedup window). Unit-tested on the JVM. |
| BLE command handlers | `service/core/handlers/BarcodeScanCommandHandler.java` | Maps the `start_barcode_scan` / `stop_barcode_scan` JSON commands to the service intents and forwards the sweep result back via `ICommunicationManager.sendBluetoothResponse` (mirrored to adb broadcast for cable-only testing). |
| Audible state | `assets/scan_tick.wav`, `scan_success.wav`, `scan_fail.wav` | A tick every ~0.9 s while the sweep runs, then a success or fail tone. Played through `MediaPlayer` on the **main looper** — audio scheduled on the camera background thread dies when the sweep tears that thread down. |

## Sweep lifecycle

1. `start_barcode_scan` arrives over BLE (or adb) → `CameraNeoService` starts in sweep mode. A second start while one is active is **ignored** (logged) — no stacked cameras.
2. Each frame: capture a full-res still → NV21 → ML Kit decode (30–250 ms) → per-frame record appended to `results.jsonl` in the sweep directory (every code in the frame, as `FORMAT|value`).
3. On the first frame with any decode (`stop_on_found`, default) or at `sweep_max` frames, the sweep ends: success/fail tone, `barcode_scan_result` sent over BLE, camera closed, wake locks released.
4. On success, the hit frame is **promoted** to the camera media directory as an ordinary capture — `IMG_<ts>_<rand>_<requestId>/base.jpg` plus a `barcode.json` sidecar listing **every** code the frame carried — so gallery sync tooling picks it up like any photo. The transient sweep directory holds per-frame debug artifacts only.

### Multi-code frames

A single frame can contain several codes. All of them ride the result (`values` array) and the sidecar (`codes` array); the legacy single `value`/`format` fields carry the first code so older consumers keep working.

### Timing

With a readable code in view the sweep typically locks on frame 1–2 (≈ 3–5 s end to end). With nothing readable, the default `sweep_max` bounds the wait (callers commonly send `sweep_max: 8` ≈ 13 s to a definitive "not found"). There are no retries or loops — one command, one bounded sweep, one result.

## Testing

JVM unit tests (Robolectric where a `Context` is needed):

- `camera/barcode/CodeConsensusTest` — the confirm/dedup contract (misreads never confirm, sliding window, re-scan after leaving frame).
- `camera/barcode/BarcodeScanControllerTest` — format naming (`QR`, `EAN_13`, …, `FMT_<n>` fallback) that every downstream consumer stores/displays.
- `service/core/handlers/BarcodeScanCommandHandlerTest` — the BLE wire contract: JSON → service intent (parameters, documented defaults, requestId sanitization, stop).

```bash
./gradlew :app:testDebugUnitTest --tests "*Barcode*" --tests "*CodeConsensus*"
```

The decode paths themselves need the on-device ML Kit runtime and real optics, so they are validated on hardware; per-frame `results.jsonl` and the `SWEEP` logcat lines (tag `BarcodeScan`) are the diagnostics.

## ADB testing without a phone

```bash
# Start a short sweep (result is mirrored to an intent broadcast)
adb shell am broadcast -a com.mentra.asg_client.ACTION_SEND_COMMAND \
  --es json '{"type":"start_barcode_scan","requestId":"test1","sweep_max":8}'

# Stop an in-flight sweep early
adb shell am broadcast -a com.mentra.asg_client.ACTION_SEND_COMMAND \
  --es json '{"type":"stop_barcode_scan"}'
```
