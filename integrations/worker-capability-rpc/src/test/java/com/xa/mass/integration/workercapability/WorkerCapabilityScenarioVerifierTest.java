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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkerCapabilityScenarioVerifierTest {

    private static final String PHONE_GROUP =
            "scenario-phone-number-workers";
    private static final String STRING_GROUP =
            "scenario-string-utils-workers";
    private static final List<String> PHONE_EVENTS = List.of(
            "phonenumber.e164",
            "phonenumber.country",
            "phonenumber.original-carrier"
    );
    private static final List<String> STRING_EVENTS = List.of(
            "string.md5",
            "string.sha1",
            "string.base64.encode"
    );

    @TempDir
    Path temporaryDirectory;

    @Test
    void acceptsSixtyGroupResultsAndTwentyPersistentIdentities()
            throws Exception {
        Fixture fixture = fixture();

        assertDoesNotThrow(() -> WorkerCapabilityScenarioVerifier.verify(
                fixture.results(),
                fixture.lab()
        ));
    }

    @Test
    void rejectsMissingGroupResults() throws Exception {
        Fixture fixture = fixture();
        List<String> phone = Files.readAllLines(
                fixture.results().resolve("phone-number.jsonl"),
                StandardCharsets.UTF_8
        );
        Files.write(
                fixture.results().resolve("phone-number.jsonl"),
                phone.subList(0, 29),
                StandardCharsets.UTF_8
        );

        assertThrows(
                IllegalStateException.class,
                () -> WorkerCapabilityScenarioVerifier.verify(
                        fixture.results(),
                        fixture.lab()
                )
        );
    }

    @Test
    void rejectsOldTaskAndWorkerCoordinates() throws Exception {
        Fixture fixture = fixture();
        Path file = fixture.results().resolve("phone-number.jsonl");
        List<Map<String, Object>> rows = readRows(file);
        rows.get(0).put("taskId", "old-task");
        rows.get(0).put("workerId", UUID.randomUUID().toString());
        writeRows(file, rows);

        assertThrows(
                IllegalStateException.class,
                () -> WorkerCapabilityScenarioVerifier.verify(
                        fixture.results(),
                        fixture.lab()
                )
        );
    }

    @Test
    void rejectsDuplicateMessageIds() throws Exception {
        Fixture fixture = fixture();
        Path file = fixture.results().resolve("phone-number.jsonl");
        List<Map<String, Object>> rows = readRows(file);
        rows.get(1).put("messageId", rows.get(0).get("messageId"));
        writeRows(file, rows);

        assertThrows(
                IllegalStateException.class,
                () -> WorkerCapabilityScenarioVerifier.verify(
                        fixture.results(),
                        fixture.lab()
                )
        );
    }

    @Test
    void rejectsWorkerIdentityReuseAcrossGroups() throws Exception {
        Fixture fixture = fixture();
        Path phoneFile = firstFile(fixture.lab().resolve(PHONE_GROUP));
        Path stringFile = firstFile(fixture.lab().resolve(STRING_GROUP));
        Map<String, Object> phoneState = Jsons.parseObject(
                Files.readString(phoneFile, StandardCharsets.UTF_8)
        );
        Map<String, Object> stringState = Jsons.parseObject(
                Files.readString(stringFile, StandardCharsets.UTF_8)
        );
        stringState.put("workerId", phoneState.get("workerId"));
        Files.writeString(
                stringFile,
                Jsons.toJson(stringState),
                StandardCharsets.UTF_8
        );

        assertThrows(
                IllegalStateException.class,
                () -> WorkerCapabilityScenarioVerifier.verify(
                        fixture.results(),
                        fixture.lab()
                )
        );
    }

    private Fixture fixture() throws IOException {
        Path results = temporaryDirectory.resolve("results");
        Path lab = temporaryDirectory.resolve("data/scenario-workers");
        Files.createDirectories(results);
        writeRows(
                results.resolve("phone-number.jsonl"),
                rows(PHONE_GROUP, PHONE_EVENTS)
        );
        writeRows(
                results.resolve("string-utils.jsonl"),
                rows(STRING_GROUP, STRING_EVENTS)
        );
        writeLab(lab, PHONE_GROUP);
        writeLab(lab, STRING_GROUP);
        return new Fixture(results, lab);
    }

    private static List<Map<String, Object>> rows(
            String workerGroupId,
            List<String> events
    ) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String event : events) {
            for (int index = 1; index <= 10; index++) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("workerGroupId", workerGroupId);
                row.put("messageId", workerGroupId + "-" + event + "-" + index);
                row.put("eventCode", event);
                row.put("input", Map.of("value", "input-" + index));
                row.put("result", Map.of("valid", true));
                rows.add(row);
            }
        }
        return rows;
    }

    private static void writeLab(Path lab, String workerGroupId)
            throws IOException {
        Path group = lab.resolve(workerGroupId);
        Files.createDirectories(group);
        for (int index = 1; index <= 10; index++) {
            Files.writeString(
                    group.resolve("worker-%03d.json".formatted(index)),
                    Jsons.toJson(Map.of(
                            "schemaVersion", 1,
                            "workerId", UUID.nameUUIDFromBytes(
                                    (workerGroupId + ":" + index).getBytes(
                                            StandardCharsets.UTF_8
                                    )
                            ).toString()
                    )),
                    StandardCharsets.UTF_8
            );
        }
    }

    private static List<Map<String, Object>> readRows(Path path)
            throws IOException {
        return Files.readAllLines(path, StandardCharsets.UTF_8)
                .stream()
                .<Map<String, Object>>map(line ->
                        new LinkedHashMap<>(Jsons.parseObject(line))
                )
                .toList();
    }

    private static Path firstFile(Path directory) throws IOException {
        try (var files = Files.list(directory)) {
            return files.findFirst().orElseThrow();
        }
    }

    private static void writeRows(
            Path path,
            List<Map<String, Object>> rows
    ) throws IOException {
        Files.write(
                path,
                rows.stream().map(Jsons::toJson).toList(),
                StandardCharsets.UTF_8
        );
    }

    private record Fixture(Path results, Path lab) {
    }
}
