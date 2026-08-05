package com.xa.mass.integration.workercapability;

import java.net.URI;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

final class CommandLineOptions {

    private final Map<String, String> values;

    private CommandLineOptions(Map<String, String> values) {
        this.values = Map.copyOf(values);
    }

    static CommandLineOptions parse(String[] arguments) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String argument : arguments) {
            if (argument == null || !argument.startsWith("--")) {
                throw new IllegalArgumentException(
                        "Arguments must use --name=value"
                );
            }
            int separator = argument.indexOf('=');
            if (separator <= 2 || separator == argument.length() - 1) {
                throw new IllegalArgumentException(
                        "Arguments must use --name=value"
                );
            }
            String name = argument.substring(2, separator);
            String value = argument.substring(separator + 1);
            if (values.putIfAbsent(name, value) != null) {
                throw new IllegalArgumentException(
                        "Duplicate argument: --" + name
                );
            }
        }
        return new CommandLineOptions(values);
    }

    String string(String name, String defaultValue) {
        return values.getOrDefault(name, defaultValue);
    }

    long positiveLong(String name, long defaultValue) {
        String value = values.get(name);
        long parsed = value == null
                ? defaultValue
                : Long.parseLong(value);
        if (parsed <= 0) {
            throw new IllegalArgumentException(
                    "--" + name + " must be positive"
            );
        }
        return parsed;
    }

    URI uri(String name, String defaultValue) {
        return URI.create(string(name, defaultValue));
    }

    Path path(String name, String defaultValue) {
        return Path.of(string(name, defaultValue));
    }
}
