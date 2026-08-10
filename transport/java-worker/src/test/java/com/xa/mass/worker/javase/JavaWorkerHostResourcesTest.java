package com.xa.mass.worker.javase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xa.mass.transport.client.TextMessageClient;
import com.xa.mass.transport.client.TextMessageClientFactory;
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

class JavaWorkerHostResourcesTest {

    @Test
    void validatesStaticReplicaSizingInputs() {
        assertThrows(
                IllegalArgumentException.class,
                () -> JavaWorkerHostResources.create(
                        0,
                        "workers",
                        false
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> JavaWorkerHostResources.create(
                        1,
                        " ",
                        false
                )
        );
    }

    @Test
    void createsOneBoundedNamedBundleAndRejectsAfterClose()
            throws Exception {
        JavaWorkerHostResources resources =
                JavaWorkerHostResources.create(
                        8,
                        "test-java-worker",
                        true
                );
        ExecutorService control = executor(resources, "controlExecutor");
        ExecutorService network = executor(resources, "networkScheduler");
        ExecutorService sockets = executor(resources, "socketExecutor");

        assertEquals(4, ((ThreadPoolExecutor) control).getCorePoolSize());
        assertThread(control, "test-java-worker-control-");
        assertThread(network, "test-java-worker-network-");
        assertThread(sockets, "test-java-worker-socket-");

        resources.close();
        resources.close();

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
    void websocketClientsBorrowOneNetworkScheduler()
            throws Exception {
        try (JavaWorkerHostResources resources =
                     JavaWorkerHostResources.create(
                             2,
                             "test-java-shared",
                             true
                     )) {
            TextMessageClientFactory factory =
                    resources.textClientFactory(
                            WorkerTransportType.WEBSOCKET,
                            Duration.ofSeconds(1),
                            TextMessageReconnectPolicy.defaults()
                    );
            TextMessageClient first = factory.create(
                    URI.create("ws://127.0.0.1:18084/first")
            );
            TextMessageClient second = factory.create(
                    URI.create("ws://127.0.0.1:18084/second")
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
            JavaWorkerHostResources resources,
            String fieldName
    ) throws Exception {
        Field field = JavaWorkerHostResources.class.getDeclaredField(
                fieldName
        );
        field.setAccessible(true);
        return (ExecutorService) field.get(resources);
    }

    private static Object field(Object target, String fieldName)
            throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void assertThread(
            ExecutorService executor,
            String expectedPrefix
    ) throws Exception {
        Future<Thread> future = executor.submit(Thread::currentThread);
        Thread thread = future.get();
        assertTrue(thread.getName().startsWith(expectedPrefix));
        assertTrue(thread.isDaemon());
    }
}
