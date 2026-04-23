package com.xa.mass.command.core;

import com.google.gson.JsonObject;
import com.xa.mass.command.model.CommandResponse;
import com.xa.mass.command.model.CommandContext;
import com.xa.mass.command.model.ErrorCode;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class CommandDispatcher {

    private CommandDispatcher() {
    }

    public static CommandResponse<?> dispatch(JsonObject json) {
        String event = stringValue(json, "event");
        long startTime = System.currentTimeMillis();

        CommandResponse<?> result;
        try {
            if (event.isEmpty()) {
                log("event is empty");
                result = new CommandResponse<>(ErrorCode.UNKNOWN_EVENT.code, "missing event");
            } else {
                CommandInvoker invoker = CommandRegistry.get(event);
                if (invoker == null) {
                    log("supported events=" + CommandRegistry.getRegisteredEvents());
                    result = new CommandResponse<>(
                            ErrorCode.UNKNOWN_EVENT.code,
                            buildUnknownEventMessage(event)
                    );
                } else {
                    result = invoker.invoke(json);
                }
            }
        } catch (Exception e) {
            log("dispatch error: " + e.getMessage());
            result = CommandResponse.fromException(e);
        }

        result.copyForwardFromRequest(json);
        result.setDuration(System.currentTimeMillis() - startTime);
        log("dispatch result=" + resultToLogString(event, result));
        return result;
    }

    public static Set<String> getRegisterMethods() {
        return CommandRegistry.getRegisteredEvents();
    }

    public static List<CommandDefinition.Descriptor> getRegisteredDescriptors() {
        return CommandRegistry.getRegisteredDescriptors();
    }

    public static CommandDefinition.Descriptor getDescriptor(String event) {
        return CommandRegistry.getDescriptor(event);
    }

    private static String resultToLogString(String event, CommandResponse<?> result) {
        return "event=" + event
                + ", status=" + result.status
                + ", code=" + result.code
                + ", message=" + result.message
                + ", duration=" + result.duration;
    }

    private static String buildUnknownEventMessage(String event) {
        Set<String> registeredEvents = CommandRegistry.getRegisteredEvents();
        String suggestions = registeredEvents.stream()
                .filter(k -> k.toLowerCase().contains(event.toLowerCase()))
                .collect(Collectors.joining(", "));

        return suggestions.isEmpty()
                ? "unknown event: " + event
                : "unknown event: " + event + ", did you mean: " + suggestions;
    }

    private static String stringValue(JsonObject json, String field) {
        if (json == null || !json.has(field) || json.get(field).isJsonNull()) {
            return "";
        }
        return json.get(field).getAsString().trim();
    }

    private static void log(String message) {
        CommandContext context = CommandContext.getInstance();
        context.logger().info(message == null ? "" : message);
    }
}
