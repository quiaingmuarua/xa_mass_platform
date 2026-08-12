package com.xa.mass.integration.workercapability;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.xa.mass.workerdelivery.json.Jsons;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkerCapabilityScenarioVerifierTest {

    private static final String PHONE_FILE = "phone-number.jsonl";
    private static final String PHONE_GROUP =
            "scenario-phone-number-workers";
    private static final String PHONE_PREFIX =
            "scenario-phone-number-worker-";
    private static final List<String> PHONE_EVENTS = List.of(
            "phonenumber.e164",
            "phonenumber.country",
            "phonenumber.original-carrier"
    );
    private static final String STRING_FILE = "string-utils.jsonl";
    private static final String STRING_GROUP =
            "scenario-string-utils-workers";
    private static final String STRING_PREFIX =
            "scenario-string-utils-worker-";
    private static final List<String> STRING_EVENTS = List.of(
            "string.md5",
            "string.sha1",
            "string.base64.encode"
    );

    @TempDir
    Path resultDirectory;
    private Path labRoot;

    @BeforeEach
    void writeCompleteProof() throws IOException {
        Files.createDirectories(resultDirectory);
        labRoot = resultDirectory.resolve("data/scenario-workers");
        Files.createDirectories(labRoot);
        writeRows(
                PHONE_FILE,
                validRows(PHONE_GROUP, PHONE_PREFIX, PHONE_EVENTS)
        );
        writeRows(
                STRING_FILE,
                validRows(STRING_GROUP, STRING_PREFIX, STRING_EVENTS)
        );
        writeWorkerFiles(
                PHONE_GROUP,
                validRows(PHONE_GROUP, PHONE_PREFIX, PHONE_EVENTS)
        );
        writeWorkerFiles(
                STRING_GROUP,
                validRows(STRING_GROUP, STRING_PREFIX, STRING_EVENTS)
        );
    }

    @Test
    void acceptsCompleteTwentyWorkerSixtyCallProof() {
        assertDoesNotThrow(
                () -> WorkerCapabilityScenarioVerifier.verify(
                        resultDirectory,
                        labRoot
                )
        );
    }

    @Test
    void rejectsMissingResult() throws IOException {
        List<Map<String, Object>> rows = readRows(PHONE_FILE);
        rows.remove(rows.size() - 1);
        writeRows(PHONE_FILE, rows);

        assertInvalidProof();
    }

    @Test
    void rejectsUnexpectedWorkerGroup() throws IOException {
        mutateRow(
                PHONE_FILE,
                0,
                row -> row.put("workerGroupId", "wrong-group")
        );

        assertInvalidProof();
    }

    @Test
    void rejectsDuplicateWorkerId() throws IOException {
        List<Map<String, Object>> rows = readRows(PHONE_FILE);
        String firstWorkerId = (String) rows.get(0).get("workerId");
        rows.stream()
                .filter(row -> (PHONE_PREFIX + "002").equals(
                        row.get("clientWorkerKey")
                ))
                .forEach(row -> row.put("workerId", firstWorkerId));
        writeRows(PHONE_FILE, rows);

        assertInvalidProof();
    }

    @Test
    void rejectsWorkerKeyIdentityDrift() throws IOException {
        mutateRow(
                PHONE_FILE,
                10,
                row -> row.put("workerId", UUID.randomUUID().toString())
        );

        assertInvalidProof();
    }

    @Test
    void rejectsIncompleteWorkerEventCoverage() throws IOException {
        List<Map<String, Object>> rows = readRows(STRING_FILE);
        Map<String, Object> workerNine = rows.stream()
                .filter(row -> (STRING_PREFIX + "009").equals(
                        row.get("clientWorkerKey")
                ))
                .findFirst()
                .orElseThrow();
        Map<String, Object> last = rows.get(rows.size() - 1);
        last.put("clientWorkerKey", STRING_PREFIX + "009");
        last.put("workerId", workerNine.get("workerId"));
        writeRows(STRING_FILE, rows);

        assertInvalidProof();
    }

    @Test
    void rejectsAResultIdentityThatDiffersFromTheWorkerFile()
            throws IOException {
        Path stateFile = labRoot.resolve(PHONE_GROUP).resolve(
                PHONE_PREFIX + "001.json"
        );
        Map<String, Object> state = new LinkedHashMap<>(
                Jsons.parseObject(Files.readString(
                        stateFile,
                        StandardCharsets.UTF_8
                ))
        );
        state.put("workerId", UUID.randomUUID().toString());
        Files.writeString(
                stateFile,
                Jsons.toJson(state),
                StandardCharsets.UTF_8
        );

        assertInvalidProof();
    }

    private void assertInvalidProof() {
        assertThrows(
                IllegalStateException.class,
                () -> WorkerCapabilityScenarioVerifier.verify(
                        resultDirectory,
                        labRoot
                )
        );
    }

    private void writeWorkerFiles(
            String workerGroupId,
            List<Map<String, Object>> rows
    ) throws IOException {
        Path group = labRoot.resolve(workerGroupId);
        Files.createDirectories(group);
        Map<String, String> workerIds = new LinkedHashMap<>();
        rows.forEach(row -> workerIds.putIfAbsent(
                (String) row.get("clientWorkerKey"),
                (String) row.get("workerId")
        ));
        for (Map.Entry<String, String> worker : workerIds.entrySet()) {
            Files.writeString(
                    group.resolve(worker.getKey() + ".json"),
                    Jsons.toJson(Map.of(
                            "schemaVersion",
                            1,
                            "workerId",
                            worker.getValue()
                    )),
                    StandardCharsets.UTF_8
            );
        }
    }

    private void mutateRow(
            String filename,
            int index,
            Consumer<Map<String, Object>> mutation
    ) throws IOException {
        List<Map<String, Object>> rows = readRows(filename);
        mutation.accept(rows.get(index));
        writeRows(filename, rows);
    }

    private List<Map<String, Object>> readRows(String filename)
            throws IOException {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String line : Files.readAllLines(
                resultDirectory.resolve(filename),
                StandardCharsets.UTF_8
        )) {
            rows.add(new LinkedHashMap<>(Jsons.parseObject(line)));
        }
        return rows;
    }

    private void writeRows(
            String filename,
            List<Map<String, Object>> rows
    ) throws IOException {
        Files.write(
                resultDirectory.resolve(filename),
                rows.stream().map(Jsons::toJson).toList(),
                StandardCharsets.UTF_8
        );
    }

    private static List<Map<String, Object>> validRows(
            String workerGroupId,
            String workerKeyPrefix,
            List<String> eventCodes
    ) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String eventCode : eventCodes) {
            for (int index = 1; index <= 10; index++) {
                String clientWorkerKey = workerKeyPrefix
                        + "%03d".formatted(index);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("taskId", "task-" + workerGroupId);
                row.put("workerGroupId", workerGroupId);
                row.put("clientWorkerKey", clientWorkerKey);
                row.put(
                        "workerId",
                        UUID.nameUUIDFromBytes(
                                clientWorkerKey.getBytes(
                                        StandardCharsets.UTF_8
                                )
                        ).toString()
                );
                row.put("eventCode", eventCode);
                row.put("input", Map.of("value", index));
                row.put("result", Map.of("valid", true));
                rows.add(row);
            }
        }
        return rows;
    }
}
