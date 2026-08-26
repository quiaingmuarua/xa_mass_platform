package com.xa.mass.server.openapi;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

public final class OpenApiSnapshotSupport {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private OpenApiSnapshotSupport() {
    }

    public static String canonicalize(String rawDocument) throws Exception {
        JsonNode parsed = JSON.readTree(rawDocument);
        if (!(parsed instanceof ObjectNode document)) {
            throw new IllegalArgumentException(
                    "OpenAPI document root must be an object"
            );
        }
        if (!"3.1.0".equals(document.path("openapi").asText())) {
            throw new IllegalArgumentException(
                    "OpenAPI document must use version 3.1.0"
            );
        }
        if (!"XA Mass Runtime API".equals(
                document.path("info").path("title").asText()
        )) {
            throw new IllegalArgumentException(
                    "OpenAPI document title is invalid"
            );
        }
        List<String> paths = new ArrayList<>(
                document.path("paths").propertyNames()
        );
        if (paths.isEmpty() || paths.stream().anyMatch(
                path -> !path.startsWith("/api/v1/")
        )) {
            throw new IllegalArgumentException(
                    "OpenAPI snapshot may contain only /api/v1/** paths"
            );
        }

        document.remove("servers");
        String rendered = JSON.writerWithDefaultPrettyPrinter()
                .writeValueAsString(sort(document));
        return rendered.replace("\r\n", "\n")
                .replace('\r', '\n') + "\n";
    }

    private static JsonNode sort(JsonNode node) {
        if (node instanceof ObjectNode objectNode) {
            ObjectNode sorted = JsonNodeFactory.instance.objectNode();
            objectNode.properties().stream()
                    .sorted(Comparator.comparing(entry -> entry.getKey()))
                    .forEach(entry -> sorted.set(
                            entry.getKey(),
                            sort(entry.getValue())
                    ));
            return sorted;
        }
        if (node instanceof ArrayNode arrayNode) {
            ArrayNode sorted = JsonNodeFactory.instance.arrayNode();
            arrayNode.forEach(child -> sorted.add(sort(child)));
            return sorted;
        }
        return node.deepCopy();
    }
}
