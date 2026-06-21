package com.mentra.asg_client.io.media.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

/**
 * Verifies {@link UploadSpec#fromJson(JSONObject)} parses the app-described upload descriptor
 * carried on {@code stop_video_recording} / {@code upload_video}, and that it returns {@code null}
 * (falling back to the legacy webhook path) whenever there is no usable {@code upload} object.
 *
 * <p>Pure {@code org.json} parsing — no Robolectric needed (mirrors {@code RtmpStreamConfigTest}).
 */
public class UploadSpecTest {

    @Test
    public void fromJson_null_returnsNull() {
        assertNull(UploadSpec.fromJson(null));
    }

    @Test
    public void fromJson_noUploadObject_returnsNull() {
        // Legacy stop command (webhookUrl/authToken only) → no described upload.
        assertNull(UploadSpec.fromJson(new JSONObject()));
    }

    @Test
    public void fromJson_uploadMissingUrl_returnsNull() throws JSONException {
        JSONObject data = new JSONObject().put("upload", new JSONObject().put("method", "PUT"));
        // An empty/missing url means the descriptor isn't actionable.
        assertNull(UploadSpec.fromJson(data));
    }

    @Test
    public void fromJson_minimalUpload_appliesDefaults() throws JSONException {
        JSONObject data =
                new JSONObject().put("upload", new JSONObject().put("url", "https://s3/clip.mp4"));

        UploadSpec spec = UploadSpec.fromJson(data);

        assertNotNull(spec);
        assertEquals("https://s3/clip.mp4", spec.upload.url);
        assertEquals("POST", spec.upload.method); // defaults to POST
        assertEquals("file", spec.upload.bodyMode); // defaults to raw file
        assertEquals("video", spec.upload.fileField); // multipart default
        assertNull(spec.upload.headers);
        assertNull(spec.upload.fields);
        assertNull(spec.upload.jsonBody);
        assertNull(spec.onComplete);
    }

    @Test
    public void fromJson_rawPutWithHeaders_parsesMethodAndHeaders() throws JSONException {
        JSONObject upload =
                new JSONObject()
                        .put("method", "PUT")
                        .put("url", "https://bucket.s3.amazonaws.com/clip.mp4?X-Amz-Signature=abc")
                        .put("body", "file")
                        .put(
                                "headers",
                                new JSONObject()
                                        .put("Content-Type", "video/mp4")
                                        .put("x-amz-meta-id", "video-1"));
        JSONObject data = new JSONObject().put("upload", upload);

        UploadSpec spec = UploadSpec.fromJson(data);

        assertNotNull(spec);
        assertEquals("PUT", spec.upload.method);
        assertEquals("file", spec.upload.bodyMode);
        assertNotNull(spec.upload.headers);
        assertEquals("video/mp4", spec.upload.headers.get("Content-Type"));
        assertEquals("video-1", spec.upload.headers.get("x-amz-meta-id"));
    }

    @Test
    public void fromJson_multipartBody_parsesFileFieldAndFields() throws JSONException {
        JSONObject upload =
                new JSONObject()
                        .put("url", "https://api.example.com/upload")
                        .put("body", "multipart")
                        .put("fileField", "clip")
                        .put("fields", new JSONObject().put("type", "video_upload"));
        JSONObject data = new JSONObject().put("upload", upload);

        UploadSpec spec = UploadSpec.fromJson(data);

        assertNotNull(spec);
        assertEquals("multipart", spec.upload.bodyMode);
        assertEquals("clip", spec.upload.fileField);
        assertNotNull(spec.upload.fields);
        assertEquals("video_upload", spec.upload.fields.get("type"));
    }

    @Test
    public void fromJson_jsonObjectBody_isSerialized() throws JSONException {
        JSONObject upload =
                new JSONObject()
                        .put("url", "https://api.example.com/segments")
                        .put("body", "json")
                        .put("json", new JSONObject().put("key", "clip.mp4"));
        JSONObject data = new JSONObject().put("upload", upload);

        UploadSpec spec = UploadSpec.fromJson(data);

        assertNotNull(spec);
        assertEquals("json", spec.upload.bodyMode);
        assertNotNull(spec.upload.jsonBody);
        // Re-parse to avoid asserting on key ordering of the serialized form.
        assertEquals("clip.mp4", new JSONObject(spec.upload.jsonBody).getString("key"));
    }

    @Test
    public void fromJson_jsonStringBody_passedThroughVerbatim() throws JSONException {
        JSONObject upload =
                new JSONObject()
                        .put("url", "https://api.example.com/segments")
                        .put("body", "json")
                        .put("json", "{\"key\":\"clip.mp4\"}");
        JSONObject data = new JSONObject().put("upload", upload);

        UploadSpec spec = UploadSpec.fromJson(data);

        assertNotNull(spec);
        assertEquals("{\"key\":\"clip.mp4\"}", spec.upload.jsonBody);
    }

    @Test
    public void fromJson_withOnComplete_parsesBothRequests() throws JSONException {
        JSONObject data =
                new JSONObject()
                        .put(
                                "upload",
                                new JSONObject()
                                        .put("method", "PUT")
                                        .put("url", "https://s3/clip.mp4"))
                        .put(
                                "onComplete",
                                new JSONObject()
                                        .put("method", "POST")
                                        .put("url", "https://api.example.com/complete")
                                        .put("body", "json")
                                        .put("json", new JSONObject().put("requestId", "video-1")));

        UploadSpec spec = UploadSpec.fromJson(data);

        assertNotNull(spec);
        assertNotNull(spec.onComplete);
        assertEquals("POST", spec.onComplete.method);
        assertEquals("https://api.example.com/complete", spec.onComplete.url);
        assertEquals("json", spec.onComplete.bodyMode);
        assertEquals("video-1", new JSONObject(spec.onComplete.jsonBody).getString("requestId"));
    }

    @Test
    public void fromJson_onCompleteMissingUrl_ignoredButUploadKept() throws JSONException {
        JSONObject data =
                new JSONObject()
                        .put("upload", new JSONObject().put("url", "https://s3/clip.mp4"))
                        .put("onComplete", new JSONObject().put("method", "POST")); // no url

        UploadSpec spec = UploadSpec.fromJson(data);

        assertNotNull(spec);
        assertNull(spec.onComplete);
    }
}
