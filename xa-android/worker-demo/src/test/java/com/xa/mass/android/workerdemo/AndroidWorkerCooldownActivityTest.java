package com.xa.mass.android.workerdemo;

import static org.junit.Assert.*;

import android.widget.TextView;
import com.xa.mass.android.capabilities.AndroidDemoCapabilities;
import com.xa.mass.worker.runtime.WorkerLifecycle;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(application = AndroidWorkerCooldownActivityTest.TestApplication.class, sdk = 33)
public class AndroidWorkerCooldownActivityTest {

    public static class TestApplication extends AndroidWorkerDemoApplication {
        final AndroidWorkerHostControlTest.Fixture fixture = new AndroidWorkerHostControlTest.Fixture(0);
        private AndroidDemoCapabilities capabilities;

        @Override public void onCreate() {
            capabilities = new AndroidDemoCapabilities(this);
            fixture.control.start();
        }
        @Override boolean hasWorker() { return true; }
        @Override AndroidWorkerHostControl workerControl() { return fixture.control; }
        @Override AndroidDemoCapabilities demoCapabilities() { return capabilities; }
        @Override public void onTerminate() { fixture.control.close(); }
    }

    @Test public void recreationDoesNotCheckAgainAndPageStopCancelsRecovery() {
        TestApplication app = (TestApplication) RuntimeEnvironment.getApplication();
        app.fixture.worker.stopped(WorkerLifecycle.StopReason.COOLDOWN, 1_900_000_000_000L);
        Runnable pending = app.fixture.scheduler.pending.get(0).action;
        WorkerLifecycle.Snapshot old = app.fixture.worker.snapshot();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            assertEquals(1, app.fixture.worker.starts);
            assertTrue(text(activity).contains("COOLDOWN"));
            assertTrue(text(activity).contains("预计下次接入检查"));
            assertTrue(activity.get().findViewById(R.id.disconnectButton).isEnabled());
            activity.recreate();
            assertEquals(1, app.fixture.worker.starts);
            activity.get().findViewById(R.id.disconnectButton).performClick();
            pending.run();
            app.fixture.worker.emit(old);
            assertEquals(1, app.fixture.worker.starts);
            assertFalse(app.fixture.control.status().desiredRunning());
            assertFalse(activity.get().findViewById(R.id.disconnectButton).isEnabled());
            assertFalse(text(activity).contains("预计下次接入检查"));
            activity.get().findViewById(R.id.connectButton).performClick();
            assertEquals(2, app.fixture.worker.starts);
        }
    }

    private static String text(ActivityController<MainActivity> controller) {
        return ((TextView) controller.get().findViewById(R.id.connectionControlValue)).getText().toString();
    }
}
