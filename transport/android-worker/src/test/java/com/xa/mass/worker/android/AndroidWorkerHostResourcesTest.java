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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

@RunWith(RobolectricTestRunner.class)
public class AndroidWorkerHostResourcesTest {

    @Test
    public void commandCapacityRejectsImmediatelyWithoutQueueing()
            throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        try (AndroidWorkerHostResources resources =
                     AndroidWorkerHostResources.create(
                             1,
                             1,
                             "test-android-capacity"
                     )) {
            Executor executor = resources.commandExecutor();
            executor.execute(() -> {
                entered.countDown();
                awaitLatch(release);
                completed.countDown();
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertThrows(
                    RejectedExecutionException.class,
                    () -> executor.execute(() -> {
                    })
            );

            release.countDown();
            assertTrue(completed.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void clientsBorrowOneLooperAndCloseIndependently()
            throws Exception {
        AndroidWorkerHostResources resources =
                AndroidWorkerHostResources.create(
                        2,
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

    private static void awaitLatch(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(error);
        }
    }
}
