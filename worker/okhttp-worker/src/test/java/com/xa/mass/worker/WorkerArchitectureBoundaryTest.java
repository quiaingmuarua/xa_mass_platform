package com.xa.mass.worker;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xa.mass.worker.error.WorkerErrorCode;
import com.xa.mass.worker.error.WorkerException;
import com.xa.mass.worker.execution.WorkerCommandProcessor;
import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.worker.execution.WorkerEventDefinitionManager;
import com.xa.mass.worker.execution.WorkerEventHandler;
import com.xa.mass.worker.execution.WorkerEventParameterResolver;
import com.xa.mass.worker.transport.polling.PollingWorkerTransport;
import com.xa.mass.worker.transport.socket.SocketWorkerTransport;
import com.xa.mass.worker.transport.websocket.WebSocketWorkerTransport;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class WorkerArchitectureBoundaryTest {

    @Test
    void okHttpWorkerIsAJavaElevenLibraryWithNarrowDependencies()
            throws IOException {
        Path project = Path.of("").toAbsolutePath();
        String source = readTree(project.resolve("src/main/java"));
        String build = Files.readString(project.resolve("build.gradle"));

        assertTrue(build.contains("id 'java-library'"));
        assertTrue(build.contains(
                "archivesName.set('xa-mass-okhttp-worker')"
        ));
        assertTrue(build.contains(
                "api project(':worker_delivery_contract_jvm')"
        ));
        assertTrue(build.contains(
                "api project(':foundation_jvm')"
        ));
        assertTrue(build.contains(
                "implementation 'com.squareup.okhttp3:okhttp:5.3.0'"
        ));
        assertTrue(build.contains("options.release = 11"));
        for (String forbiddenDependency : new String[]{
                "id 'application'",
                "mainClass",
                "project(':worker:common')",
                "project(':worker:android')",
                "project(':server_jvm')",
                "project(':kernel_jvm')",
                "com.android",
                "androidx",
                "desugar",
                "libphonenumber",
                "spring",
                "redis",
                "lettuce"
        }) {
            assertFalse(
                    build.toLowerCase().contains(
                            forbiddenDependency.toLowerCase()
                    ),
                    forbiddenDependency
            );
        }
        for (String forbidden : new String[]{
                "com.google.gson",
                "org.json",
                "android.",
                "androidx.",
                "javax.naming",
                "java.net.http",
                "server_jvm",
                "kernel_jvm",
                "kernel_design",
                "springframework",
                "io.lettuce",
                "Redis",
                "TaskType",
                "WorkerMain",
                "WorkerConfiguration",
                "WorkerTransportMode",
                "PhoneInspectHandler",
                "StringTransformHandler",
                "DomainInspectHandler"
        }) {
            assertFalse(source.contains(forbidden), forbidden);
        }
        assertFalse(source.contains("java.util.logging"));
        assertFalse(source.contains("LogUtils"));
        assertTrue(source.contains("class WorkerException"));
        assertEquals(
                1,
                occurrences(source, "extends CodedRuntimeException")
        );
    }

    @Test
    void publicWorkerApiDoesNotExposeOkHttp() {
        Class<?>[] publicTypes = {
                WorkerCommandProcessor.class,
                WorkerEventDefinition.class,
                WorkerEventDefinitionManager.class,
                WorkerEventHandler.class,
                WorkerEventParameterResolver.class,
                WorkerException.class,
                WorkerErrorCode.class,
                PollingWorkerTransport.class,
                WebSocketWorkerTransport.class,
                SocketWorkerTransport.class
        };
        for (Class<?> type : publicTypes) {
            for (Constructor<?> constructor : type.getConstructors()) {
                assertFalse(
                        constructor.toGenericString().contains("okhttp3"),
                        constructor.toGenericString()
                );
            }
            for (Method method : type.getMethods()) {
                if (method.getDeclaringClass() == Object.class) {
                    continue;
                }
                assertFalse(
                        method.toGenericString().contains("okhttp3"),
                        method.toGenericString()
                );
            }
        }
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

    private static int occurrences(String value, String token) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(token, offset)) >= 0) {
            count++;
            offset += token.length();
        }
        return count;
    }
}
