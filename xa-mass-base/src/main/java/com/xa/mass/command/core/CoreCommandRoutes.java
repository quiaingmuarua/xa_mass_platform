package com.xa.mass.command.core;

import com.google.gson.JsonObject;

import java.util.Collections;
import java.util.Map;

public final class CoreCommandRoutes {

    private CoreCommandRoutes() {
    }

    public static void registerCommonRoutes() {
        registerIfAbsent(new CommandDefinition<>(
                BatchCommandRequest.EVENT,
                BatchCommandExecutor::execute,
                BatchCommandRequest::fromJson,
                CommandDefinition.Descriptor.simple(
                        BatchCommandRequest.EVENT,
                        "Execute a sequential batch of command events with shared flat context and explicit exports.",
                        java.util.List.of("trigger", "verify"),
                        false
                )
        ));

        registerIfAbsent(new CommandDefinition<>(
                "command.list",
                (request, context) -> Map.of(
                        "events", CommandDispatcher.getRegisteredDescriptors(),
                        "count", CommandDispatcher.getRegisteredDescriptors().size()
                ),
                json -> json,
                CommandDefinition.Descriptor.simple(
                        "command.list",
                        "List supported registered commands and their discovery metadata.",
                        java.util.List.of("prepare", "verify"),
                        true
                )
        ));

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
