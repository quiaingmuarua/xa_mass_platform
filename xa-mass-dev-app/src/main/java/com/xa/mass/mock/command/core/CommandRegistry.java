package com.xa.mass.mock.command.core;

import com.google.gson.JsonObject;
import com.xa.mass.mock.command.model.ApiResponse;
import com.xa.mass.mock.command.model.CommandContext;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Process-local command registration and lookup center for the dev mock client.
 */
public class CommandRegistry {

    private static final Map<String, CommandInvoker> HANDLERS = new ConcurrentHashMap<>();
    private static final Map<String, CommandDefinition.Descriptor> DESCRIPTORS = new ConcurrentHashMap<>();

    private CommandRegistry() {
    }

    public static <V> void registerNoArg(String event,
                                         NoArgCommandHandler<V> handler) {
        registerNoArg(event, handler, CommandDefinition.Descriptor.fallback(event));
    }

    public static <V> void registerNoArg(String event,
                                         NoArgCommandHandler<V> handler,
                                         CommandDefinition.Descriptor descriptor) {
        register(event,
                (Void req, CommandContext ctx) -> handler.handle(ctx),
                json -> null,
                descriptor);
    }

    public static <T, V> void register(CommandDefinition<T, V> definition) {
        register(definition.getEvent(), definition.getHandler(), definition.getResolver(), definition.getDescriptor());
    }

    public static <T, V> void register(String event,
                                       CommandHandler<T, V> handler,
                                       Function<JsonObject, T> resolver,
                                       CommandDefinition.Descriptor descriptor) {
        if (event == null || event.trim().isEmpty()) {
            throw new IllegalArgumentException("event is empty");
        }
        if (handler == null) {
            throw new IllegalArgumentException("handler is null");
        }
        if (resolver == null) {
            throw new IllegalArgumentException("resolver is null");
        }

        CommandInvoker invoker = json -> {
            T request = resolver.apply(json);
            CommandContext ctx = CommandContext.getInstance();
            V result = handler.handle(request, ctx);
            return ApiResponse.success(result);
        };

        CommandInvoker old = HANDLERS.putIfAbsent(event, invoker);
        if (old != null) {
            throw new IllegalStateException("duplicate event register: " + event);
        }
        DESCRIPTORS.put(event, descriptor == null ? CommandDefinition.Descriptor.fallback(event) : descriptor);
    }

    public static boolean contains(String event) {
        return HANDLERS.containsKey(event);
    }

    public static CommandInvoker get(String event) {
        return HANDLERS.get(event);
    }

    public static Set<String> getRegisteredEvents() {
        return Collections.unmodifiableSet(HANDLERS.keySet());
    }

    public static CommandDefinition.Descriptor getDescriptor(String event) {
        return DESCRIPTORS.get(event);
    }

    public static List<CommandDefinition.Descriptor> getRegisteredDescriptors() {
        return DESCRIPTORS.values().stream()
                .sorted((left, right) -> left.getEvent().compareToIgnoreCase(right.getEvent()))
                .collect(Collectors.toList());
    }
}
