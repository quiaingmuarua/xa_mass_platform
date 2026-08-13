package com.xa.mass.integration.workercapability;

import com.xa.mass.workerdelivery.json.Jsons;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;

final class StringUtilityRpcScenario {

    private static final String WORKER_GROUP_ID =
            "scenario-string-utils-workers";
    private static final List<EventContract> EVENTS = List.of(
            new EventContract("string.md5", "md5"),
            new EventContract("string.sha1", "sha1"),
            new EventContract("string.base64.encode", "base64")
    );

    private final WorkerGroupRpcClient rpc;

    StringUtilityRpcScenario(WorkerGroupRpcClient rpc) {
        this.rpc = rpc;
    }

    int run(
            String scenarioId,
            Path seedPath,
            Path outputPath,
            long waitTimeoutMillis
    ) throws IOException {
        List<String> inputs = WorkerCapabilityInputs.readDistinct(
                seedPath,
                "string-seed.txt",
                10
        );
        List<Callable<String>> calls = new ArrayList<>(
                EVENTS.size() * inputs.size()
        );
        for (EventContract event : EVENTS) {
            for (int index = 0; index < inputs.size(); index++) {
                int inputIndex = index;
                calls.add(() -> callAndEncode(
                        scenarioId,
                        event,
                        inputs.get(inputIndex),
                        waitTimeoutMillis,
                        inputIndex + 1
                ));
            }
        }
        List<String> results = WorkerCapabilityCallBatch.invoke(calls);
        WorkerCapabilityResultWriter.writeAtomically(outputPath, results);
        return results.size();
    }

    private String callAndEncode(
            String scenarioId,
            EventContract event,
            String value,
            long waitTimeoutMillis,
            int inputIndex
    ) {
        String messageId = messageId(
                scenarioId,
                event.eventCode(),
                inputIndex
        );
        Map<String, Object> input = Map.of("value", value);
        Map<String, Object> result = rpc.call(
                WORKER_GROUP_ID,
                messageId,
                event.eventCode(),
                input,
                waitTimeoutMillis
        );
        requireValidResult(messageId, event, result);

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("workerGroupId", WORKER_GROUP_ID);
        output.put("messageId", messageId);
        output.put("eventCode", event.eventCode());
        output.put("input", input);
        output.put("result", result);
        return Jsons.toJson(output);
    }

    private static void requireValidResult(
            String messageId,
            EventContract event,
            Map<String, Object> result
    ) {
        if (!Boolean.TRUE.equals(result.get("valid"))) {
            throw new IllegalStateException(
                    "RPC returned invalid domain output for " + messageId
            );
        }
        if (!result.containsKey(event.resultField())) {
            throw new IllegalStateException(
                    "RPC result is missing "
                            + event.resultField()
                            + " for "
                            + messageId
            );
        }
    }

    private static String messageId(
            String scenarioId,
            String eventCode,
            int inputIndex
    ) {
        return scenarioId
                + "-string-"
                + eventCode.replace('.', '-')
                + "-"
                + String.format(Locale.ROOT, "%03d", inputIndex);
    }

    private record EventContract(
            String eventCode,
            String resultField
    ) {
    }
}
