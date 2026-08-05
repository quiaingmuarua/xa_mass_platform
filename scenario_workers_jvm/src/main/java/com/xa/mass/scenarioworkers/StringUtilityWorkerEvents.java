package com.xa.mass.scenarioworkers;

import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.worker.execution.WorkerEventHandler;
import com.xa.mass.worker.execution.WorkerEventParameterResolvers;
import com.xa.mass.workerdelivery.json.Jsons;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

final class StringUtilityWorkerEvents {

    static final String MD5_EVENT_CODE = "string.md5";
    static final String SHA1_EVENT_CODE = "string.sha1";
    static final String BASE64_ENCODE_EVENT_CODE =
            "string.base64.encode";

    private StringUtilityWorkerEvents() {
    }

    static List<WorkerEventDefinition<?>> definitions() {
        return List.of(
                definition(
                        MD5_EVENT_CODE,
                        "md5",
                        value -> digest("MD5", value)
                ),
                definition(
                        SHA1_EVENT_CODE,
                        "sha1",
                        value -> digest("SHA-1", value)
                ),
                definition(
                        BASE64_ENCODE_EVENT_CODE,
                        "base64",
                        value -> Base64.getEncoder().encodeToString(
                                value.getBytes(StandardCharsets.UTF_8)
                        )
                )
        );
    }

    private static WorkerEventDefinition<Map<String, Object>> definition(
            String eventCode,
            String outputField,
            Function<String, String> operation
    ) {
        WorkerEventHandler<Map<String, Object>> handler = payload ->
                execute(payload, outputField, operation);
        return WorkerEventDefinition.of(
                "TASK",
                eventCode,
                WorkerEventParameterResolvers.jsonMap(),
                handler
        );
    }

    private static String execute(
            Map<String, Object> payload,
            String outputField,
            Function<String, String> operation
    ) {
        Object input = payload.get("value");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("input", input);
        if (!(input instanceof String)) {
            result.put("valid", false);
            result.put("error", "VALUE_STRING_REQUIRED");
            return Jsons.toJson(result);
        }
        result.put("valid", true);
        result.put(outputField, operation.apply((String) input));
        return Jsons.toJson(result);
    }

    private static String digest(String algorithm, String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance(algorithm).digest(
                            value.getBytes(StandardCharsets.UTF_8)
                    )
            );
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(
                    "Required digest algorithm is unavailable: "
                            + algorithm,
                    error
            );
        }
    }
}
