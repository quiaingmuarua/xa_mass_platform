package com.xa.mass.transport;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xa.mass.transport.client.TextMessageClient;
import com.xa.mass.transport.client.TextMessageReconnectState;
import com.xa.mass.transport.client.WorkerControlClient;
import com.xa.mass.transport.client.WorkerPointClient;
import com.xa.mass.transport.client.WorkerTransportType;
import com.xa.mass.worker.execution.WorkerCommandExecutor;
import com.xa.mass.worker.runtime.PreparedWorker;
import com.xa.mass.worker.runtime.TextMessageWorkerTransportFactory;
import com.xa.mass.worker.runtime.WorkerLifecycle;
import com.xa.mass.worker.runtime.WorkerPreparation;
import com.xa.mass.worker.runtime.WorkerRunController;
import com.xa.mass.worker.transport.polling.PollingWorkerTransport;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.Executor;
import java.util.stream.Stream;

class WorkerCoreArchitectureBoundaryTest {

    @Test
    void coreIsJavaElevenAndHasNoPlatformImplementationDependency()
            throws IOException {
        Path project = Path.of("").toAbsolutePath();
        String source = readTree(project.resolve("src/main/java"));
        String build = Files.readString(project.resolve("build.gradle"));

        assertTrue(build.contains("id 'java-library'"));
        assertTrue(build.contains(
                "archivesName.set('xa-mass-worker-core')"
        ));
        assertTrue(build.contains(
                "api project(':transport:worker-delivery-contract')"
        ));
        assertTrue(build.contains("options.release = 11"));
        assertFalse(build.contains("foundation_jvm"));
        assertFalse(source.contains("com.xa.mass.foundation"));
        assertFalse(source.contains("ObservedText" + "MessageClient"));
        assertFalse(source.contains("new Thread("));
        assertFalse(source.contains("Executors.new"));
        assertFalse(source.contains(".shutdown("));
        assertFalse(source.contains("shutdownNow("));
        assertFalse(source.contains("publishPropertiesChanged"));
        assertFalse(source.contains("platform.worker.properties.changed"));

        for (String forbidden : new String[]{
                "okhttp3",
                "android.",
                "androidx.",
                "io.netty",
                "springframework",
                "io.lettuce",
                "java.net.Socket",
                "WebSocketListener",
                "HandlerThread",
                "server_jvm",
                "kernel_jvm"
        }) {
            assertFalse(source.contains(forbidden), forbidden);
            assertFalse(build.contains(forbidden), forbidden);
        }
    }

    @Test
    void networkClientContractsDoNotExposeProtocolOrPlatformTypes()
            throws Exception {
        for (Class<?> type : new Class<?>[]{
                WorkerPointClient.class,
                TextMessageClient.class,
                TextMessageClient.Listener.class
        }) {
            for (Method method : type.getDeclaredMethods()) {
                String signature = method.toGenericString();
                for (String forbidden : new String[]{
                        "DeliveryCommand",
                        "DeliveryReport",
                        "WorkerConnection" + "Bind",
                        "WorkerConnectionHello",
                        "okhttp3",
                        "android.",
                        "java.net.Socket"
                }) {
                    assertFalse(
                            signature.contains(forbidden),
                            signature
                    );
                }
            }
        }

        assertTrue(hasMethod(
                TextMessageClient.class,
                "send",
                String.class
        ));
        assertTrue(hasMethod(
                TextMessageClient.class,
                "closeCurrent",
                TextMessageClient.CloseReason.class
        ));
        assertFalse(hasMethod(TextMessageClient.class, "isConnected"));
        assertFalse(hasMethod(
                TextMessageClient.Listener.class,
                "onReconnecting"
        ));
        assertEquals(
                3,
                TextMessageClient.Listener.class
                        .getDeclaredMethods()
                        .length
        );
        assertTrue(hasMethod(
                TextMessageClient.Listener.class,
                "onOpen"
        ));
        assertTrue(hasMethod(
                TextMessageClient.Listener.class,
                "onMessage",
                String.class
        ));
        assertTrue(hasMethod(
                TextMessageClient.Listener.class,
                "onEndpointTerminated"
        ));
        assertThrows(
                ClassNotFoundException.class,
                () -> Class.forName(
                        "com.xa.mass.transport.client."
                                + "TextMessageClient"
                                + "Factory"
                )
        );
    }

    @Test
    void coreOwnsNoConnectionRegistryOrNetworkExecutionResources()
            throws Exception {
        Path sourceRoot = Path.of("").toAbsolutePath()
                .resolve("src/main/java");
        assertFalse(Files.exists(sourceRoot.resolve(
                "com/xa/mass/transport/client/"
                        + "TextMessageClientManager.java"
        )));
        assertTrue(Modifier.isFinal(
                TextMessageReconnectState.class.getModifiers()
        ));
        assertFalse(AutoCloseable.class.isAssignableFrom(
                TextMessageReconnectState.class
        ));
    }

    @Test
    void controlClientIsAPlatformNeutralPreparationContract() {
        for (Method method : WorkerControlClient.class
                .getDeclaredMethods()) {
            String signature = method.toGenericString();
            for (String forbidden : new String[]{
                    "okhttp3",
                    "android.",
                    "SharedPreferences",
                    "WorkerConnection" + "Bind"
            }) {
                assertFalse(signature.contains(forbidden), signature);
            }
        }

        assertTrue(hasMethod(
                WorkerControlClient.class,
                "prepare",
                String.class,
                WorkerTransportType.class,
                java.util.Map.class,
                java.time.Duration.class
        ));
        assertTrue(Arrays.equals(
                WorkerTransportType.values(),
                new WorkerTransportType[]{
                        WorkerTransportType.POLLING,
                        WorkerTransportType.WEBSOCKET,
                        WorkerTransportType.SOCKET
                }
        ));
    }

    @Test
    void assembledWorkersShareOneLifecycleAndObservationContract() {
        assertTrue(WorkerLifecycle.class.isAssignableFrom(
                WorkerRunController.class
        ));
        assertTrue(Arrays.equals(
                WorkerLifecycle.State.values(),
                new WorkerLifecycle.State[]{
                        WorkerLifecycle.State.STOPPED,
                        WorkerLifecycle.State.RUNNING
                }
        ));
        assertTrue(hasMethod(WorkerLifecycle.class, "start"));
        assertTrue(hasMethod(WorkerLifecycle.class, "stop"));
        for (Method method : WorkerLifecycle.class.getDeclaredMethods()) {
            String signature = method.toGenericString();
            assertFalse("send".equals(method.getName()), signature);
            assertFalse(signature.contains("DeliveryCommand"), signature);
            assertFalse(signature.contains("DeliveryReport"), signature);
        }
        assertFalse(hasMethod(WorkerLifecycle.class, "refreshProperties"));
        assertFalse(hasMethod(
                WorkerRunController.class,
                "publishPropertiesChanged"
        ));
        assertTrue(hasMethod(WorkerLifecycle.class, "snapshot"));
        assertFalse(hasMethod(WorkerLifecycle.class, "isConnected"));
        assertFalse(hasMethod(
                WorkerLifecycle.Snapshot.class,
                "connectionState"
        ));
        assertThrows(
                ClassNotFoundException.class,
                () -> Class.forName(
                        WorkerLifecycle.class.getName()
                                + "$ConnectionState"
                )
        );
        assertTrue(hasMethod(
                WorkerLifecycle.class,
                "addListener",
                WorkerLifecycle.Listener.class
        ));
        assertTrue(hasMethod(
                WorkerLifecycle.class,
                "removeListener",
                WorkerLifecycle.Listener.class
        ));
    }

    @Test
    void workerTransportStateMachinesOnlyAcceptCoreSeams()
            throws Exception {
        assertThrows(
                ClassNotFoundException.class,
                () -> Class.forName(
                        "com.xa.mass.worker.runtime."
                                + "WorkerExecutionResources"
                )
        );
        assertConstructor(
                PollingWorkerTransport.class,
                WorkerPointClient.class,
                String.class,
                WorkerCommandExecutor.class
        );
        assertThrows(
                NoSuchMethodException.class,
                () -> PollingWorkerTransport.class.getConstructor(
                        WorkerPointClient.class,
                        String.class,
                        Collection.class
                )
        );
        assertConstructor(
                WorkerRunController.class,
                WorkerPreparation.class,
                TextMessageWorkerTransportFactory.class,
                Executor.class
        );

        for (Class<?> transport : new Class<?>[]{
                PollingWorkerTransport.class,
                WorkerRunController.class
        }) {
            for (Constructor<?> constructor
                    : transport.getConstructors()) {
                String signature = constructor.toGenericString();
                for (String forbidden : new String[]{
                        "java.net.URI",
                        "okhttp",
                        "JdkLineSocketClient",
                        "OkHttpTextWebSocketClient",
                        "OkHttpWorkerPointClient"
                }) {
                    assertFalse(
                            signature.contains(forbidden),
                            signature
                    );
                }
            }
        }
    }

    @Test
    void coreOwnsTheFinalWorkerDefinitionRegistry() throws Exception {
        Class<?> dispatcher = Class.forName(
                "com.xa.mass.worker.execution.WorkerCommandDispatcher"
        );
        Path sourceRoot = Path.of("").toAbsolutePath()
                .resolve("src/main/java");
        String dispatcherSource = Files.readString(sourceRoot.resolve(
                "com/xa/mass/worker/execution/"
                        + "WorkerCommandDispatcher.java"
        ));

        assertEquals(0, dispatcher.getConstructors().length);
        assertThrows(
                ClassNotFoundException.class,
                () -> Class.forName(
                        "com.xa.mass.worker.execution."
                                + "WorkerEventDefinition"
                                + "Manager"
                )
        );
        assertFalse(Files.exists(sourceRoot.resolve(
                "com/xa/mass/worker/execution/"
                        + "WorkerEventDefinition"
                        + "Manager.java"
        )));
        assertFalse(dispatcherSource.contains("LongSupplier"));
        assertFalse(dispatcherSource.contains("java.time.Clock"));
        assertFalse(dispatcherSource.contains("DeliveryReport"));
        assertFalse(dispatcherSource.contains("workerId"));
        assertFalse(dispatcherSource.contains(
                "WORKER_CONNECTION_CLOSE_EVENT_CODE"
        ));
        for (Method method : dispatcher.getDeclaredMethods()) {
            String methodName = method.getName();
            assertFalse(methodName.startsWith("register"), methodName);
            assertFalse(methodName.startsWith("unregister"), methodName);
            assertFalse(methodName.startsWith("reload"), methodName);
            assertFalse(methodName.startsWith("refresh"), methodName);
        }
    }

    @Test
    void preparationAndOneRoundRuntimeHaveNarrowOwnership()
            throws Exception {
        assertTrue(hasMethod(WorkerPreparation.class, "prepare"));
        assertTrue(hasMethod(WorkerPreparation.class, "close"));
        assertTrue(hasMethod(PreparedWorker.class, "workerId"));
        assertTrue(hasMethod(PreparedWorker.class, "endpointUri"));

        Path sourceRoot = Path.of("").toAbsolutePath()
                .resolve("src/main/java");
        Path transportFile = sourceRoot.resolve(
                "com/xa/mass/worker/runtime/TextMessageWorkerTransport.java"
        );
        String transport = Files.readString(transportFile);
        assertTrue(transport.contains(
                "WORKER_CONNECTION_CLOSE_EVENT_CODE"
        ));
        assertFalse(transport.contains("UUID.fromString("));
        assertFalse(transport.contains("java.util.concurrent"));

        String preparation = Files.readString(sourceRoot.resolve(
                "com/xa/mass/worker/runtime/"
                        + "WorkerControlPreparation.java"
        ));
        String preparedWorker = Files.readString(sourceRoot.resolve(
                "com/xa/mass/worker/runtime/PreparedWorker.java"
        ));
        String polling = Files.readString(sourceRoot.resolve(
                "com/xa/mass/worker/transport/polling/"
                        + "PollingWorkerTransport.java"
        ));
        assertFalse(preparation.contains("UUID.fromString("));
        assertFalse(preparedWorker.contains("UUID.fromString("));
        assertFalse(polling.contains("UUID.fromString("));
        for (String forbidden : new String[]{
                "WorkerIdentityStore",
                "WorkerPropertiesProvider",
                "WorkerControlClient",
                "WorkerEventDefinition",
                "WorkerCommandDispatcher",
                "ExecutorService",
                "ThreadPoolExecutor",
                "ScheduledExecutor",
                "SynchronousQueue",
                "BlockingQueue",
                "Future",
                "WorkerRetryPolicy",
                "FutureTask",
                "commandThread",
                "inFlightMessageIds",
                "connectionGeneration",
                "isConnected",
                "isExiting",
                "onStateChanged",
                "Executors.new",
                "shutdown"
        }) {
            assertFalse(transport.contains(forbidden), forbidden);
        }
        assertFalse(Files.exists(sourceRoot.resolve(
                "com/xa/mass/worker/runtime/TextMessageWorkerRuntime.java"
        )));
        assertFalse(Files.exists(sourceRoot.resolve(
                "com/xa/mass/worker/transport/connection/"
                        + "TextMessageWorker" + "Transport.java"
        )));
        assertFalse(Files.exists(sourceRoot.resolve(
                "com/xa/mass/worker/runtime/WorkerResultSlot.java"
        )));
        assertFalse(Files.exists(sourceRoot.resolve(
                "com/xa/mass/worker/internal/WorkerIds.java"
        )));
        assertFalse(Files.exists(sourceRoot.resolve(
                "com/xa/mass/worker/execution/"
                        + "AsyncWorkerCommandExecutor.java"
        )));

        Path controllerFile = sourceRoot.resolve(
                "com/xa/mass/worker/runtime/WorkerRunController.java"
        );
        String controller = Files.readString(controllerFile);
        for (String forbidden : new String[]{
                "WorkerEventDefinition",
                "WorkerCommandDispatcher",
                "DeliveryCommand",
                "commandInFlight",
                "prepareAfterCommand",
                "pendingResult",
                "generation",
                "runVersion",
                "channelId",
                "PAUSED",
                "pause(",
                "resume(",
                "supervisor",
                "isConnected",
                "ConnectionState",
                "Executors.new",
                "new Thread(",
                "WorkerRetryPolicy",
                "ScheduledFuture",
                "FutureTask",
                "PreparationRetry",
                "maxPrepareAttempts",
                "retryScheduler",
                "WorkerExecutionResources",
                "private State state",
                "private boolean closed",
                "private boolean startTaskActive"
        }) {
            assertFalse(controller.contains(forbidden), forbidden);
        }
        assertFalse(Files.exists(sourceRoot.resolve(
                "com/xa/mass/worker/runtime/WorkerLoop.java"
        )));
        assertFalse(Files.exists(sourceRoot.resolve(
                "com/xa/mass/worker/runtime/WorkerRetryPolicy.java"
        )));
        assertFalse(Files.exists(sourceRoot.resolve(
                "com/xa/mass/worker/runtime/"
                        + "WorkerPropertiesSnapshot.java"
        )));
    }

    private static boolean hasMethod(
            Class<?> type,
            String name,
            Class<?>... parameters
    ) {
        return Arrays.stream(type.getMethods())
                .anyMatch(method -> method.getName().equals(name)
                        && Arrays.equals(
                        method.getParameterTypes(),
                        parameters
                ));
    }

    private static void assertConstructor(
            Class<?> type,
            Class<?>... parameters
    ) throws NoSuchMethodException {
        type.getConstructor(parameters);
    }

    private static String readTree(Path root) throws IOException {
        StringBuilder source = new StringBuilder();
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile)
                    .forEach(path -> append(source, path));
        }
        return source.toString();
    }

    private static void append(StringBuilder target, Path path) {
        try {
            target.append(Files.readString(path));
        } catch (IOException error) {
            throw new IllegalStateException(
                    "Unable to read " + path,
                    error
            );
        }
    }
}
