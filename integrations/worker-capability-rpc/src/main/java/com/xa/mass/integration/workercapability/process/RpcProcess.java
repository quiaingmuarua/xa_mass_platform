package com.xa.mass.integration.workercapability.process;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class RpcProcess {

    private static final int MAX_WORKERS = 100;
    private static final String IDENTIFIER_PATTERN = "[A-Za-z0-9._-]+";

    private final RpcCall rpcCall;
    private final String scenarioId;
    private final String processName;
    private final String workerGroupId;
    private final List<String> lines;
    private final List<String> eventCodes;
    private final RpcPayloadParser payloadParser;
    private final List<RpcResultMiddleware> middlewares;
    private final int maxWorkers;
    private final long waitTimeoutMillis;

    private RpcProcess(Builder builder) {
        rpcCall = Objects.requireNonNull(builder.rpcCall, "rpcCall");
        scenarioId = requireIdentifier(builder.scenarioId, "scenarioId");
        processName = requireIdentifier(builder.processName, "processName");
        workerGroupId = requireIdentifier(
                builder.workerGroupId,
                "workerGroupId"
        );
        lines = List.copyOf(Objects.requireNonNull(builder.lines, "lines"));
        eventCodes = validatedEventCodes(builder.eventCodes);
        payloadParser = Objects.requireNonNull(
                builder.payloadParser,
                "payloadParser"
        );
        middlewares = List.copyOf(Objects.requireNonNull(
                builder.middlewares,
                "middlewares"
        ));
        if (builder.maxWorkers < 1 || builder.maxWorkers > MAX_WORKERS) {
            throw new IllegalArgumentException(
                    "maxWorkers must be between 1 and " + MAX_WORKERS
            );
        }
        maxWorkers = builder.maxWorkers;
        if (builder.waitTimeoutMillis <= 0) {
            throw new IllegalArgumentException(
                    "waitTimeoutMillis must be positive"
            );
        }
        waitTimeoutMillis = builder.waitTimeoutMillis;
    }

    public static Builder builder(RpcCall rpcCall) {
        return new Builder(rpcCall);
    }

    public List<RpcResult> start() {
        List<RpcSeed> seeds = generateSeeds();
        List<RpcResult> results = invoke(seeds);
        for (RpcResultMiddleware middleware : middlewares) {
            try {
                middleware.process(results);
            } catch (RuntimeException error) {
                throw error;
            } catch (Exception error) {
                throw new IllegalStateException(
                        "RPC process middleware failed: " + processName,
                        error
                );
            }
        }
        return results;
    }

    private List<RpcSeed> generateSeeds() {
        List<RpcSeed> seeds = new ArrayList<>(
                lines.size() * eventCodes.size()
        );
        int sequence = 0;
        for (int lineIndex = 0; lineIndex < lines.size(); lineIndex++) {
            int lineNumber = lineIndex + 1;
            Map<String, Object> payload;
            try {
                payload = payloadParser.parseLine(lines.get(lineIndex));
                if (payload == null) {
                    throw new IllegalArgumentException(
                            "parseLine returned null"
                    );
                }
                payload = Collections.unmodifiableMap(
                        new LinkedHashMap<>(payload)
                );
            } catch (RuntimeException error) {
                throw new IllegalArgumentException(
                        "RPC process "
                                + processName
                                + " could not parse line "
                                + lineNumber,
                        error
                );
            }
            for (String eventCode : eventCodes) {
                seeds.add(new RpcSeed(
                        sequence++,
                        lineNumber,
                        messageId(eventCode, lineNumber),
                        eventCode,
                        payload
                ));
            }
        }
        return List.copyOf(seeds);
    }

    private List<RpcResult> invoke(List<RpcSeed> seeds) {
        if (seeds.isEmpty()) {
            return List.of();
        }
        ExecutorService executor = Executors.newFixedThreadPool(
                maxWorkers,
                Thread.ofVirtual()
                        .name("rpc-process-" + processName + "-", 0)
                        .factory()
        );
        CompletionService<IndexedResult> completions =
                new ExecutorCompletionService<>(executor);
        List<Future<IndexedResult>> submitted = new ArrayList<>();
        List<RpcResult> ordered = new ArrayList<>(
                java.util.Collections.nCopies(seeds.size(), null)
        );
        int nextSeed = 0;
        int completed = 0;
        try {
            while (nextSeed < seeds.size()
                    && submitted.size() < maxWorkers) {
                submitted.add(submit(completions, seeds.get(nextSeed++)));
            }
            while (completed < seeds.size()) {
                Future<IndexedResult> finished = completions.take();
                IndexedResult indexed = finished.get();
                ordered.set(indexed.sequence(), indexed.result());
                completed++;
                if (nextSeed < seeds.size()) {
                    submitted.add(submit(
                            completions,
                            seeds.get(nextSeed++)
                    ));
                }
            }
            return List.copyOf(ordered);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            cancel(submitted);
            throw new IllegalStateException(
                    "RPC process was interrupted: " + processName,
                    error
            );
        } catch (ExecutionException error) {
            cancel(submitted);
            throw callFailure(error.getCause());
        } finally {
            executor.shutdownNow();
        }
    }

    private Future<IndexedResult> submit(
            CompletionService<IndexedResult> completions,
            RpcSeed seed
    ) {
        return completions.submit(() -> {
            Map<String, Object> result = rpcCall.call(
                    workerGroupId,
                    seed.messageId(),
                    seed.eventCode(),
                    seed.payload(),
                    waitTimeoutMillis
            );
            return new IndexedResult(
                    seed.sequence(),
                    new RpcResult(
                            workerGroupId,
                            seed.messageId(),
                            seed.eventCode(),
                            seed.payload(),
                            result
                    )
            );
        });
    }

    private String messageId(String eventCode, int lineNumber) {
        return scenarioId
                + "-"
                + processName
                + "-"
                + normalizeEventCode(eventCode)
                + "-"
                + String.format(Locale.ROOT, "%03d", lineNumber);
    }

    private static List<String> validatedEventCodes(List<String> values) {
        List<String> eventCodes = List.copyOf(Objects.requireNonNull(
                values,
                "eventCodes"
        ));
        if (eventCodes.isEmpty()) {
            throw new IllegalArgumentException("eventCodes must not be empty");
        }
        Set<String> exact = new HashSet<>();
        Set<String> normalized = new HashSet<>();
        for (String eventCode : eventCodes) {
            requireIdentifier(eventCode, "eventCode");
            if (!exact.add(eventCode)) {
                throw new IllegalArgumentException(
                        "eventCodes must be unique: " + eventCode
                );
            }
            if (!normalized.add(normalizeEventCode(eventCode))) {
                throw new IllegalArgumentException(
                        "eventCodes produce duplicate message IDs: "
                                + eventCode
                );
            }
        }
        return eventCodes;
    }

    private static String normalizeEventCode(String eventCode) {
        return eventCode.replace('.', '-');
    }

    private static String requireIdentifier(String value, String name) {
        if (value == null || !value.matches(IDENTIFIER_PATTERN)) {
            throw new IllegalArgumentException(
                    name + " must match " + IDENTIFIER_PATTERN
            );
        }
        return value;
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
        return new IllegalStateException("RPC process call failed", cause);
    }

    @FunctionalInterface
    public interface RpcCall {

        Map<String, Object> call(
                String workerGroupId,
                String messageId,
                String eventCode,
                Map<String, Object> payload,
                long waitTimeoutMillis
        );
    }

    public static final class Builder {

        private final RpcCall rpcCall;
        private String scenarioId;
        private String processName;
        private String workerGroupId;
        private List<String> lines;
        private List<String> eventCodes;
        private RpcPayloadParser payloadParser;
        private List<RpcResultMiddleware> middlewares = List.of();
        private int maxWorkers = 30;
        private long waitTimeoutMillis = 30_000;

        private Builder(RpcCall rpcCall) {
            this.rpcCall = Objects.requireNonNull(rpcCall, "rpcCall");
        }

        public Builder scenarioId(String value) {
            scenarioId = value;
            return this;
        }

        public Builder processName(String value) {
            processName = value;
            return this;
        }

        public Builder workerGroupId(String value) {
            workerGroupId = value;
            return this;
        }

        public Builder lines(List<String> value) {
            lines = value;
            return this;
        }

        public Builder eventCodes(List<String> value) {
            eventCodes = value;
            return this;
        }

        public Builder parseLine(RpcPayloadParser value) {
            payloadParser = value;
            return this;
        }

        public Builder middlewares(List<RpcResultMiddleware> value) {
            middlewares = value;
            return this;
        }

        public Builder maxWorkers(int value) {
            maxWorkers = value;
            return this;
        }

        public Builder waitTimeoutMillis(long value) {
            waitTimeoutMillis = value;
            return this;
        }

        public RpcProcess build() {
            return new RpcProcess(this);
        }
    }

    private record IndexedResult(int sequence, RpcResult result) {
    }
}
