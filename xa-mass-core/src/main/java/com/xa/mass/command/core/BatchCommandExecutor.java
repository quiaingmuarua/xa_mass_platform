package com.xa.mass.command.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.xa.mass.command.model.CommandContext;
import com.xa.mass.command.model.CommandException;
import com.xa.mass.command.model.CommandResponse;
import com.xa.mass.command.model.ErrorCode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class BatchCommandExecutor {

    private BatchCommandExecutor() {
    }

    public static Map<String, Object> execute(BatchCommandRequest request, CommandContext context) {
        Map<String, Object> sharedContext = new LinkedHashMap<>(request.getContext());
        List<Map<String, Object>> results = new ArrayList<>();

        for (BatchCommandRequest.BatchStep step : request.getSteps()) {
            long startTime = System.currentTimeMillis();
            CommandResponse<?> response;
            try {
                JsonObject stepRequest = buildStepRequest(step, sharedContext);
                response = CommandDispatcher.dispatch(stepRequest);
                if (response.isSuccess() && !step.getExport().entrySet().isEmpty()) {
                    Map<String, Object> exports = resolveExports(step, response.getData(), sharedContext);
                    sharedContext.putAll(exports);
                }
            } catch (Exception e) {
                response = CommandResponse.fromException(e);
            }

            Map<String, Object> stepResult = toStepResult(step, response, System.currentTimeMillis() - startTime);
            results.add(stepResult);

            if (!response.isSuccess() && BatchCommandRequest.ON_ERROR_STOP.equals(request.getOnError())) {
                break;
            }
        }

        Map<String, Object> batchData = new LinkedHashMap<>();
        batchData.put("context", new LinkedHashMap<>(sharedContext));
        batchData.put("results", results);
        return batchData;
    }

    private static JsonObject buildStepRequest(BatchCommandRequest.BatchStep step, Map<String, Object> sharedContext) {
        JsonObject json = new JsonObject();
        json.addProperty("event", step.getEvent());
        for (Map.Entry<String, JsonElement> entry : step.getParams().entrySet()) {
            String key = entry.getKey();
            if ("event".equals(key)) {
                throw new CommandException(ErrorCode.PARSE_ERROR, "batch step params must not contain event");
            }
            Object raw = BatchPathResolver.toPlainValue(entry.getValue());
            Object resolved = resolveParamValue(raw, sharedContext);
            putPlainValue(json, key, resolved);
        }
        return json;
    }

    private static Object resolveParamValue(Object raw, Map<String, Object> sharedContext) {
        if (!(raw instanceof String value)) {
            return BatchPathResolver.normalizeScalarValue(raw);
        }
        if (!value.startsWith("$ctx.")) {
            return value;
        }
        return BatchPathResolver.resolveContextReference(value, sharedContext);
    }

    private static Map<String, Object> resolveExports(
            BatchCommandRequest.BatchStep step,
            Object resultData,
            Map<String, Object> sharedContext
    ) {
        Map<String, Object> exports = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : step.getExport().entrySet()) {
            String key = entry.getKey();
            if (sharedContext.containsKey(key) || exports.containsKey(key)) {
                throw new CommandException(ErrorCode.PARSE_ERROR, "batch export key already exists in context: " + key);
            }
            Object raw = BatchPathResolver.toPlainValue(entry.getValue());
            if (!(raw instanceof String reference) || !reference.startsWith("$result")) {
                throw new CommandException(ErrorCode.PARSE_ERROR, "batch export must use $result path: " + key);
            }
            Object resolved = BatchPathResolver.resolveResultReference(reference, resultData);
            if (!BatchPathResolver.isScalarValue(resolved)) {
                throw new CommandException(ErrorCode.PARSE_ERROR, "batch export only supports scalar values: " + key);
            }
            exports.put(key, BatchPathResolver.normalizeScalarValue(resolved));
        }
        return exports;
    }

    private static Map<String, Object> toStepResult(
            BatchCommandRequest.BatchStep step,
            CommandResponse<?> response,
            long duration
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", step.getId());
        result.put("event", step.getEvent());
        result.put("status", response.status);
        result.put("code", response.code);
        result.put("message", response.message);
        result.put("data", response.data);
        result.put("duration", (int) duration);
        return result;
    }

    private static void putPlainValue(JsonObject json, String key, Object value) {
        if (value == null) {
            json.add(key, JsonNull.INSTANCE);
        } else if (value instanceof Number number) {
            json.add(key, new JsonPrimitive(number));
        } else if (value instanceof Boolean bool) {
            json.addProperty(key, bool);
        } else {
            json.addProperty(key, String.valueOf(value));
        }
    }
}
