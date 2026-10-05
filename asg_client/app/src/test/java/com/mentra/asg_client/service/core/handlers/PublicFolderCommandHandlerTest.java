package com.mentra.asg_client.service.core.handlers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Application;
import com.mentra.asg_client.io.file.core.FileManager;
import com.mentra.asg_client.io.media.utils.MediaUtils;
import com.mentra.asg_client.service.communication.interfaces.ICommunicationManager;
import com.mentra.asg_client.service.legacy.managers.AsgClientServiceManager;
import com.mentra.asg_client.settings.AsgSettings;
import java.io.File;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Verifies save_on_public_folder persists the setting and redirects the capture directory. */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 33)
public class PublicFolderCommandHandlerTest {

    private AsgClientServiceManager serviceManager;
    private AsgSettings settings;
    private FileManager fileManager;
    private ICommunicationManager communicationManager;
    private PublicFolderCommandHandler handler;

    @Before
    public void setUp() {
        serviceManager = mock(AsgClientServiceManager.class);
        settings = mock(AsgSettings.class);
        fileManager = mock(FileManager.class);
        communicationManager = mock(ICommunicationManager.class);

        when(serviceManager.getAsgSettings()).thenReturn(settings);
        when(serviceManager.getFileManager()).thenReturn(fileManager);

        handler = new PublicFolderCommandHandler(serviceManager, communicationManager);
    }

    @Test
    public void supportsOnlyItsOwnCommand() {
        assertThat(handler.getSupportedCommandTypes()).containsExactly("save_on_public_folder");
    }

    @Test
    public void enabled_persistsAndRedirectsToThePublicFolder() throws Exception {
        JSONObject data = new JSONObject().put("enabled", true);

        boolean handled = handler.handleCommand("save_on_public_folder", data);

        assertThat(handled).isTrue();
        verify(settings).setSaveOnPublicFolder(true);
        verify(fileManager).setPublicMediaDirectory(MediaUtils.getPublicCaptureDirectory());
    }

    /** Null is what returns the capture pipeline to the private media directory. */
    @Test
    public void disabled_persistsAndClearsTheRedirect() throws Exception {
        JSONObject data = new JSONObject().put("enabled", false);

        boolean handled = handler.handleCommand("save_on_public_folder", data);

        assertThat(handled).isTrue();
        verify(settings).setSaveOnPublicFolder(false);
        verify(fileManager).setPublicMediaDirectory(null);
    }

    /** Off by default: a command with no field must not silently turn the redirect on. */
    @Test
    public void missingField_defaultsToDisabled() throws Exception {
        boolean handled = handler.handleCommand("save_on_public_folder", new JSONObject());

        assertThat(handled).isTrue();
        verify(settings).setSaveOnPublicFolder(false);
        verify(fileManager).setPublicMediaDirectory(null);
    }

    @Test
    public void unavailableFileManager_isRefusedRatherThanCrashing() throws Exception {
        when(serviceManager.getFileManager()).thenReturn(null);

        boolean handled =
                handler.handleCommand("save_on_public_folder", new JSONObject().put("enabled", true));

        assertThat(handled).isFalse();
        verify(settings, never()).setSaveOnPublicFolder(true);
    }

    @Test
    public void unsupportedCommand_returnsFalse() throws Exception {
        assertThat(handler.handleCommand("take_photo", new JSONObject())).isFalse();
        verify(fileManager, never()).setPublicMediaDirectory(any(File.class));
    }
}
