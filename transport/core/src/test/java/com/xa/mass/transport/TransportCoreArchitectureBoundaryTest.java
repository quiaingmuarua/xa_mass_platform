package com.xa.mass.transport;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xa.mass.transport.client.LineSocketClient;
import com.xa.mass.transport.client.TextWebSocketClient;
import com.xa.mass.transport.client.WorkerPointClient;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class TransportCoreArchitectureBoundaryTest {

    @Test
    void coreIsJavaElevenAndHasNoPlatformImplementationDependency()
            throws IOException {
        Path project = Path.of("").toAbsolutePath();
        String source = readTree(project.resolve("src/main/java"));
        String build = Files.readString(project.resolve("build.gradle"));

        assertTrue(build.contains("id 'java-library'"));
        assertTrue(build.contains(
                "archivesName.set('xa-mass-transport-core')"
        ));
        assertTrue(build.contains(
                "api project(':foundation_jvm')"
        ));
        assertTrue(build.contains(
                "api project(':worker_delivery_contract_jvm')"
        ));
        assertTrue(build.contains("options.release = 11"));

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
    void clientContractsOnlyExposeStringsAndLifecycleSignals() {
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
