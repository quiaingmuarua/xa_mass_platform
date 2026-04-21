package com.xa.mass.mock.command.core;

import com.google.gson.JsonObject;
import com.xa.mass.mock.command.model.CommandContext;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CoreCommandRoutes {

    private CoreCommandRoutes() {
    }

    public static void registerCommonRoutes() {
        registerIfAbsent(CommandDefinition.<BatchCommandRequest, Map<String, Object>>builder(BatchCommandRequest.EVENT)
                .handler(BatchCommandExecutor::execute)
                .resolver(BatchCommandRequest::fromJson)
                .summary("Execute a sequential batch of command events with shared flat context and explicit exports.")
                .suggestedPhases("trigger", "verify")
                .safeForScenario(false)
                .build());

        registerIfAbsent(CommandDefinition.<JsonObject, Map<String, Object>>builder("command.list")
                .handler((request, context) -> Map.of(
                        "events", CommandDispatcher.getRegisteredDescriptors(),
                        "count", CommandDispatcher.getRegisteredDescriptors().size()
                ))
                .resolver(json -> json)
                .summary("List supported mock client commands and their discovery metadata.")
                .suggestedPhases("prepare", "verify")
                .safeForScenario(true)
                .build());

        registerIfAbsent(CommandDefinition.<JsonObject, Map<String, Object>>builder("client.info")
                .handler(CoreCommandRoutes::clientInfo)
                .resolver(json -> json)
                .summary("Return mock client identity and runtime facts.")
                .suggestedPhases("prepare", "verify")
                .safeForScenario(true)
                .build());

        registerIfAbsent(CommandDefinition.<JsonObject, Map<String, Object>>builder("client.echo")
                .handler(CoreCommandRoutes::clientEcho)
                .resolver(json -> json)
                .summary("Echo the provided value/text for manual debug validation.")
                .suggestedPhases("trigger", "verify")
                .safeForScenario(true)
                .build());

        registerIfAbsent(CommandDefinition.<JsonObject, Map<String, Object>>builder("client.sleep")
                .handler(CoreCommandRoutes::clientSleep)
                .resolver(json -> json)
                .summary("Sleep for a bounded delay to simulate a slow worker command.")
                .suggestedPhases("trigger", "verify")
                .safeForScenario(true)
                .build());

        registerIfAbsent(CommandDefinition.<JsonObject, Map<String, Object>>builder("client.fail")
                .handler((request, context) -> {
                    throw new com.xa.mass.mock.command.model.CommandException(
                            com.xa.mass.mock.command.model.ErrorCode.UNKNOWN_ERROR,
                            stringValue(request, "message", "mock client command failed")
                    );
                })
                .resolver(json -> json)
                .summary("Force a structured command failure for debug/testing.")
                .suggestedPhases("trigger", "verify")
                .safeForScenario(true)
                .build());

        if (!CommandRegistry.contains("onOpen")) {
            CommandRegistry.registerNoArg(
                    "onOpen",
                    context -> "ok",
                    CommandDefinition.Descriptor.simple(
                            "onOpen",
                            "Common liveness route for simple open checks.",
                            Collections.singletonList("prepare"),
                            false
                    )
            );
        }
    }

    private static Map<String, Object> clientInfo(JsonObject request, CommandContext context) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("workerId", stringValue(request, "workerId", "unknown"));
        info.put("timestamp", System.currentTimeMillis());
        info.put("supportedEvents", CommandDispatcher.getRegisteredDescriptors());
        info.put("phaseHints", List.of("prepare", "trigger", "verify"));
        return info;
    }

    private static Map<String, Object> clientEcho(JsonObject request, CommandContext context) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("workerId", stringValue(request, "workerId", "unknown"));
        data.put("text", stringValue(request, "text", ""));
        data.put("value", request.has("value") ? BatchPathResolver.toPlainValue(request.get("value")) : null);
        data.put("timestamp", System.currentTimeMillis());
        return data;
    }

    private static Map<String, Object> clientSleep(JsonObject request, CommandContext context) throws InterruptedException {
        long requested = longValue(request, "millis", 0L);
        long sleepMillis = Math.max(0L, Math.min(requested, 5000L));
        Thread.sleep(sleepMillis);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("workerId", stringValue(request, "workerId", "unknown"));
        data.put("sleptMillis", sleepMillis);
        data.put("requestedMillis", requested);
        data.put("timestamp", System.currentTimeMillis());
        return data;
    }

    private static void registerIfAbsent(CommandDefinition<?, ?> definition) {
        if (!CommandRegistry.contains(definition.getEvent())) {
            CommandRegistry.register(definition);
        }
    }

    private static String stringValue(JsonObject json, String field, String defaultValue) {
        if (json == null || !json.has(field) || json.get(field).isJsonNull()) {
            return defaultValue;
        }
        return json.get(field).getAsString();
    }

    private static long longValue(JsonObject json, String field, long defaultValue) {
        if (json == null || !json.has(field) || json.get(field).isJsonNull()) {
            return defaultValue;
        }
        try {
            return json.get(field).getAsLong();
        } catch (Exception ignore) {
            return defaultValue;
        }
    }
}
