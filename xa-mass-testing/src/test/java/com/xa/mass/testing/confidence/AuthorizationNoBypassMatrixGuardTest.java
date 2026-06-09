package com.xa.mass.testing.confidence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthorizationNoBypassMatrixGuardTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Path MATRIX =
            Path.of("..").resolve("xa-mass-testing/proof/authorization-no-bypass-matrix.json");

    @Test
    void matrixStaysRepresentativeAndOwned() throws IOException {
        Map<String, Object> matrix = OBJECT_MAPPER.readValue(MATRIX.toFile(), new TypeReference<>() {
        });
        assertEquals(1, matrix.get("schemaVersion"));
        assertEquals("authorization-no-bypass-safety", matrix.get("proofLine"));
        assertTrue(String.valueOf(matrix.get("scope")).contains("not a full route-permission matrix"));

        List<?> rows = list(matrix.get("rows"));
        assertFalse(rows.isEmpty(), "no-bypass matrix must name representative rows");
        Set<String> ids = rows.stream()
                .map(AuthorizationNoBypassMatrixGuardTest::map)
                .map(row -> String.valueOf(row.get("id")))
                .collect(Collectors.toSet());
        assertTrue(ids.containsAll(Set.of(
                "unauthenticatedOperatorRoute",
                "invalidTaskApiKey",
                "invalidWorkerApiKey",
                "taskApiKeyOnOperatorCommandRoute",
                "workerApiKeyOnTaskProducerRoute",
                "wrongProjectOrEventScope",
                "workerResultSubmitImpersonation",
                "missingCsrfOnSessionMutation",
                "fixtureHeaderRejectedInConfidence")));

        for (Object value : rows) {
            Map<?, ?> row = map(value);
            assertNotNull(row.get("id"), "row must name id");
            assertEquals("authorization-no-bypass-safety", matrix.get("proofLine"));
            assertNotNull(row.get("ownerLane"), row.get("id") + " must name owner lane");
            assertNotNull(row.get("operation"), row.get("id") + " must name operation");
            assertNotNull(row.get("credentialFamily"), row.get("id") + " must name credential family");
            assertNotNull(row.get("routeFamily"), row.get("id") + " must name route family");
            assertNotNull(row.get("claimScope"), row.get("id") + " must name claim scope");
        }

        Map<?, ?> invalidTaskKey = row(rows, "invalidTaskApiKey");
        assertEquals("platform-confidence", invalidTaskKey.get("ownerLane"));
        assertEquals("implemented", invalidTaskKey.get("status"));
        assertEquals(401, invalidTaskKey.get("expectedHttpStatus"));
        assertEquals("Invalid or missing API-key credential", invalidTaskKey.get("expectedReason"));

        Map<?, ?> taskKeyOnOperatorCommand = row(rows, "taskApiKeyOnOperatorCommandRoute");
        assertEquals("api-contract-health", taskKeyOnOperatorCommand.get("ownerLane"));
        assertEquals("linked", taskKeyOnOperatorCommand.get("status"));
    }

    private static Map<?, ?> row(List<?> rows, String id) {
        return rows.stream()
                .map(AuthorizationNoBypassMatrixGuardTest::map)
                .filter(row -> id.equals(row.get("id")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing no-bypass matrix row " + id));
    }

    @SuppressWarnings("unchecked")
    private static Map<?, ?> map(Object value) {
        assertTrue(value instanceof Map<?, ?>, "expected JSON object but got " + value);
        return (Map<?, ?>) value;
    }

    private static List<?> list(Object value) {
        assertTrue(value instanceof List<?>, "expected JSON array but got " + value);
        return (List<?>) value;
    }
}
