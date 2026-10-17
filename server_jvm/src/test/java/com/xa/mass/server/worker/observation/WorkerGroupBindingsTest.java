package com.xa.mass.server.worker.observation;

import com.xa.mass.server.assembly.runtime.FixedWindowBindingsProperties;
import com.xa.mass.server.assembly.runtime.FixedWindowBindingsProperties.*;
import com.xa.mass.server.assembly.runtime.ServerWorkerAssemblyProperties;
import com.xa.mass.workermatching.MatchingGroup;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ByteArrayResource;
import static org.assertj.core.api.Assertions.*;

class WorkerGroupBindingsTest {
    private final Pool pool = new Pool("custom.take", "observedAt", "observedCount", 200, 10);
    private final Feedback feedback = new Feedback("custom", List.of("event.one", "event.two"));

    @Test void namesAndGroupOverridesAreSharedWhilePoolAndFeedbackRemainIndependent() {
        var raw = Map.of("both", new MatchingGroup(Set.of("any"), Set.of("worker.any")));
        var config = new FixedWindowBindingsProperties(Map.of("custom", pool, "unused", new Pool("unused.take", "t", "c", 100, 1)),
                Map.of("observe", feedback), Map.of(
                "both", new Group(Map.of("custom", new Limits(100L, 2L)), List.of("observe")),
                "pool", new Group(Map.of("custom", new Limits(null, null)), List.of()),
                "feedback", new Group(Map.of(), List.of("observe"))));
        var bindings = new WorkerGroupBindings(raw, config, List.of());
        assertThat(bindings.matchingGroups().get("both").functions()).containsExactlyInAnyOrder("custom.take", "worker.any");
        assertThat(bindings.matchingGroups().get("feedback").pools()).isEmpty();
        var definition = bindings.definitions().stream().filter(value -> value.poolName().equals("custom")).findFirst().orElseThrow();
        assertThat(definition.limitsByGroup()).containsOnlyKeys("both", "pool");
        assertThat(definition.limitsByGroup().get("both").windowMillis()).isEqualTo(100);
        assertThat(bindings.group("both").feedbacks().getFirst().windowMillis()).isEqualTo(100);
        assertThat(bindings.group("feedback").feedbacks().getFirst().windowMillis()).isEqualTo(200);
        assertThat(bindings.group("pool").feedbacks()).isEmpty();
        assertThat(bindings.group("missing").fixedWindowPools()).isEmpty();
        var selected = bindings.projections().getFirst().projectionsByGroup();
        assertThat(selected).containsOnlyKeys("both", "feedback");
        assertThat(selected.get("both").getFirst().project().apply(Map.of(), List.of(99L, 100L)))
                .containsEntry("observedCount", 1L);
        assertThat(selected.get("feedback").getFirst().project().apply(Map.of(), List.of(99L, 100L)))
                .containsEntry("observedCount", 2L);
        assertThat(bindings.requiredEvents().get("pool")).isEmpty();
        assertThat(bindings.requiredEvents().get("feedback")).containsExactly("event.one", "event.two");
        assertThatThrownBy(() -> bindings.matchingGroups().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void unknownNamesDuplicateEnablementAndOverlappingFeedbackFailWithoutResources() {
        var group = new Group(Map.of("custom", new Limits(null, null)), List.of("observe"));
        assertThatThrownBy(() -> resolve(Map.of(), Map.of("bad", new Group(Map.of("missing", new Limits(null, null)), List.of()))))
                .hasMessageContaining("groups.bad.pools.missing");
        assertThatThrownBy(() -> resolve(Map.of(), Map.of("bad", new Group(Map.of(), List.of("missing")))))
                .hasMessageContaining("groups.bad.feedbacks").hasMessageContaining("unknown feedback");
        assertThatThrownBy(() -> resolve(Map.of("g", new MatchingGroup(Set.of("custom"), Set.of())), Map.of("g", group)))
                .hasMessageContaining("duplicate raw Matching");
        assertThatThrownBy(() -> resolve(Map.of("g", new MatchingGroup(Set.of(), Set.of("custom.take"))), Map.of("g", group)))
                .hasMessageContaining("duplicate raw Matching");
        assertThatThrownBy(() -> new Group(Map.of(), List.of("observe", "observe"))).hasMessageContaining("duplicate");
        assertThatThrownBy(() -> new WorkerGroupBindings(Map.of(), new FixedWindowBindingsProperties(Map.of("custom", pool),
                Map.of("observe", feedback, "other", feedback), Map.of("g", new Group(Map.of(), List.of("observe", "other")))), List.of()))
                .hasMessageContaining("overlapping Platform Properties");
        var second = new Pool("other.take", "otherAt", "otherCount", 100, 1);
        assertThatThrownBy(() -> new WorkerGroupBindings(Map.of(), new FixedWindowBindingsProperties(Map.of("custom", pool, "other", second),
                Map.of("observe", feedback, "other", new Feedback("other", List.of("event.one"))),
                Map.of("g", new Group(Map.of(), List.of("observe", "other")))), List.of()))
                .hasMessageContaining("duplicate worker.assigned selection");
    }

    private WorkerGroupBindings resolve(Map<String, MatchingGroup> raw, Map<String, Group> groups) {
        return new WorkerGroupBindings(raw, new FixedWindowBindingsProperties(Map.of("custom", pool), Map.of("observe", feedback), groups), List.of());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ServerWorkerAssemblyProperties.class)
    static class Binding {}

    @Test void actualYamlEmptyObjectBindsDefaultsAndStrictFieldsRemainStrict() {
        String yaml = """
                xa.mass.worker-assembly.fixed-window:
                  pools:
                    custom:
                      function-name: custom.take
                      timestamp-property: observedAt
                      count-property: observedCount
                      window-millis: 200
                      max-count: 10
                  feedbacks:
                    observe:
                      pool-name: custom
                      message-events: [event.one]
                  groups:
                    g:
                      pools:
                        custom: {}
                      feedbacks: [observe]
                """;
        var sources = yamlSources(yaml);
        var runner = new ApplicationContextRunner().withUserConfiguration(Binding.class).withInitializer(context ->
                sources.forEach(source -> context.getEnvironment().getPropertySources().addFirst(source)));
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            var config = context.getBean(ServerWorkerAssemblyProperties.class).fixedWindow();
            var bindings = new WorkerGroupBindings(Map.of(), config, List.of());
            assertThat(bindings.group("g").fixedWindowPools()).as("bound config: %s; YAML: %s", config, sources).hasSize(1);
            assertThat(bindings.group("g").fixedWindowPools().getFirst().windowMillis()).isEqualTo(200);
        });
        runner.withPropertyValues("xa.mass.worker-assembly.fixed-window.groups.g.pools.custom.extra=1")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("xa.mass.worker-assembly.fixed-window.groups.g.pools.custom.max-count=0")
                .run(context -> assertThat(context).hasFailed());
        new ApplicationContextRunner().withUserConfiguration(Binding.class).run(context -> {
            assertThat(context).hasNotFailed();
            var empty = new WorkerGroupBindings(Map.of(), context.getBean(ServerWorkerAssemblyProperties.class).fixedWindow(), List.of());
            assertThat(empty.definitions()).isEmpty(); assertThat(empty.projections()).isEmpty();
        });
    }

    private static List<org.springframework.core.env.PropertySource<?>> yamlSources(String yaml) {
        try { return new org.springframework.boot.env.YamlPropertySourceLoader().load("binding-yaml",
                new ByteArrayResource(yaml.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
    }
}
