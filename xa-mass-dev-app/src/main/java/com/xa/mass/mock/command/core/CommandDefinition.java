package com.xa.mass.mock.command.core;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

public final class CommandDefinition<T, V> {

    private final String event;
    private final CommandHandler<T, V> handler;
    private final Function<JsonObject, T> resolver;
    private final Descriptor descriptor;

    public CommandDefinition(String event,
                             CommandHandler<T, V> handler,
                             Function<JsonObject, T> resolver) {
        this(event, handler, resolver, null);
    }

    public CommandDefinition(String event,
                             CommandHandler<T, V> handler,
                             Function<JsonObject, T> resolver,
                             Descriptor descriptor) {
        if (event == null || event.trim().isEmpty()) {
            throw new IllegalArgumentException("event is empty");
        }
        if (handler == null) {
            throw new IllegalArgumentException("handler is null");
        }
        if (resolver == null) {
            throw new IllegalArgumentException("resolver is null");
        }
        this.event = event;
        this.handler = handler;
        this.resolver = resolver;
        this.descriptor = descriptor == null ? Descriptor.fallback(event) : descriptor;
    }

    public static <T, V> Builder<T, V> builder(String event) {
        return new Builder<>(event);
    }

    public String getEvent() {
        return event;
    }

    public CommandHandler<T, V> getHandler() {
        return handler;
    }

    public Function<JsonObject, T> getResolver() {
        return resolver;
    }

    public Descriptor getDescriptor() {
        return descriptor;
    }

    public static final class Descriptor {
        private final String event;
        private final String summary;
        private final List<String> suggestedPhases;
        private final boolean safeForScenario;

        private Descriptor(String event,
                           String summary,
                           List<String> suggestedPhases,
                           boolean safeForScenario) {
            if (event == null || event.trim().isEmpty()) {
                throw new IllegalArgumentException("event is empty");
            }
            this.event = event;
            this.summary = summary == null ? "" : summary;
            this.suggestedPhases = immutableCopy(suggestedPhases);
            this.safeForScenario = safeForScenario;
        }

        public static Descriptor simple(String event,
                                        String summary,
                                        List<String> suggestedPhases,
                                        boolean safeForScenario) {
            return new Descriptor(event, summary, suggestedPhases, safeForScenario);
        }

        public static Descriptor fallback(String event) {
            return new Descriptor(event, "", Collections.emptyList(), false);
        }

        public String getEvent() {
            return event;
        }

        public String getSummary() {
            return summary;
        }

        public List<String> getSuggestedPhases() {
            return suggestedPhases;
        }

        public boolean isSafeForScenario() {
            return safeForScenario;
        }

        private static List<String> immutableCopy(List<String> values) {
            if (values == null || values.isEmpty()) {
                return Collections.emptyList();
            }
            return Collections.unmodifiableList(new ArrayList<>(values));
        }
    }

    public static final class Builder<T, V> {
        private final String event;
        private CommandHandler<T, V> handler;
        private Function<JsonObject, T> resolver;
        private String summary = "";
        private List<String> suggestedPhases = Collections.emptyList();
        private boolean safeForScenario = false;

        private Builder(String event) {
            if (event == null || event.trim().isEmpty()) {
                throw new IllegalArgumentException("event is empty");
            }
            this.event = event;
        }

        public Builder<T, V> handler(CommandHandler<T, V> handler) {
            this.handler = handler;
            return this;
        }

        public Builder<T, V> resolver(Function<JsonObject, T> resolver) {
            this.resolver = resolver;
            return this;
        }

        public Builder<T, V> summary(String summary) {
            this.summary = summary == null ? "" : summary;
            return this;
        }

        public Builder<T, V> suggestedPhases(String... phases) {
            this.suggestedPhases = phases == null || phases.length == 0
                    ? Collections.emptyList()
                    : Arrays.asList(phases);
            return this;
        }

        public Builder<T, V> safeForScenario(boolean safeForScenario) {
            this.safeForScenario = safeForScenario;
            return this;
        }

        public CommandDefinition<T, V> build() {
            return new CommandDefinition<>(
                    event,
                    handler,
                    resolver,
                    new Descriptor(event, summary, suggestedPhases, safeForScenario)
            );
        }
    }
}
