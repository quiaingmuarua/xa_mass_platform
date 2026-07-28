package com.xa.mass.worker.execution;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

public final class StringTransformHandler implements WorkerEventHandler {

    public static final String EVENT_CODE = "utility.string.transform";

    private final JsonMapper json;

    public StringTransformHandler() {
        this(JsonMapper.builder().build());
    }

    StringTransformHandler(JsonMapper json) {
        this.json = json;
    }

    @Override
    public JsonNode execute(JsonNode payload) throws WorkerInputException {
        JsonNode operationNode = payload.get("operation");
        JsonNode valueNode = payload.get("value");
        if (operationNode == null
                || !operationNode.isString()
                || valueNode == null
                || !valueNode.isString()) {
            throw new WorkerInputException(
                    "operation and value must be strings"
            );
        }

        String operation = operationNode.stringValue();
        byte[] value = valueNode.stringValue().getBytes(StandardCharsets.UTF_8);
        String result = switch (operation) {
            case "BASE64" -> Base64.getEncoder().encodeToString(value);
            case "MD5" -> digest("MD5", value);
            case "SHA1" -> digest("SHA-1", value);
            default -> throw new WorkerInputException(
                    "unsupported string transform operation"
            );
        };

        ObjectNode response = json.createObjectNode();
        response.put("operation", operation);
        response.put("result", result);
        return response;
    }

    private static String digest(String algorithm, byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance(algorithm).digest(value)
            );
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(
                    "Required digest algorithm is unavailable",
                    error
            );
        }
    }
}
