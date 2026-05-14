package com.xa.mass.trace.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xa.mass.trace.sink.ExecutionEventType;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class TraceValidationService {

    private static final String CANONICAL_SCHEMA = "xa.mass.execution-event.v1";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TraceQueryBackend queryBackend;

    public TraceValidationService(TraceQueryBackend queryBackend) {
        this.queryBackend = queryBackend;
    }

    public TraceValidationReport validate(TraceSource source) throws Exception {
        List<TraceValidationIssue> issues = new ArrayList<>();
        for (Path file : source.files()) {
            validateFile(file, issues);
        }
        long validRows = 0L;
        if (issues.isEmpty()) {
            validRows = queryBackend.countRows(source);
        }
        return new TraceValidationReport(
                new TraceValidationReport.PathSummary(source.inputPath().toString(), source.files().size()),
                issues.isEmpty(),
                validRows,
                List.copyOf(issues)
        );
    }

    private void validateFile(Path file, List<TraceValidationIssue> issues) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(file)) {
            String line;
            int lineNo = 0;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                if (line.isBlank()) {
                    issues.add(new TraceValidationIssue("BLANK_LINE", file.toString(), lineNo, "Blank line is not a valid trace row"));
                    continue;
                }
                JsonNode node;
                try {
                    node = objectMapper.readTree(line);
                } catch (Exception ex) {
                    issues.add(new TraceValidationIssue("INVALID_JSON", file.toString(), lineNo, ex.getMessage()));
                    continue;
                }
                validateRequired(node, file, lineNo, issues);
            }
        }
    }

    private void validateRequired(JsonNode node, Path file, int lineNo, List<TraceValidationIssue> issues) {
        requireText(node, "schema", file, lineNo, issues);
        requireText(node, "eventType", file, lineNo, issues);
        requireText(node, "category", file, lineNo, issues);
        requireText(node, "severity", file, lineNo, issues);
        requireNode(node, "identity", file, lineNo, issues);
        if (!node.hasNonNull("ts") || !node.get("ts").canConvertToLong()) {
            issues.add(new TraceValidationIssue("MISSING_TS", file.toString(), lineNo, "Missing or non-numeric ts"));
        }
        if (!node.hasNonNull("tsIso") || node.get("tsIso").asText().isBlank()) {
            issues.add(new TraceValidationIssue("MISSING_TS_ISO", file.toString(), lineNo, "Missing tsIso"));
        }
        JsonNode schema = node.get("schema");
        if (schema != null && !schema.isNull() && !CANONICAL_SCHEMA.equals(schema.asText())) {
            issues.add(new TraceValidationIssue("SCHEMA_MISMATCH", file.toString(), lineNo,
                    "Expected schema " + CANONICAL_SCHEMA + " but was " + schema.asText()));
        }
        JsonNode eventType = node.get("eventType");
        if (eventType != null && !eventType.isNull()) {
            try {
                ExecutionEventType.valueOf(eventType.asText());
            } catch (IllegalArgumentException ex) {
                issues.add(new TraceValidationIssue("UNKNOWN_EVENT_TYPE", file.toString(), lineNo,
                        "Unknown eventType: " + eventType.asText()));
            }
        }
    }

    private void requireText(JsonNode node, String field, Path file, int lineNo, List<TraceValidationIssue> issues) {
        if (!node.hasNonNull(field) || node.get(field).asText().isBlank()) {
            issues.add(new TraceValidationIssue("MISSING_" + field.toUpperCase(), file.toString(), lineNo,
                    "Missing or blank " + field));
        }
    }

    private void requireNode(JsonNode node, String field, Path file, int lineNo, List<TraceValidationIssue> issues) {
        if (!node.has(field) || node.get(field).isNull()) {
            issues.add(new TraceValidationIssue("MISSING_" + field.toUpperCase(), file.toString(), lineNo,
                    "Missing " + field + " object"));
        }
    }
}
