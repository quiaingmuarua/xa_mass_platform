package com.xa.mass.scenariorpc;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.zip.CRC32;

public final class ScenarioRpcScenario {

    private static final String IDENTIFIER_PATTERN = "[A-Za-z0-9._-]+";

    private final ScenarioRpcDescriptor descriptor;
    private final Function<String, Map<String, Object>> parser;
    private final String requiredResultField;

    ScenarioRpcScenario(
            ScenarioRpcDescriptor descriptor,
            Function<String, Map<String, Object>> parser,
            String requiredResultField
    ) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.parser = Objects.requireNonNull(parser, "parser");
        this.requiredResultField = Objects.requireNonNull(
                requiredResultField,
                "requiredResultField"
        );
    }

    public ScenarioRpcDescriptor descriptor() {
        return descriptor;
    }

    public ScenarioRpcRunOutcome run(
            String messageIdPrefix,
            List<String> lines,
            ScenarioRpcPollingPolicy polling,
            ScenarioRpcBatchExchange exchange,
            ScenarioRpcResultSink sink
    ) {
        requireIdentifier(messageIdPrefix, "messageIdPrefix");
        Objects.requireNonNull(lines, "lines");
        Objects.requireNonNull(polling, "polling");
        Objects.requireNonNull(exchange, "exchange");
        Objects.requireNonNull(sink, "sink");

        List<Seed> seeds = parseAll(List.copyOf(lines), messageIdPrefix);
        if (seeds.isEmpty()) {
            return new ScenarioRpcRunOutcome(
                    ScenarioRpcRunStatus.SUCCEEDED,
                    List.of(),
                    0,
                    0
            );
        }
        exchange.append(
                descriptor,
                seeds.stream()
                        .map(seed -> new ScenarioRpcItem(
                                seed.messageId(),
                                seed.payload()
                        ))
                        .toList()
        );

        Map<String, Seed> seedsByMessageId = new LinkedHashMap<>();
        seeds.forEach(seed -> seedsByMessageId.put(seed.messageId(), seed));
        Set<String> pending = new LinkedHashSet<>(seedsByMessageId.keySet());
        Map<String, ScenarioRpcResult> completed = new LinkedHashMap<>();
        int loadRounds = 0;
        while (!pending.isEmpty()
                && loadRounds < polling.maximumLoadRounds()) {
            if (loadRounds > 0) {
                waitForNextLoad(polling.loadIntervalMillis());
            }
            loadRounds++;
            Map<String, Map<String, Object>> loaded = Objects.requireNonNull(
                    exchange.loadResults(
                            descriptor,
                            List.copyOf(pending)
                    ),
                    "loadResults returned null"
            );
            rejectUnexpectedResults(loaded, pending);
            List<ScenarioRpcResult> round = new ArrayList<>();
            for (Seed seed : seeds) {
                if (!pending.contains(seed.messageId())) {
                    continue;
                }
                Map<String, Object> resultPayload = loaded.get(
                        seed.messageId()
                );
                if (resultPayload == null) {
                    continue;
                }
                ScenarioRpcResult result = new ScenarioRpcResult(
                        descriptor.workerGroupId(),
                        seed.messageId(),
                        descriptor.eventCode(),
                        seed.payload(),
                        immutableMap(resultPayload)
                );
                verify(result);
                round.add(result);
            }
            if (!round.isEmpty()) {
                accept(sink, round);
                for (ScenarioRpcResult result : round) {
                    pending.remove(result.messageId());
                    completed.put(result.messageId(), result);
                }
            }
        }

        List<ScenarioRpcResult> ordered = seeds.stream()
                .map(seed -> completed.get(seed.messageId()))
                .filter(Objects::nonNull)
                .toList();
        return new ScenarioRpcRunOutcome(
                pending.isEmpty()
                        ? ScenarioRpcRunStatus.SUCCEEDED
                        : ScenarioRpcRunStatus.PARTIAL,
                ordered,
                pending.size(),
                loadRounds
        );
    }

    private List<Seed> parseAll(
            List<String> lines,
            String messageIdPrefix
    ) {
        List<Seed> seeds = new ArrayList<>(lines.size());
        for (int index = 0; index < lines.size(); index++) {
            int lineNumber = index + 1;
            String line = lines.get(index);
            Map<String, Object> payload;
            try {
                payload = immutableMap(Objects.requireNonNull(
                        parser.apply(line),
                        "line parser returned null"
                ));
            } catch (RuntimeException error) {
                throw new IllegalArgumentException(
                        "scenario "
                                + descriptor.scenarioType()
                                + " could not parse line "
                                + lineNumber,
                        error
                );
            }
            seeds.add(new Seed(
                    messageIdPrefix
                            + "-"
                            + descriptor.eventCode()
                            + "-"
                            + lineCrc32(lineNumber, line),
                    payload
            ));
        }
        return List.copyOf(seeds);
    }

    private void verify(ScenarioRpcResult result) {
        if (!Boolean.TRUE.equals(result.result().get("valid"))) {
            throw invalid(result, "valid=true");
        }
        if (!result.result().containsKey(requiredResultField)) {
            throw invalid(result, requiredResultField);
        }
    }

    private static void rejectUnexpectedResults(
            Map<String, Map<String, Object>> loaded,
            Set<String> pending
    ) {
        for (String messageId : loaded.keySet()) {
            if (!pending.contains(messageId)) {
                throw new IllegalStateException(
                        "loadResults returned an unexpected messageId"
                );
            }
        }
    }

    private static void accept(
            ScenarioRpcResultSink sink,
            List<ScenarioRpcResult> round
    ) {
        try {
            sink.accept(List.copyOf(round));
        } catch (RuntimeException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException(
                    "Scenario RPC result sink failed",
                    error
            );
        }
    }

    private static void waitForNextLoad(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Scenario RPC polling was interrupted",
                    error
            );
        }
    }

    private static String lineCrc32(int lineNumber, String line) {
        CRC32 crc32 = new CRC32();
        crc32.update(
                (lineNumber + "\0" + line).getBytes(StandardCharsets.UTF_8)
        );
        return String.format(Locale.ROOT, "%08x", crc32.getValue());
    }

    private static void requireIdentifier(String value, String name) {
        if (value == null || !value.matches(IDENTIFIER_PATTERN)) {
            throw new IllegalArgumentException(
                    name + " must match " + IDENTIFIER_PATTERN
            );
        }
    }

    private static Map<String, Object> immutableMap(
            Map<String, Object> values
    ) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    private static IllegalStateException invalid(
            ScenarioRpcResult result,
            String expected
    ) {
        return new IllegalStateException(
                "scenario RPC result for "
                        + result.messageId()
                        + " requires "
                        + expected
        );
    }

    private record Seed(
            String messageId,
            Map<String, Object> payload
    ) {
    }
}
