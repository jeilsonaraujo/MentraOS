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

    /** Main upload (the bytes) for the single-request described path. */
    public final RequestSpec upload;

    /** Optional follow-up request fired only after the upload returns 2xx (may be {@code null}). */
    public final RequestSpec onComplete;

    private UploadSpec(RequestSpec upload, RequestSpec onComplete) {
        this.upload = upload;
        this.onComplete = onComplete;
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
        RequestSpec upload = parseRequest(data.optJSONObject("upload"));
        if (upload == null) {
            return null;
        }
        RequestSpec onComplete = parseRequest(data.optJSONObject("onComplete"));
        return new UploadSpec(upload, onComplete);
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
