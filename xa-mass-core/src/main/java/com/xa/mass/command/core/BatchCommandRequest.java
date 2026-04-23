package com.xa.mass.command.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.xa.mass.base.exception.CommandException;
import com.xa.mass.base.exception.ErrorCode;

import java.util.*;

public final class BatchCommandRequest {

    public static final String EVENT = "batch";
    public static final String ON_ERROR_STOP = "stop";
    public static final String ON_ERROR_CONTINUE = "continue";

    private final String onError;
    private final Map<String, Object> context;
    private final List<BatchStep> steps;

    private BatchCommandRequest(String onError, Map<String, Object> context, List<BatchStep> steps) {
        this.onError = onError;
        this.context = context;
        this.steps = steps;
    }

    public String getOnError() {
        return onError;
    }

    public Map<String, Object> getContext() {
        return context;
    }

    public List<BatchStep> getSteps() {
        return steps;
    }

    public static BatchCommandRequest fromJson(JsonObject json) {
        String onError = stringValue(json, "onError", ON_ERROR_STOP);
        if (!ON_ERROR_STOP.equals(onError) && !ON_ERROR_CONTINUE.equals(onError)) {
            throw new CommandException(ErrorCode.PARSE_ERROR, "batch onError must be stop or continue");
        }

        Map<String, Object> context = parseContext(json);
        List<BatchStep> steps = parseSteps(json);
        return new BatchCommandRequest(onError, context, steps);
    }

    private static Map<String, Object> parseContext(JsonObject json) {
        if (!json.has("context")) {
            return Collections.emptyMap();
        }
        JsonElement raw = json.get("context");
        if (!raw.isJsonObject()) {
            throw new CommandException(ErrorCode.PARSE_ERROR, "batch context must be an object");
        }

        Map<String, Object> context = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : raw.getAsJsonObject().entrySet()) {
            Object value = BatchPathResolver.toPlainValue(entry.getValue());
            if (!BatchPathResolver.isScalarValue(value)) {
                throw new CommandException(ErrorCode.PARSE_ERROR, "batch context only supports scalar values: " + entry.getKey());
            }
            context.put(entry.getKey(), BatchPathResolver.normalizeScalarValue(value));
        }
        return Collections.unmodifiableMap(context);
    }

    private static List<BatchStep> parseSteps(JsonObject json) {
        JsonElement raw = json.get("events");
        if (raw == null || !raw.isJsonArray()) {
            throw new CommandException(ErrorCode.PARSE_ERROR, "batch events must be an array");
        }

        List<BatchStep> steps = new ArrayList<>();
        int index = 0;
        for (JsonElement stepRaw : raw.getAsJsonArray()) {
            if (!stepRaw.isJsonObject()) {
                throw new CommandException(ErrorCode.PARSE_ERROR, "batch step must be an object, index=" + index);
            }
            JsonObject stepJson = stepRaw.getAsJsonObject();
            String event = stringValue(stepJson, "event", "").trim();
            if (event.isEmpty()) {
                throw new CommandException(ErrorCode.PARSE_ERROR, "batch step missing event, index=" + index);
            }
            if (EVENT.equalsIgnoreCase(event)) {
                throw new CommandException(ErrorCode.PARSE_ERROR, "nested batch is not supported, index=" + index);
            }

            JsonObject params = optionalObjectField(stepJson, "params");
            JsonObject export = optionalObjectField(stepJson, "export");
            String id = stepJson.has("id") && !stepJson.get("id").isJsonNull()
                    ? stepJson.get("id").getAsString()
                    : null;
            steps.add(new BatchStep(id, event, params, export));
            index++;
        }
        return Collections.unmodifiableList(steps);
    }

    private static JsonObject optionalObjectField(JsonObject json, String key) {
        if (!json.has(key)) {
            return new JsonObject();
        }
        JsonElement raw = json.get(key);
        if (!raw.isJsonObject()) {
            throw new CommandException(ErrorCode.PARSE_ERROR, "batch step " + key + " must be an object");
        }
        return raw.getAsJsonObject();
    }

    private static String stringValue(JsonObject json, String key, String defaultValue) {
        if (!json.has(key) || json.get(key).isJsonNull()) {
            return defaultValue;
        }
        return json.get(key).getAsString();
    }

    public static final class BatchStep {
        private final String id;
        private final String event;
        private final JsonObject params;
        private final JsonObject export;

        private BatchStep(String id, String event, JsonObject params, JsonObject export) {
            this.id = id;
            this.event = event;
            this.params = params;
            this.export = export;
        }

        public String getId() {
            return id;
        }

        public String getEvent() {
            return event;
        }

        public JsonObject getParams() {
            return params;
        }

        public JsonObject getExport() {
            return export;
        }
    }
}
