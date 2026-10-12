package com.mentra.asg_client.io.media.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.robolectric.Shadows.shadowOf;

import android.os.Looper;

import com.mentra.asg_client.io.media.managers.MediaUploadQueueManager;
import com.mentra.asg_client.service.system.interfaces.IStateManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * A stopped recording is always reported and uploaded — never judged and deleted on the glasses.
 *
 * <p>A post-stop integrity check used to delete any clip it could not read and report it lost;
 * it misread good 1080p clips (a first frame over its 256 KB buffer) and deleted five of them in
 * five days. A file the glasses cannot parse may still be recoverable off the device, so it goes
 * up like any other.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class MediaCaptureServiceRecordedVideoTest {

    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void uploadsAndKeepsAClipTheGlassesCannotParse() throws Exception {
        MediaCaptureService service =
                new MediaCaptureService(
                        RuntimeEnvironment.getApplication(),
                        mock(MediaUploadQueueManager.class),
                        null,
                        mock(IStateManager.class));
        MediaCaptureService.MediaCaptureListener listener =
                mock(MediaCaptureService.MediaCaptureListener.class);
        service.setMediaCaptureListener(listener);
        File clip = unreadableClip();

        service.finishRecordedVideo("req-1", clip.getAbsolutePath(), null, true);
        settle();

        verify(listener).onVideoRecordingStopped("req-1", clip.getAbsolutePath());
        verify(listener).onVideoUploaded("req-1", clip.getAbsolutePath());
        verify(listener, never()).onMediaError(eq("req-1"), anyString(), anyInt());
        assertThat(clip).exists();
    }

    /** Bytes that are not an MP4: the platform's extractor cannot open them. */
    private File unreadableClip() throws IOException {
        File clip = folder.newFile("base.mp4");
        try (FileOutputStream out = new FileOutputStream(clip)) {
            out.write(new byte[64 * 1024]);
        }
        return clip;
    }

    /** Whatever happens off the main thread, wait for it to land back on it. */
    private static void settle() throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(20);
        }
        shadowOf(Looper.getMainLooper()).idle();
    }
}
