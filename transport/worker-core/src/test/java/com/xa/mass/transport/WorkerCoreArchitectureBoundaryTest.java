package com.xa.mass.transport;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xa.mass.transport.client.LineSocketClient;
import com.xa.mass.transport.client.TextWebSocketClient;
import com.xa.mass.transport.client.WorkerControlClient;
import com.xa.mass.transport.client.WorkerPointClient;
import com.xa.mass.transport.client.WorkerTransportType;
import com.xa.mass.worker.execution.WorkerCommandExecutor;
import com.xa.mass.worker.transport.polling.PollingWorkerTransport;
import com.xa.mass.worker.transport.socket.SocketWorkerTransport;
import com.xa.mass.worker.transport.websocket.WebSocketWorkerTransport;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

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
                TextWebSocketClient.class,
                TextWebSocketClient.Listener.class,
                LineSocketClient.class,
                LineSocketClient.Listener.class
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
                TextWebSocketClient.class,
                "send",
                String.class
        ));
        assertTrue(hasMethod(
                TextWebSocketClient.class,
                "closeCurrent",
                int.class,
                String.class
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
                String.class,
                java.time.Duration.class
        ));
        assertTrue(hasMethod(
                WorkerControlClient.class,
                "bind",
                String.class,
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
                WebSocketWorkerTransport.class,
                TextWebSocketClient.class,
                String.class,
                Collection.class
        );
        assertConstructor(
                WebSocketWorkerTransport.class,
                TextWebSocketClient.class,
                String.class,
                WorkerCommandExecutor.class
        );
        assertConstructor(
                SocketWorkerTransport.class,
                LineSocketClient.class,
                String.class,
                Collection.class
        );
        assertConstructor(
                SocketWorkerTransport.class,
                LineSocketClient.class,
                String.class,
                WorkerCommandExecutor.class
        );

        for (Class<?> transport : new Class<?>[]{
                PollingWorkerTransport.class,
                WebSocketWorkerTransport.class,
                SocketWorkerTransport.class
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
