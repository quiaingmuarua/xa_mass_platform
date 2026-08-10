package com.xa.mass.worker.android;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.os.Handler;
import android.os.HandlerThread;

import com.xa.mass.transport.client.TextMessageClient;
import com.xa.mass.transport.client.TextMessageReconnectPolicy;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.lang.reflect.Field;
import java.net.URI;
import java.time.Duration;

@RunWith(RobolectricTestRunner.class)
public class AndroidWorkerHostResourcesTest {

    @Test
    public void clientsBorrowOneLooperAndCloseIndependently()
            throws Exception {
        AndroidWorkerHostResources resources =
                AndroidWorkerHostResources.create(
                        2,
                        "test-android-shared"
                );
        HandlerThread networkThread = (HandlerThread) field(
                resources,
                "networkThread"
        );
        TextMessageClient first = resources.textClient(
                URI.create("ws://127.0.0.1:18084/first"),
                Duration.ofSeconds(1),
                TextMessageReconnectPolicy.defaults()
        );
        TextMessageClient second = resources.textClient(
                URI.create("ws://127.0.0.1:18084/second"),
                Duration.ofSeconds(1),
                TextMessageReconnectPolicy.defaults()
        );
        try {
            Handler firstHandler = (Handler) field(first, "handler");
            Handler secondHandler = (Handler) field(second, "handler");

            assertSame(
                    firstHandler.getLooper(),
                    secondHandler.getLooper()
            );
            first.close();
            assertTrue(networkThread.isAlive());
            second.close();
            assertTrue(networkThread.isAlive());
        } finally {
            resources.close();
        }
        assertThrows(
                IllegalStateException.class,
                resources::controlExecutor
        );
    }

    private static Object field(Object target, String fieldName)
            throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }
}
