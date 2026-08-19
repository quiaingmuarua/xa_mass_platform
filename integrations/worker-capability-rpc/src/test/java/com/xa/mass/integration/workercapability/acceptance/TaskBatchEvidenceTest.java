package com.xa.mass.integration.workercapability.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.xa.mass.workerdelivery.json.Jsons;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TaskBatchEvidenceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void failedEvidenceKeepsOnlySafeMissingAndDuplicateRelationships()
            throws Exception {
        Map<String, Object> row = Map.of(
                "workerGroupId", "scenario-phone-number-workers",
                "eventCode", "extension.worker.phonenumber.e164",
                "messageId", "message-1",
                "input", Map.of("private", "input-value"),
                "result", Map.of("private", "result-value")
        );
        Path evidenceFile = temporaryDirectory.resolve("evidence.json");

        TaskBatchEvidence.writeFailed(
                evidenceFile,
                "proof-1",
                List.of(),
                List.of(row, row),
                new IllegalStateException("private failure detail")
        );

        String encoded = Files.readString(
                evidenceFile,
                StandardCharsets.UTF_8
        );
        Map<String, Object> evidence = Jsons.parseObject(encoded);
        assertEquals("failed", evidence.get("status"));
        assertEquals(
                List.of("message-1"),
                evidence.get("duplicateMessageIds")
        );
        assertEquals(
                List.of("message-1"),
                evidence.get("messageIds")
        );
        assertFalse(encoded.contains("input-value"));
        assertFalse(encoded.contains("result-value"));
        assertFalse(encoded.contains("private failure detail"));
    }
}
