package com.xa.mass.admin;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminCliArchitectureGuardTest {
    @Test
    void adminCliDoesNotDependOnJavaSdkOrServerKernelModules() throws Exception {
        Path pom = Path.of("tools/xa-mass-admin-cli/pom.xml");
        if (!Files.exists(pom)) {
            pom = Path.of("pom.xml");
        }
        String text = Files.readString(pom, StandardCharsets.UTF_8);

        assertFalse(text.contains("<artifactId>xa-mass-java-sdk</artifactId>"));
        assertFalse(text.contains("<artifactId>xa-mass-server</artifactId>"));
        assertFalse(text.contains("<artifactId>xa-mass-engine</artifactId>"));
        assertTrue(text.contains("<artifactId>jackson-databind</artifactId>"));
    }

    @Test
    void sharedHttpClientCoreIsNotIntroducedPrematurely() {
        Path rootRelative = Path.of("sdk/xa-mass-http-client-core");
        Path moduleRelative = Path.of("../..").resolve(rootRelative).normalize();

        assertFalse(Files.exists(rootRelative) || Files.exists(moduleRelative),
                "do not add sdk/xa-mass-http-client-core without a recorded owner decision");
    }
}
