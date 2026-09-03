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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import okhttp3.OkHttpClient;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.internal.concurrent.TaskRunner;
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
        OkHttpClient httpClient = (OkHttpClient) field(
                platform,
                "httpClient"
        );
        ExecutorService webSockets =
                httpClient.dispatcher().executorService();
        TaskRunner.RealBackend httpTasks =
                (TaskRunner.RealBackend) field(
                        platform,
                        "httpTaskBackend"
                );

        assertEquals(4, ((ThreadPoolExecutor) control).getCorePoolSize());
        assertEquals(8, httpClient.dispatcher().getMaxRequests());
        assertThread(control, "test-java-worker-control-", true);
        assertThread(network, "test-java-worker-network-", true);
        assertThread(sockets, "test-java-worker-socket-", true);
        assertVirtualThread(webSockets, "test-java-worker-websocket-");
        assertVirtualThread(
                httpTasks.getExecutor(),
                "test-java-worker-okhttp-task-"
        );

        platform.close();
        platform.close();

        assertTrue(control.isShutdown());
        assertTrue(network.isShutdown());
        assertTrue(sockets.isShutdown());
        assertTrue(webSockets.isShutdown());
        assertTrue(httpTasks.getExecutor().isShutdown());
        assertThrows(
                RejectedExecutionException.class,
                () -> control.execute(() -> {
                })
        );
    }

    @Test
    void supportsMoreThanDefaultDispatcherLimitOnVirtualReaders()
            throws Exception {
        int connectionCount = 128;
        ExecutorService webSocketExecutor;
        try (JavaWorkerPlatform platform = JavaWorkerPlatform.create(
                     connectionCount,
                     "test-java-scale",
                     true
             );
             MockWebServer server = new MockWebServer()) {
            server.start();
            CountDownLatch opened = new CountDownLatch(connectionCount);
            CopyOnWriteArrayList<Boolean> virtualCallbacks =
                    new CopyOnWriteArrayList<>();
            for (int index = 0; index < connectionCount; index++) {
                server.enqueue(new MockResponse.Builder()
                        .webSocketUpgrade(new WebSocketListener() {
                            @Override
                            public void onClosing(
                                    WebSocket webSocket,
                                    int code,
                                    String reason
                            ) {
                                webSocket.close(code, reason);
                            }
                        })
                        .build());
            }

            URI endpoint = URI.create(server.url("/workers").toString()
                    .replaceFirst("^http", "ws"));
            CopyOnWriteArrayList<TextMessageClient> clients =
                    new CopyOnWriteArrayList<>();
            webSocketExecutor = ((OkHttpClient) field(
                    platform,
                    "httpClient"
            )).dispatcher().executorService();
            try {
                for (int index = 0; index < connectionCount; index++) {
                    TextMessageClient client = platform.textClient(
                            WorkerTransportType.WEBSOCKET,
                            endpoint,
                            Duration.ofSeconds(5),
                            TextMessageReconnectPolicy.defaults()
                    );
                    clients.add(client);
                    client.start(new TextMessageClient.Listener() {
                        @Override
                        public void onOpen() {
                            virtualCallbacks.add(
                                    Thread.currentThread().isVirtual()
                            );
                            opened.countDown();
                        }

                        @Override
                        public void onMessage(String message) {
                        }

                        @Override
                        public void onEndpointTerminated() {
                        }
                    });
                }

                assertTrue(opened.await(30, TimeUnit.SECONDS));
                assertEquals(connectionCount, virtualCallbacks.size());
                assertTrue(virtualCallbacks.stream().allMatch(Boolean::valueOf));
                clients.get(0).close();
                assertTrue(clients.get(1).send("remaining-client-is-open"));
            } finally {
                clients.forEach(TextMessageClient::close);
            }
        }
        assertTrue(webSocketExecutor.isShutdown());
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

    private static void assertVirtualThread(
            ExecutorService executor,
            String expectedPrefix
    ) throws Exception {
        Thread thread = executor.submit(Thread::currentThread).get();
        assertTrue(thread.isVirtual());
        assertTrue(thread.getName().startsWith(expectedPrefix));
    }
}
