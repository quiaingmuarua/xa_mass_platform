package com.xa.mass.transport;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xa.mass.transport.client.TextMessageClient;
import com.xa.mass.transport.client.WorkerControlClient;
import com.xa.mass.transport.client.WorkerPointClient;
import com.xa.mass.transport.client.WorkerTransportType;
import com.xa.mass.worker.execution.WorkerCommandExecutor;
import com.xa.mass.worker.runtime.PreparedWorker;
import com.xa.mass.worker.runtime.WorkerLifecycle;
import com.xa.mass.worker.runtime.WorkerLoop;
import com.xa.mass.worker.runtime.WorkerPreparation;
import com.xa.mass.worker.runtime.WorkerRetryPolicy;
import com.xa.mass.worker.transport.polling.PollingWorkerTransport;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommand;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
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
                "api project(':worker_delivery_contract_jvm')"
        ));
        assertTrue(build.contains("options.release = 11"));
        assertFalse(build.contains("foundation_jvm"));
        assertFalse(source.contains("com.xa.mass.foundation"));
        assertFalse(source.contains("ObservedText" + "MessageClient"));

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
    void networkClientContractsDoNotExposeProtocolOrPlatformTypes() {
        for (Class<?> type : new Class<?>[]{
                WorkerPointClient.class,
                TextMessageClient.class,
                TextMessageClient.Listener.class
        }) {
            for (Method method : type.getDeclaredMethods()) {
                String signature = method.toGenericString();
                for (String forbidden : new String[]{
                        "WorkerCommand",
                        "WorkerResult",
                        "WorkerConnectionBind",
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
        assertTrue(hasMethod(
                TextMessageClient.Listener.class,
                "onReconnectExhausted"
        ));
    }

    @Test
    void controlClientIsAPlatformNeutralRegisterAndBindContract() {
        for (Method method : WorkerControlClient.class
                .getDeclaredMethods()) {
            String signature = method.toGenericString();
            for (String forbidden : new String[]{
                    "okhttp3",
                    "android.",
                    "SharedPreferences",
                    "WorkerConnectionBind"
            }) {
                assertFalse(signature.contains(forbidden), signature);
            }
        }

        assertTrue(hasMethod(
                WorkerControlClient.class,
                "register",
                String.class,
                java.util.Map.class,
                java.time.Duration.class
        ));
        assertTrue(hasMethod(
                WorkerControlClient.class,
                "bind",
                String.class,
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
                WorkerLoop.class
        ));
        assertTrue(hasMethod(WorkerLifecycle.class, "start"));
        assertTrue(hasMethod(WorkerLifecycle.class, "stop"));
        assertTrue(hasMethod(
                WorkerLifecycle.class,
                "send",
                WorkerCommand.class
        ));
        assertFalse(hasMethod(WorkerLifecycle.class, "refreshProperties"));
        assertTrue(hasMethod(WorkerLifecycle.class, "snapshot"));
        assertTrue(hasMethod(WorkerLifecycle.class, "isConnected"));
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
        assertConstructor(
                PollingWorkerTransport.class,
                WorkerPointClient.class,
                String.class,
                Collection.class
        );
        assertConstructor(
                PollingWorkerTransport.class,
                WorkerPointClient.class,
                String.class,
                WorkerCommandExecutor.class
        );
        assertConstructor(
                WorkerLoop.class,
                WorkerPreparation.class,
                Collection.class,
                WorkerLoop.NetworkClientFactory.class,
                WorkerRetryPolicy.class
        );

        for (Class<?> transport : new Class<?>[]{
                PollingWorkerTransport.class,
                WorkerLoop.class
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
    void preparationAndOneRoundRuntimeHaveNarrowOwnership()
            throws Exception {
        assertTrue(hasMethod(WorkerPreparation.class, "prepare"));
        assertTrue(hasMethod(WorkerPreparation.class, "close"));
        assertTrue(hasMethod(PreparedWorker.class, "workerId"));
        assertTrue(hasMethod(PreparedWorker.class, "endpointUri"));

        Path sourceRoot = Path.of("").toAbsolutePath()
                .resolve("src/main/java");
        Path runtimeFile = sourceRoot.resolve(
                "com/xa/mass/worker/runtime/TextMessageWorkerRuntime.java"
        );
        String runtime = Files.readString(runtimeFile);
        for (String forbidden : new String[]{
                "WorkerIdentityStore",
                "WorkerPropertiesProvider",
                "WorkerControlClient",
                "WorkerEventDefinition",
                "WorkerCommandDispatcher",
                "WorkerRetryPolicy"
        }) {
            assertFalse(runtime.contains(forbidden), forbidden);
        }
        assertFalse(Files.exists(sourceRoot.resolve(
                "com/xa/mass/worker/transport/connection/"
                        + "TextMessageWorker" + "Transport.java"
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
