package com.xa.mass.scenariorpc;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.zip.CRC32;

public final class ScenarioRpcEngine {

    private static final int MAX_CONCURRENCY = 100;
    private static final String IDENTIFIER_PATTERN = "[A-Za-z0-9._-]+";
    private static final String PHONE_GROUP =
            "scenario-phone-number-workers";
    private static final String STRING_GROUP =
            "scenario-string-utils-workers";

    private final List<BuiltInScenario> scenarios;
    private final Map<String, BuiltInScenario> scenariosById;

    private ScenarioRpcEngine() {
        scenarios = List.of(
                phone("phonenumber.e164", "e164"),
                phone("phonenumber.country", "countryCallingCode"),
                phone(
                        "phonenumber.original-carrier",
                        "originalCarrier"
                ),
                string("string.md5", "md5"),
                string("string.sha1", "sha1"),
                string("string.base64.encode", "base64")
        );
        Map<String, BuiltInScenario> indexed = new LinkedHashMap<>();
        for (BuiltInScenario scenario : scenarios) {
            indexed.put(scenario.descriptor().scenarioId(), scenario);
        }
        scenariosById = Collections.unmodifiableMap(indexed);
    }

    public static ScenarioRpcEngine create() {
        return new ScenarioRpcEngine();
    }

    public List<ScenarioRpcDescriptor> scenarios() {
        return scenarios.stream()
                .map(BuiltInScenario::descriptor)
                .toList();
    }

    public List<ScenarioRpcResult> run(
            String scenarioId,
            String messageIdPrefix,
            List<String> lines,
            int concurrency,
            ScenarioRpcCall rpcCall
    ) {
        BuiltInScenario scenario = requireScenario(scenarioId);
        requireIdentifier(messageIdPrefix, "messageIdPrefix");
        Objects.requireNonNull(lines, "lines");
        Objects.requireNonNull(rpcCall, "rpcCall");
        if (concurrency < 1 || concurrency > MAX_CONCURRENCY) {
            throw new IllegalArgumentException(
                    "concurrency must be between 1 and " + MAX_CONCURRENCY
            );
        }

        List<Seed> seeds = parseAll(
                scenario,
                List.copyOf(lines),
                messageIdPrefix
        );
        List<ScenarioRpcResult> results = invoke(
                scenario,
                seeds,
                concurrency,
                rpcCall
        );
        verifyAll(scenario, results);
        return results;
    }

    private BuiltInScenario requireScenario(String scenarioId) {
        BuiltInScenario scenario = scenariosById.get(scenarioId);
        if (scenario == null) {
            throw new IllegalArgumentException(
                    "unknown scenarioId: " + scenarioId
            );
        }
        return scenario;
    }

    private static List<Seed> parseAll(
            BuiltInScenario scenario,
            List<String> lines,
            String messageIdPrefix
    ) {
        List<Seed> seeds = new ArrayList<>(lines.size());
        for (int index = 0; index < lines.size(); index++) {
            int lineNumber = index + 1;
            String line = lines.get(index);
            Map<String, Object> payload;
            try {
                payload = scenario.parser().apply(line);
                payload = immutableMap(Objects.requireNonNull(
                        payload,
                        "line parser returned null"
                ));
            } catch (RuntimeException error) {
                throw new IllegalArgumentException(
                        "scenario "
                                + scenario.descriptor().scenarioId()
                                + " could not parse line "
                                + lineNumber,
                        error
                );
            }
            seeds.add(new Seed(
                    index,
                    messageIdPrefix
                            + "-"
                            + scenario.descriptor().eventCode()
                            + "-"
                            + lineCrc32(lineNumber, line),
                    payload
            ));
        }
        return List.copyOf(seeds);
    }

    private static List<ScenarioRpcResult> invoke(
            BuiltInScenario scenario,
            List<Seed> seeds,
            int concurrency,
            ScenarioRpcCall rpcCall
    ) {
        if (seeds.isEmpty()) {
            return List.of();
        }
        ExecutorService executor = Executors.newFixedThreadPool(
                concurrency,
                Thread.ofVirtual()
                        .name("scenario-rpc-", 0)
                        .factory()
        );
        CompletionService<IndexedResult> completions =
                new ExecutorCompletionService<>(executor);
        List<Future<IndexedResult>> submitted = new ArrayList<>();
        List<ScenarioRpcResult> ordered = new ArrayList<>(
                Collections.nCopies(seeds.size(), null)
        );
        int nextSeed = 0;
        int completed = 0;
        try {
            while (nextSeed < seeds.size()
                    && submitted.size() < concurrency) {
                submitted.add(submit(
                        completions,
                        scenario,
                        seeds.get(nextSeed++),
                        rpcCall
                ));
            }
            while (completed < seeds.size()) {
                IndexedResult indexed = completions.take().get();
                ordered.set(indexed.sequence(), indexed.result());
                completed++;
                if (nextSeed < seeds.size()) {
                    submitted.add(submit(
                            completions,
                            scenario,
                            seeds.get(nextSeed++),
                            rpcCall
                    ));
                }
            }
            return List.copyOf(ordered);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            cancel(submitted);
            throw new IllegalStateException(
                    "scenario RPC was interrupted",
                    error
            );
        } catch (ExecutionException error) {
            cancel(submitted);
            throw callFailure(error.getCause());
        } finally {
            executor.shutdownNow();
        }
    }

    private static Future<IndexedResult> submit(
            CompletionService<IndexedResult> completions,
            BuiltInScenario scenario,
            Seed seed,
            ScenarioRpcCall rpcCall
    ) {
        ScenarioRpcDescriptor descriptor = scenario.descriptor();
        return completions.submit(() -> {
            Map<String, Object> result = immutableMap(
                    Objects.requireNonNull(
                            rpcCall.call(
                                    descriptor.workerGroupId(),
                                    seed.messageId(),
                                    descriptor.eventCode(),
                                    seed.payload()
                            ),
                            "RPC call returned null"
                    )
            );
            return new IndexedResult(
                    seed.sequence(),
                    new ScenarioRpcResult(
                            descriptor.workerGroupId(),
                            seed.messageId(),
                            descriptor.eventCode(),
                            seed.payload(),
                            result
                    )
            );
        });
    }

    private static void verifyAll(
            BuiltInScenario scenario,
            List<ScenarioRpcResult> results
    ) {
        for (ScenarioRpcResult result : results) {
            if (!Boolean.TRUE.equals(result.result().get("valid"))) {
                throw invalid(result, "valid=true");
            }
            if (!result.result().containsKey(scenario.requiredResultField())) {
                throw invalid(result, scenario.requiredResultField());
            }
        }
    }

    private static BuiltInScenario phone(
            String eventCode,
            String requiredField
    ) {
        return new BuiltInScenario(
                new ScenarioRpcDescriptor(
                        eventCode,
                        PHONE_GROUP,
                        eventCode
                ),
                line -> {
                    if (line == null || line.trim().isEmpty()) {
                        throw new IllegalArgumentException(
                                "phone number line must not be blank"
                        );
                    }
                    return Map.of("rawNumber", line.trim());
                },
                requiredField
        );
    }

    private static BuiltInScenario string(
            String eventCode,
            String requiredField
    ) {
        return new BuiltInScenario(
                new ScenarioRpcDescriptor(
                        eventCode,
                        STRING_GROUP,
                        eventCode
                ),
                line -> Map.of("value", Objects.requireNonNull(line, "line")),
                requiredField
        );
    }

    private static String lineCrc32(int lineNumber, String line) {
        CRC32 crc32 = new CRC32();
        crc32.update(
                (lineNumber + "\0" + line).getBytes(StandardCharsets.UTF_8)
        );
        return String.format(Locale.ROOT, "%08x", crc32.getValue());
    }

    private static String requireIdentifier(String value, String name) {
        if (value == null || !value.matches(IDENTIFIER_PATTERN)) {
            throw new IllegalArgumentException(
                    name + " must match " + IDENTIFIER_PATTERN
            );
        }
        return value;
    }

    private static Map<String, Object> immutableMap(
            Map<String, Object> values
    ) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    private static void cancel(List<? extends Future<?>> futures) {
        for (Future<?> future : futures) {
            if (!future.isDone()) {
                future.cancel(true);
            }
        }
    }

    private static RuntimeException callFailure(Throwable cause) {
        if (cause instanceof RuntimeException runtimeError) {
            return runtimeError;
        }
        if (cause instanceof Error fatalError) {
            throw fatalError;
        }
        return new IllegalStateException("scenario RPC call failed", cause);
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

    private record BuiltInScenario(
            ScenarioRpcDescriptor descriptor,
            Function<String, Map<String, Object>> parser,
            String requiredResultField
    ) {
    }

    private record Seed(
            int sequence,
            String messageId,
            Map<String, Object> payload
    ) {
    }

    private record IndexedResult(
            int sequence,
            ScenarioRpcResult result
    ) {
    }
}
