package com.xa.mass.admin;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminCliMainTest {
    @Test
    void authConfigPrintsFullReadinessFields() throws Exception {
        try (AdminStubServer server = new AdminStubServer()) {
            JsonNode output = runJson("auth", "config", "--base-url", server.baseUrl());

            assertEquals("session", output.path("authMode").asText());
            assertFalse(output.path("operatorHeaderSupported").asBoolean(true));
            assertTrue(output.path("sessionCookieSupported").asBoolean(false));
            assertEquals("X-Mass-Csrf-Token", output.path("csrfHeaderName").asText());
        }
    }

    private JsonNode runJson(String... args) throws Exception {
        PrintStream previous = System.out;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PrintStream capture = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            AdminCliMain.main(args);
        } finally {
            System.setOut(previous);
        }
        return AdminEnvConfig.objectMapper().readTree(output.toString(StandardCharsets.UTF_8));
    }
}
