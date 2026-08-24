package com.xa.mass.integration.workercapability.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.xa.mass.integration.workercapability.acceptance.CapabilityTaskEvidence.TaskSummary;
import com.xa.mass.integration.workercapability.acceptance.WorkerCapabilityAcceptance.ExpectedItem;
import com.xa.mass.integration.workercapability.acceptance.WorkerCapabilityAcceptance.ObservedResult;
import com.xa.mass.workerdelivery.json.Jsons;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CapabilityTaskEvidenceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void failedEvidenceKeepsOnlySafeCoordinates() throws Exception {
        Path evidenceFile = temporaryDirectory.resolve("evidence.json");
        Map<String, ExpectedItem> manifest = new LinkedHashMap<>();
        manifest.put(
                "message-1",
                new ExpectedItem(
                        "scenario-phone-number-workers",
                        "extension.worker.phonenumber.e164"
                )
        );

        CapabilityTaskEvidence.writeFailed(
                evidenceFile,
                "proof-1",
                List.of(new TaskSummary(
                        "task-1",
                        "scenario-phone-number-workers",
                        1,
                        1
                )),
                manifest,
                List.of(new ObservedResult(
                        "message-1",
                        "private-result-value"
                )),
                new IllegalStateException("private failure detail")
        );

        String encoded = Files.readString(
                evidenceFile,
                StandardCharsets.UTF_8
        );
        Map<String, Object> evidence = Jsons.parseObject(encoded);
        assertEquals("failed", evidence.get("status"));
        assertEquals(1, ((Number) evidence.get("taskCount")).intValue());
        assertEquals(1, ((Number) evidence.get("itemCount")).intValue());
        assertEquals(1, ((Number) evidence.get("resultCount")).intValue());
        assertEquals(List.of("message-1"), evidence.get("messageIds"));
        assertEquals(
                List.of("IllegalStateException"),
                evidence.get("failures")
        );
        assertFalse(encoded.contains("private-result-value"));
        assertFalse(encoded.contains("private failure detail"));
    }
}
