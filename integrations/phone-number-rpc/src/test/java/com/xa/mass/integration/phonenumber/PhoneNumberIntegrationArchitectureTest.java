package com.xa.mass.integration.phonenumber;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PhoneNumberIntegrationArchitectureTest {

    @Test
    void moduleOwnsOnlyTheExternalTaskScenario()
            throws Exception {
        String build = Files.readString(Path.of("build.gradle"));

        assertTrue(build.contains(
                "project(':worker_delivery_contract_jvm')"
        ));
        for (String forbidden : new String[]{
                "project(':server_jvm')",
                "project(':kernel_jvm')",
                "project(':transport:netty-adapter')",
                "project(':transport:okhttp-worker')",
                "implementation 'com.googlecode.libphonenumber",
                "springframework",
                "io.lettuce"
        }) {
            assertFalse(build.contains(forbidden), forbidden);
        }
    }

    @Test
    void taskInvocationDoesNotHostWorkerAssembly()
            throws Exception {
        Path root = Path.of(
                "src/main/java/com/xa/mass/integration/phonenumber"
        );
        String taskMain = Files.readString(
                root.resolve("PhoneNumberTaskMain.java")
        );

        assertTrue(taskMain.contains("PhoneNumberTaskClient"));
        assertFalse(taskMain.contains("WebSocketWorkerTransport"));
        assertFalse(taskMain.contains(
                "PhoneNumberWorkerRegistrationClient"
        ));
        assertFalse(Files.exists(
                root.resolve("PhoneNumberWorkerMain.java")
        ));
        assertFalse(Files.exists(
                root.resolve("PhoneNumberWorkerApplication.java")
        ));
    }
}
