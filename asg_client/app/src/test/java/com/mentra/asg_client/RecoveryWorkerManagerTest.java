package com.mentra.asg_client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.robolectric.Shadows.shadowOf;

import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import androidx.test.core.app.ApplicationProvider;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class RecoveryWorkerManagerTest {

    private static final String RECOVERY_PACKAGE = "com.mentra.recovery";
    private static final String RECOVERY_LAUNCHER = "com.mentra.recovery.ui.LauncherActivity";

    @Before
    public void installRecoveryWorker() {
        Context app = ApplicationProvider.getApplicationContext();
        PackageInfo info = new PackageInfo();
        info.packageName = RECOVERY_PACKAGE;
        info.setLongVersionCode(10);
        info.applicationInfo = new ApplicationInfo();
        info.applicationInfo.packageName = RECOVERY_PACKAGE;
        shadowOf(app.getPackageManager()).installPackage(info);
        shadowOf(app.getPackageManager())
                .addActivityIfNotPresent(new ComponentName(RECOVERY_PACKAGE, RECOVERY_LAUNCHER));
    }

    @Test
    public void stockClient_startsTheInstalledRecoveryWorker() throws Exception {
        // Given — running under the stock id, with the recovery worker installed
        RecordingContext context = new RecordingContext("com.mentra.asg_client");

        // When
        new RecoveryWorkerManager(context).initialize();

        // Then — the worker is started
        assertThat(context.recoveryStarted.await(2, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    public void thirdpartyClient_neverStartsTheRecoveryWorker() throws Exception {
        // Given — running as our build, next to a disabled stock client the worker would
        // otherwise reinstall
        RecordingContext context = new RecordingContext("com.mentra.asg_client.thirdparty");

        // When
        new RecoveryWorkerManager(context).initialize();

        // Then — the worker is left alone
        assertThat(context.recoveryStarted.await(1, TimeUnit.SECONDS)).isFalse();
    }

    /** The app context under a chosen package id, recording any attempt to start the worker. */
    private static final class RecordingContext extends ContextWrapper {
        final CountDownLatch recoveryStarted = new CountDownLatch(1);
        private final String packageName;

        RecordingContext(String packageName) {
            super(ApplicationProvider.getApplicationContext());
            this.packageName = packageName;
        }

        @Override
        public String getPackageName() {
            return packageName;
        }

        @Override
        public Context getApplicationContext() {
            return this;
        }

        @Override
        public void startActivity(Intent intent) {
            record(intent);
        }

        @Override
        public void sendBroadcast(Intent intent, String receiverPermission) {
            record(intent);
        }

        private void record(Intent intent) {
            boolean toWorker =
                    RECOVERY_PACKAGE.equals(intent.getPackage())
                            || (intent.getComponent() != null
                                    && RECOVERY_PACKAGE.equals(intent.getComponent().getPackageName()));
            if (toWorker) recoveryStarted.countDown();
        }
    }
}
