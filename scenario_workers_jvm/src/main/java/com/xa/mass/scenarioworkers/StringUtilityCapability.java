package com.xa.mass.scenarioworkers;

import com.xa.mass.worker.execution.WorkerEventDefinition;
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
import java.util.Set;
import java.util.function.Function;

final class StringUtilityCapability {

    public static final String MD5_EVENT_CODE = "string.md5";
    public static final String SHA1_EVENT_CODE = "string.sha1";
    public static final String BASE64_ENCODE_EVENT_CODE =
            "string.base64.encode";
    public static final Set<String> EVENT_CODES = Set.of(
            MD5_EVENT_CODE,
            SHA1_EVENT_CODE,
            BASE64_ENCODE_EVENT_CODE
    );

    private StringUtilityCapability() {
    }

    public static List<WorkerEventDefinition<Map<String, Object>>>
    definitions(
            String workerId
    ) {
        return List.of(
                definition(
                        MD5_EVENT_CODE,
                        workerId,
                        "md5",
                        value -> digest("MD5", value)
                ),
                definition(
                        SHA1_EVENT_CODE,
                        workerId,
                        "sha1",
                        value -> digest("SHA-1", value)
                ),
                definition(
                        BASE64_ENCODE_EVENT_CODE,
                        workerId,
                        "base64",
                        value -> Base64.getEncoder().encodeToString(
                                value.getBytes(StandardCharsets.UTF_8)
                        )
                )
        );
    }

    private static WorkerEventDefinition<Map<String, Object>>
    definition(
            String eventCode,
            String workerId,
            String outputField,
            Function<String, String> operation
    ) {
        return WorkerEventDefinition.of(
                "TASK",
                eventCode,
                WorkerEventParameterResolvers.jsonMap(),
                payload -> Jsons.toJson(
                        execute(
                                workerId,
                                payload,
                                outputField,
                                operation
                        )
                )
        );
    }

    private static Map<String, Object> execute(
            String workerId,
            Map<String, Object> payload,
            String outputField,
            Function<String, String> operation
    ) {
        Object input = payload.get("value");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("input", input);
        result.put("workerId", workerId);
        if (!(input instanceof String value)) {
            result.put("valid", false);
            result.put("error", "VALUE_STRING_REQUIRED");
            return result;
        }
        result.put("valid", true);
        result.put(outputField, operation.apply(value));
        return result;
    }

    private static String digest(
            String algorithm,
            String value
    ) {
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
