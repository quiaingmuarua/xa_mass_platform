package com.xa.mass.integration.workerlab;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

final class WorkerLabArguments {

    private final Map<String, String> values;

    private WorkerLabArguments(Map<String, String> values) {
        this.values = Map.copyOf(values);
    }

    static WorkerLabArguments parse(
            String[] arguments,
            Set<String> knownNames
    ) {
        if (arguments == null) {
            throw new IllegalArgumentException("arguments must be present");
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (String argument : arguments) {
            if (argument == null) {
                throw new IllegalArgumentException(
                        "arguments must not contain null"
                );
            }
            int separator = argument.indexOf('=');
            if (!argument.startsWith("--")
                    || separator <= 2
                    || separator == argument.length() - 1) {
                throw new IllegalArgumentException(
                        "arguments must use --name=value"
                );
            }
            String name = argument.substring(2, separator);
            if (!knownNames.contains(name)) {
                throw new IllegalArgumentException(
                        "Unknown Worker Lab argument: " + name
                );
            }
            if (values.putIfAbsent(
                    name,
                    argument.substring(separator + 1)
            ) != null) {
                throw new IllegalArgumentException(
                        "Duplicate Worker Lab argument: " + name
                );
            }
        }
        return new WorkerLabArguments(values);
    }

    String value(String name, String defaultValue) {
        return values.getOrDefault(name, defaultValue);
    }

    String required(String name) {
        String value = values.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be provided");
        }
        return value;
    }

    long number(String name, long defaultValue) {
        String value = values.get(name);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(
                    name + " must be an integer",
                    error
            );
        }
    }
}
