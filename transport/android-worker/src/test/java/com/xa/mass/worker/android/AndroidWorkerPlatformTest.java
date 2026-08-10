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
public class AndroidWorkerPlatformTest {

    @Test
    public void clientsBorrowOneLooperAndCloseIndependently()
            throws Exception {
        AndroidWorkerPlatform platform =
                AndroidWorkerPlatform.create("test-group");
        HandlerThread networkThread = (HandlerThread) field(
                platform,
                "networkThread"
        );
        TextMessageClient first = platform.textClient(
                URI.create("ws://127.0.0.1:18084/first"),
                Duration.ofSeconds(1),
                TextMessageReconnectPolicy.defaults()
        );
        TextMessageClient second = platform.textClient(
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
            platform.close();
        }
        assertThrows(
                IllegalStateException.class,
                platform::controlExecutor
        );
    }

    private static Object field(Object target, String fieldName)
            throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }
}
