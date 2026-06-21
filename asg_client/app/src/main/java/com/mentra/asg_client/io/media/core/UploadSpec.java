package com.mentra.asg_client.io.media.core;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import org.json.JSONObject;

/**
 * Generic, app-described upload instruction carried on the {@code stop_video_recording}
 * command. It lets the phone/backend dictate <em>how</em> the glasses upload a clip —
 * HTTP method, URL, headers, and body shape — without the firmware knowing anything about
 * S3, presigned URLs, or any particular scheme. The upload target can therefore change
 * (S3 PUT, presigned POST, a different cloud, the legacy multipart webhook…) without
 * re-flashing the glasses.
 *
 * <p>Shape on the wire:
 *
 * <pre>{@code
 * {
 *   "upload": {
 *     "method": "PUT",
 *     "url": "https://bucket.s3.amazonaws.com/...&X-Amz-Signature=...",
 *     "headers": { "Content-Type": "video/mp4" },
 *     "body": "file"                       // "file" | "multipart" | "json"
 *     // multipart: "fileField": "video", "fields": { "type": "video_upload" }
 *     // json:      "json": { ... }        // object or pre-serialized string
 *   },
 *   "onComplete": {                          // optional, fired after a 2xx upload
 *     "method": "POST",
 *     "url": "https://api.example.com/recordings/123/segments/complete",
 *     "headers": { "Authorization": "Bearer ..." },
 *     "body": "json",
 *     "json": { "requestId": "video-…", "key": "…" }
 *   }
 * }
 * }</pre>
 *
 * <p>When no {@code upload} object is present (legacy commands), {@link #fromJson(JSONObject)}
 * returns {@code null} and callers fall back to the multipart-webhook path.
 */
public final class UploadSpec {

    /** Main upload (the bytes) for the single-request described path. Null in the multipart path. */
    public final RequestSpec upload;

    /** Optional follow-up request fired only after the upload returns 2xx (may be {@code null}). */
    public final RequestSpec onComplete;

    /**
     * Resumable (S3 multipart) upload, driven by the glasses against the app's API. Non-null only
     * when the stop command carries a {@code multipartUpload} object; then {@link #upload} is null
     * and the caller uses the multipart executor instead of the single described request.
     */
    public final MultipartSpec multipart;

    private UploadSpec(RequestSpec upload, RequestSpec onComplete, MultipartSpec multipart) {
        this.upload = upload;
        this.onComplete = onComplete;
        this.multipart = multipart;
    }

    /**
     * Bootstrap for a glasses-driven resumable multipart upload. The glasses call the app's API
     * (authenticated with the short per-session {@code authToken}) to open the upload, fetch
     * per-part presigned URLs, PUT each part, and complete — so a dropped Wi-Fi connection only
     * re-sends the in-flight part. Carried on the regular {@code upload} descriptor (so it reuses
     * the existing plumbing), flagged by {@code body == "s3_multipart"}:
     *
     * <pre>{@code
     * "upload": {
     *   "body": "s3_multipart",
     *   "url": "https://api…",                          // apiBase
     *   "fields": { "recordingId": "<uuid>", "authToken": "…" }
     * }
     * }</pre>
     */
    public static final class MultipartSpec {
        /** API base URL, e.g. {@code https://dimenso-api…herokuapp.com} (no trailing slash needed). */
        public final String apiBase;

        public final String recordingId;

        /** Short per-session bearer token the glasses already hold (the recording/snapshot token). */
        public final String authToken;

        MultipartSpec(String apiBase, String recordingId, String authToken) {
            this.apiBase = apiBase;
            this.recordingId = recordingId;
            this.authToken = authToken;
        }
    }

    /** A single described HTTP request. */
    public static final class RequestSpec {
        /** {@code "PUT"} or {@code "POST"} (defaults to {@code POST}). */
        public final String method;

        public final String url;

        /** Headers to attach. {@code Content-Type} is honored via the request body, not here. */
        public final Map<String, String> headers;

        /** {@code "file"} (raw bytes), {@code "multipart"}, or {@code "json"} (defaults to file). */
        public final String bodyMode;

        /** Multipart only: form field name for the file part (defaults to {@code "video"}). */
        public final String fileField;

        /** Multipart only: extra string form fields. */
        public final Map<String, String> fields;

        /** JSON mode only: serialized request body. */
        public final String jsonBody;

        RequestSpec(
                String method,
                String url,
                Map<String, String> headers,
                String bodyMode,
                String fileField,
                Map<String, String> fields,
                String jsonBody) {
            this.method = method;
            this.url = url;
            this.headers = headers;
            this.bodyMode = bodyMode;
            this.fileField = fileField;
            this.fields = fields;
            this.jsonBody = jsonBody;
        }
    }

    /**
     * Parse an {@link UploadSpec} from a stop-command payload. Returns {@code null} when there is
     * no usable {@code upload} descriptor, so the caller falls back to the legacy webhook path.
     */
    public static UploadSpec fromJson(JSONObject data) {
        if (data == null) {
            return null;
        }
        JSONObject uploadObj = data.optJSONObject("upload");
        // A resumable upload reuses the same `upload` descriptor, flagged by body == "s3_multipart":
        // the app's API base goes in `url`, recordingId/authToken in `fields`. This rides the
        // existing `upload` plumbing (no new SDK/firmware field). It takes precedence when present.
        if (uploadObj != null && "s3_multipart".equals(uploadObj.optString("body", ""))) {
            MultipartSpec multipart = parseMultipart(uploadObj);
            if (multipart != null) {
                return new UploadSpec(null, null, multipart);
            }
        }
        RequestSpec upload = parseRequest(uploadObj);
        if (upload == null) {
            return null;
        }
        RequestSpec onComplete = parseRequest(data.optJSONObject("onComplete"));
        return new UploadSpec(upload, onComplete, null);
    }

    private static MultipartSpec parseMultipart(JSONObject uploadObj) {
        String apiBase = uploadObj.optString("url", "");
        JSONObject fields = uploadObj.optJSONObject("fields");
        String recordingId = (fields != null) ? fields.optString("recordingId", "") : "";
        String authToken = (fields != null) ? fields.optString("authToken", "") : "";
        if (apiBase.isEmpty() || recordingId.isEmpty()) {
            return null;
        }
        return new MultipartSpec(apiBase, recordingId, authToken);
    }

    private static RequestSpec parseRequest(JSONObject o) {
        if (o == null) {
            return null;
        }
        String url = o.optString("url", "");
        if (url.isEmpty()) {
            return null;
        }
        String method = o.optString("method", "POST");
        String bodyMode = o.optString("body", "file");
        String fileField = o.optString("fileField", "video");

        String jsonBody = null;
        if (o.has("json")) {
            JSONObject jsonObj = o.optJSONObject("json");
            jsonBody = (jsonObj != null) ? jsonObj.toString() : o.optString("json", "");
        }

        return new RequestSpec(
                method,
                url,
                parseStringMap(o.optJSONObject("headers")),
                bodyMode,
                fileField,
                parseStringMap(o.optJSONObject("fields")),
                jsonBody);
    }

    private static Map<String, String> parseStringMap(JSONObject o) {
        if (o == null) {
            return null;
        }
        Map<String, String> map = new HashMap<>();
        Iterator<String> keys = o.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            map.put(k, o.optString(k, ""));
        }
        return map;
    }
}
