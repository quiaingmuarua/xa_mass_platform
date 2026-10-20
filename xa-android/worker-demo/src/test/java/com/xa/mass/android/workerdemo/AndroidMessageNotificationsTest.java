package com.xa.mass.android.workerdemo;

import android.Manifest;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Looper;
import com.xa.mass.worker.runtime.WorkerLifecycle;
import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 33)
public class AndroidMessageNotificationsTest {
    private AndroidMessageDemo channel() {
        return new AndroidMessageDemo(() -> new WorkerLifecycle.Snapshot(
                WorkerLifecycle.State.RUNNING, "worker", URI.create("ws://localhost/worker"), null),
                "+8617000000000", () -> 0L, () -> 1000L, 200, 200, 100, 600_000);
    }

    @Test public void notificationActionsKeepDistinctFullMessageCoordinatesWithoutReporting() {
        Application app = RuntimeEnvironment.getApplication();
        AndroidMessageDemo messages = channel();
        AtomicInteger reports = new AtomicInteger();
        messages.send(AndroidMessageDemoTest.input("one"), (tag, time, content) -> { reports.incrementAndGet(); return true; });
        AndroidMessageNotifications notifications = new AndroidMessageNotifications(app, messages);
        Notification first = notifications.notification(messages.recentMessages().get(0));
        messages.send(AndroidMessageDemoTest.input("two"), (tag, time, content) -> true);
        Notification second = notifications.notification(messages.recentMessages().get(0));
        assertNotEquals(first.contentIntent, second.contentIntent);
        assertNotEquals(first.actions[0].actionIntent, second.actions[0].actionIntent);
        assertNotEquals(shadowOf(first.contentIntent).getSavedIntent().getData(),
                shadowOf(second.contentIntent).getSavedIntent().getData());
        assertEquals(MessageReplyReceiver.class.getName(),
                shadowOf(first.actions[0].actionIntent).getSavedIntent().getComponent().getClassName());
        assertEquals(AndroidMessageNotifications.REPLY, first.actions[0].getRemoteInputs()[0].getResultKey());
        assertEquals(0, reports.get());
    }

    @Test public void permissionDenialDoesNotBlockAcceptanceAndBurstKeepsOneNotification() {
        Application app = RuntimeEnvironment.getApplication();
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS);
        AndroidMessageDemo messages = channel();
        AndroidMessageNotifications notifications = new AndroidMessageNotifications(app, messages);
        messages.addListener(notifications::requestRefresh);
        messages.send(AndroidMessageDemoTest.input("without-permission"), (tag, time, content) -> true);
        shadowOf(Looper.getMainLooper()).idle();
        assertFalse(notifications.available());
        assertEquals(1, messages.recentMessages().size());
        NotificationManager manager = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        assertEquals(0, manager.getActiveNotifications().length);
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        for (int i = 0; i < 123; i++) messages.send(AndroidMessageDemoTest.input("id-" + i), (tag, time, content) -> true);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, manager.getActiveNotifications().length);
        assertEquals(AndroidMessageNotifications.NOTIFICATION_ID, manager.getActiveNotifications()[0].getId());
        messages.revokeReporters();
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(0, manager.getActiveNotifications().length);
    }

    @Test public void newProcessPresentationCancelsOnlyItsOwnOldNotification() {
        Application app = RuntimeEnvironment.getApplication();
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        AndroidMessageDemo messages = channel();
        messages.send(AndroidMessageDemoTest.input("one"), (tag, time, content) -> true);
        AndroidMessageNotifications old = new AndroidMessageNotifications(app, messages);
        old.requestRefresh();
        shadowOf(Looper.getMainLooper()).idle();
        NotificationManager manager = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        assertEquals(1, manager.getActiveNotifications().length);
        new AndroidMessageNotifications(app, channel());
        assertEquals(0, manager.getActiveNotifications().length);
    }
}
