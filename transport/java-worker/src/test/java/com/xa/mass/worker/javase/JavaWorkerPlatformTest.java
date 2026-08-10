package com.xa.mass.worker.javase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xa.mass.transport.client.TextMessageClient;
import com.xa.mass.transport.client.TextMessageReconnectPolicy;
import com.xa.mass.transport.client.WorkerTransportType;

import java.lang.reflect.Field;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import org.junit.jupiter.api.Test;

class JavaWorkerPlatformTest {

    @Test
    void createsOneBoundedManagedBundleAndRejectsAfterClose()
            throws Exception {
        JavaWorkerPlatform platform = JavaWorkerPlatform.create(
                8,
                "test-java-worker",
                true
        );
        ExecutorService control = executor(platform, "controlExecutor");
        ExecutorService network = executor(platform, "networkScheduler");
        ExecutorService sockets = executor(platform, "socketExecutor");

        assertEquals(4, ((ThreadPoolExecutor) control).getCorePoolSize());
        assertThread(control, "test-java-worker-control-", true);
        assertThread(network, "test-java-worker-network-", true);
        assertThread(sockets, "test-java-worker-socket-", true);

        platform.close();
        platform.close();

        assertTrue(control.isShutdown());
        assertTrue(network.isShutdown());
        assertTrue(sockets.isShutdown());
        assertThrows(
                RejectedExecutionException.class,
                () -> control.execute(() -> {
                })
        );
    }

    @Test
    void standaloneControlThreadIsNonDaemon() throws Exception {
        try (JavaWorkerPlatform platform =
                     JavaWorkerPlatform.standalone("group-1")) {
            assertThread(
                    executor(platform, "controlExecutor"),
                    "xa-java-worker-group-1-control-",
                    false
            );
        }
    }

    @Test
    void websocketClientsBorrowOneNetworkScheduler()
            throws Exception {
        try (JavaWorkerPlatform platform = JavaWorkerPlatform.create(
                2,
                "test-java-shared",
                true
        )) {
            TextMessageClient first = platform.textClient(
                    WorkerTransportType.WEBSOCKET,
                    URI.create("ws://127.0.0.1:18084/first"),
                    Duration.ofSeconds(1),
                    TextMessageReconnectPolicy.defaults()
            );
            TextMessageClient second = platform.textClient(
                    WorkerTransportType.WEBSOCKET,
                    URI.create("ws://127.0.0.1:18084/second"),
                    Duration.ofSeconds(1),
                    TextMessageReconnectPolicy.defaults()
            );
            ScheduledExecutorService firstScheduler =
                    (ScheduledExecutorService) field(
                            first,
                            "networkScheduler"
                    );
            ScheduledExecutorService secondScheduler =
                    (ScheduledExecutorService) field(
                            second,
                            "networkScheduler"
                    );

            assertSame(firstScheduler, secondScheduler);
            first.close();
            assertEquals(
                    "alive",
                    firstScheduler.submit(() -> "alive").get()
            );
            second.close();
        }
    }

    private static ExecutorService executor(
            JavaWorkerPlatform platform,
            String fieldName
    ) throws Exception {
        Field field = JavaWorkerPlatform.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (ExecutorService) field.get(platform);
    }

    private static Object field(Object target, String fieldName)
            throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void assertThread(
            ExecutorService executor,
            String expectedPrefix,
            boolean daemon
    ) throws Exception {
        Future<Thread> future = executor.submit(Thread::currentThread);
        Thread thread = future.get();
        assertTrue(thread.getName().startsWith(expectedPrefix));
        assertEquals(daemon, thread.isDaemon());
    }
}
