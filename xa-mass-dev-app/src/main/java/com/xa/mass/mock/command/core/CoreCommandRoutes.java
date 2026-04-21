package com.xa.mass.mock.command.core;

import com.google.gson.JsonObject;

import java.util.Collections;
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

    private static void registerIfAbsent(CommandDefinition<?, ?> definition) {
        if (!CommandRegistry.contains(definition.getEvent())) {
            CommandRegistry.register(definition);
        }
    }
}
