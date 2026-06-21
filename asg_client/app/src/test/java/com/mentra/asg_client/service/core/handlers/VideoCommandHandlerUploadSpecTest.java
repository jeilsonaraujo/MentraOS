package com.mentra.asg_client.service.core.handlers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mentra.asg_client.io.media.core.MediaCaptureService;
import com.mentra.asg_client.io.media.core.UploadSpec;
import com.mentra.asg_client.service.legacy.managers.AsgClientServiceManager;
import com.mentra.asg_client.service.media.interfaces.IMediaManager;
import com.mentra.asg_client.service.system.interfaces.IStateManager;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Covers the app-described upload path on {@code stop_video_recording}, the stop-acceptance ACK and
 * its idempotency, and the {@code upload_video} re-upload command — the generic upload surface that
 * sits alongside the legacy webhook path verified by {@link VideoCommandHandlerStopUploadTest}.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class VideoCommandHandlerUploadSpecTest {

    private AsgClientServiceManager serviceManager;
    private IMediaManager streamingManager;
    private MediaCaptureService captureService;
    private VideoCommandHandler handler;

    @Before
    public void setUp() {
        serviceManager = mock(AsgClientServiceManager.class);
        streamingManager = mock(IMediaManager.class);
        IStateManager stateManager = mock(IStateManager.class);
        captureService = mock(MediaCaptureService.class);

        when(serviceManager.getMediaCaptureService()).thenReturn(captureService);
        when(captureService.isRecordingVideo()).thenReturn(true);

        handler =
                new VideoCommandHandler(
                        null, serviceManager, streamingManager, null, stateManager);
    }

    private static JSONObject rawPutUpload(String url) throws Exception {
        return new JSONObject()
                .put("method", "PUT")
                .put("url", url)
                .put("headers", new JSONObject().put("Content-Type", "video/mp4"))
                .put("body", "file");
    }

    @Test
    public void getSupportedCommandTypes_includesUploadVideo() {
        assertThat(handler.getSupportedCommandTypes()).contains("upload_video");
    }

    @Test
    public void stop_withUploadDescriptor_routesToDescribedStopAndAcks() throws Exception {
        JSONObject data =
                new JSONObject()
                        .put("requestId", "req-123")
                        .put("upload", rawPutUpload("https://s3/clip.mp4"));

        boolean handled = handler.handleStopCommand(data);

        assertThat(handled).isTrue();
        // Accepted → immediate ACK, before the upload runs.
        verify(streamingManager).sendStopRecordingAck("req-123");

        ArgumentCaptor<UploadSpec> specCaptor = ArgumentCaptor.forClass(UploadSpec.class);
        verify(captureService).handleStopVideoCommand(eq("req-123"), specCaptor.capture());
        assertThat(specCaptor.getValue().upload.url).isEqualTo("https://s3/clip.mp4");
        assertThat(specCaptor.getValue().upload.method).isEqualTo("PUT");

        // Described upload takes precedence over the legacy webhook path.
        verify(captureService, never())
                .handleStopVideoCommand(anyString(), anyString(), anyString());
    }

    @Test
    public void stop_withUploadDescriptorNoRequestId_routesToDirectDescribedStop()
            throws Exception {
        JSONObject data = new JSONObject().put("upload", rawPutUpload("https://s3/clip.mp4"));

        boolean handled = handler.handleStopCommand(data);

        assertThat(handled).isTrue();
        verify(captureService).stopVideoRecording(any(UploadSpec.class));
        // No requestId → no ACK to send, and no legacy webhook fallback.
        verify(streamingManager, never()).sendStopRecordingAck(anyString());
        verify(captureService, never()).stopVideoRecording(anyString(), anyString());
    }

    @Test
    public void stop_duplicateRequestId_reAcksOnlyAndDoesNotStopTwice() throws Exception {
        JSONObject first =
                new JSONObject()
                        .put("requestId", "req-9")
                        .put("upload", rawPutUpload("https://s3/clip.mp4"));
        JSONObject retry =
                new JSONObject()
                        .put("requestId", "req-9")
                        .put("upload", rawPutUpload("https://s3/clip.mp4"));

        assertThat(handler.handleStopCommand(first)).isTrue();
        assertThat(handler.handleStopCommand(retry)).isTrue();

        // The retry re-ACKs (phone keeps resending until it sees an ACK)...
        verify(streamingManager, times(2)).sendStopRecordingAck("req-9");
        // ...but the stop/upload only ever runs once.
        verify(captureService, times(1)).handleStopVideoCommand(eq("req-9"), any(UploadSpec.class));
    }

    @Test
    public void uploadVideo_valid_routesToUploadExistingVideo() throws Exception {
        JSONObject data =
                new JSONObject()
                        .put("requestId", "req-7")
                        .put("upload", rawPutUpload("https://s3/clip.mp4"));

        boolean handled = handler.handleUploadCommand(data);

        assertThat(handled).isTrue();
        ArgumentCaptor<UploadSpec> specCaptor = ArgumentCaptor.forClass(UploadSpec.class);
        verify(captureService).uploadExistingVideo(eq("req-7"), specCaptor.capture());
        assertThat(specCaptor.getValue().upload.url).isEqualTo("https://s3/clip.mp4");
    }

    @Test
    public void uploadVideo_missingUploadDescriptor_returnsInvalid() throws Exception {
        JSONObject data = new JSONObject().put("requestId", "req-7");

        boolean handled = handler.handleUploadCommand(data);

        assertThat(handled).isFalse();
        verify(streamingManager)
                .sendVideoRecordingStatusResponse("req-7", false, "invalid_upload_request", null);
        verify(captureService, never()).uploadExistingVideo(anyString(), any(UploadSpec.class));
    }

    @Test
    public void uploadVideo_serviceUnavailable_returnsFalse() throws Exception {
        when(serviceManager.getMediaCaptureService()).thenReturn(null);

        boolean handled =
                handler.handleUploadCommand(
                        new JSONObject()
                                .put("requestId", "req-7")
                                .put("upload", rawPutUpload("https://s3/clip.mp4")));

        assertThat(handled).isFalse();
        verify(streamingManager)
                .sendVideoRecordingStatusResponse("req-7", false, "service_unavailable", null);
    }
}
