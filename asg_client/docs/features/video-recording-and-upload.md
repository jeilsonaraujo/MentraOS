# Video recording & upload

ASG Client records video from the camera and, on stop, optionally uploads the clip off the glasses. The upload destination is **described by the phone/backend**, not hard-coded in firmware: a stop command can carry a generic upload descriptor (method, URL, headers, body shape) so the glasses can `PUT` straight to S3 (or anywhere) without a firmware change. Delivery of the stop itself is made reliable with an immediate ACK + dedup, and a failed upload can be retried later from the phone.

Source:

- `app/src/main/java/com/mentra/asg_client/service/core/handlers/VideoCommandHandler.java` — command dispatch, ACK, idempotency
- `app/src/main/java/com/mentra/asg_client/io/media/core/MediaCaptureService.java` — recording + upload execution
- `app/src/main/java/com/mentra/asg_client/io/media/core/UploadSpec.java` — the app-described upload descriptor

The wire-level command schema (request fields, response types, error codes) lives in [ASG_CLIENT_API.md → Video recording](../ASG_CLIENT_API.md#video-recording). This doc covers the **lifecycle** and **operational behavior**.

## Commands

| Command                      | Purpose                                                                |
| ---------------------------- | --------------------------------------------------------------------- |
| `start_video_recording`      | Start recording (optional resolution/fps, flash, sound).              |
| `stop_video_recording`       | Stop recording and optionally upload the clip.                        |
| `stop_video_recording_ack`   | Glasses → phone: immediate "stop accepted" acknowledgment.            |
| `upload_video`               | Re-upload an already-recorded clip that's still on the glasses.       |
| `get_video_recording_status` | Query whether a recording is active and its duration.                 |

All status updates use the `video_recording_status` response type. See the [API doc](../ASG_CLIENT_API.md#video-recording) for fields and response shapes.

## Recording lifecycle

### Start

`start_video_recording` validates battery (same floor as photo capture) and camera availability, then begins recording with the requested resolution/fps. The successful status is `recording_started`; failures emit `already_recording`, `battery_low`, `service_unavailable`, `missing_request_id`, or `error` with `success: false`.

### Stop (reliable delivery)

Because either a BLE command or its response can be dropped, the stop path is made idempotent:

1. The phone sends `stop_video_recording` (with `requestId`) and **keeps resending the same `requestId`** until it sees an ACK. The phone SDK helper `stopVideoRecordingReliably` does this automatically (default: 3 s ACK timeout, up to 3 resends).
2. On accepting a valid, actionable stop, the glasses **immediately** reply with `stop_video_recording_ack` — _before_ the recorder stops or any upload begins — and record the `requestId` as accepted.
3. A duplicate stop for an already-accepted `requestId` just **re-ACKs**; it never stops the recorder again or starts a second upload.

The accepted-`requestId` set is bounded (insertion-ordered, oldest evicted past 64 entries) so a long session can't grow it without limit.

The ACK confirms **reception/acceptance only**. Recording-stopped and upload progress/result are reported separately on `video_recording_status`.

### Upload decision point

Once recording stops, the glasses pick an upload path from the stop command, in priority order:

1. **App-described upload** (`upload` descriptor present) — see below.
2. **Legacy multipart webhook** (`webhookUrl` + `authToken`).
3. **No upload** (neither present) — the clip stays on device.

The upload target is supplied at **stop**, not start, so any signed URL / auth token is still fresh when the upload actually runs (a recording can last arbitrarily long).

## App-described uploads (`UploadSpec`)

An `upload` object lets the backend dictate the exact HTTP request the glasses make. The firmware is destination-agnostic — it executes the described request verbatim:

- **`body: "file"`** — raw bytes (e.g. a presigned S3 `PUT`). `Content-Type` is sent **only** when the descriptor's `headers` include one: a SigV2 presigned URL is signed without `Content-Type`, so adding one would make S3 reject the `PUT` with `SignatureDoesNotMatch` (403).
- **`body: "multipart"`** — form-data; caller-provided `fields` go first and the file part (`fileField`, default `video`) is appended **last** for order-sensitive servers.
- **`body: "json"`** — a JSON body (no file), e.g. for a metadata-only request.

An optional **`onComplete`** request (same shape) fires only after the main upload returns `2xx` — typically to register the segment on the backend. If `onComplete` fails, the whole upload is reported as failed.

### Transport behavior

- **HTTP/1.1 is forced.** It removes the HTTP/2 `stream was reset: NO_ERROR` failure class that bites large bodies, and a single big upload gains nothing from h2.
- **Retries** the idempotent main upload on network errors (`IOException`) **and** transient `5xx`, up to 3 attempts with linear backoff. A `4xx` is deterministic and never retried (the phone must re-issue with a fresh descriptor). The non-idempotent `onComplete` retries on network errors **only**, so it's never double-fired on a real response.
- **Call timeout scales with file size** against a conservative throughput floor (~0.5 Mbps), so a slow-but-healthy link isn't aborted mid-transfer.
- **No BLE fallback** — video files are far too large. On any terminal failure the clip is **kept on device** for a later retry.

### Progress and result

Upload status is reported on `video_recording_status`:

| Status             | Payload                                  | Meaning                                  |
| ------------------ | ---------------------------------------- | ---------------------------------------- |
| `upload_started`   | —                                        | Upload thread started.                   |
| `uploading`        | `progress`, `bytesSent`, `bytesTotal`    | Progress, throttled (~every 5% + final 100%). |
| `upload_completed` | —                                        | Upload (and `onComplete`, if any) succeeded. |
| `error` / `upload_failed` | error message                     | Terminal failure; clip retained on device. `upload_failed` is the classified status the phone keys off to re-issue with a fresh upload target. |

## Re-upload (`upload_video`)

When an upload fails, the clip remains on the glasses and the phone can retry it:

- `upload_video` carries the same `upload` / `onComplete` descriptor and the original `requestId`.
- The glasses locate the clip by `requestId` — recordings live at `<mediaDir>/VID_<timestamp>_<rand>_<requestId>/base.mp4`, so the capture directory name ends with the `requestId` — and run the described upload again. When a start-retry left several dirs with the same `requestId` (aborted 0-byte attempts plus the real recording), the glasses pick the **largest** `base.mp4` so a re-upload never grabs an empty/abandoned clip.
- Requires both `requestId` and a valid `upload` descriptor, else `invalid_upload_request`. If no matching clip is found, an `error` media response is sent.

## Resource constraints

- **Battery** — start is gated at the photo/video battery floor; below it the stop/start is rejected.
- **Camera contention** — recording shares the single camera with photos and streaming; only one can own the camera at a time.
- **Storage** — clips are kept on device until a successful upload (or indefinitely when `save` is set or no upload target is given). Failed uploads are retained for `upload_video`.

## Logcat tags

| Tag                   | What                                                        |
| --------------------- | ---------------------------------------------------------- |
| `VideoCommandHandler` | Command dispatch, stop ACK, idempotency, upload routing    |
| `MediaCaptureService` | Recording lifecycle, described/webhook upload, progress    |
| `MediaManager`        | Status + ACK callback dispatch over BLE                    |

Useful filters:

```bash
# Everything video-recording related
adb logcat | grep -E "VideoCommandHandler|MediaCaptureService"

# Stop ACK / reliable-stop traffic
adb logcat | grep -E "stop_video_recording_ack|Duplicate stop_video_recording"
```

## Common issues

- **Stop seems ignored** — the ACK was likely dropped; the phone resends the same `requestId` and the glasses re-ACK. If `not_recording` comes back, the recorder already stopped (e.g. auto-stop) — treat as idempotent.
- **S3 `PUT` rejected with `SignatureDoesNotMatch` (403)** — a `Content-Type` was sent that the URL wasn't signed with. For SigV2 presigned URLs, omit `Content-Type` from `upload.headers`.
- **Upload never completes on a slow link** — expected up to the size-scaled call timeout; below the throughput floor it will eventually fail and retain the clip for `upload_video`.
- **`invalid_upload_request` on `upload_video`** — the command is missing `requestId` or a valid `upload` descriptor.
