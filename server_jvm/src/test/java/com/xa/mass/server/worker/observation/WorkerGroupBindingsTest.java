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
    private final Pool pool = new Pool("custom.take", "observedAt", "observedCount", 200, 10,
            new Feedback(List.of("event.one", "event.two")));
    private Group selected(Long window, Long count) { return new Group(Map.of("custom", new Limits(window, count))); }
    private WorkerGroupBindings resolve(Map<String, MatchingGroup> raw, Map<String, Group> groups) {
        return new WorkerGroupBindings(raw, new FixedWindowBindingsProperties(Map.of("custom", pool), groups), List.of());
    }

    @Test void oneGroupReferenceEnablesPoolQueryAndFeedbackWithTheSameResolvedWindow() {
        var config = new FixedWindowBindingsProperties(Map.of("custom", pool,
                "unused", new Pool("unused.take", "t", "c", 100, 1, new Feedback(List.of("unused.event")))),
                Map.of("a", selected(100L, 2L), "c", selected(null, null)));
        var bindings = new WorkerGroupBindings(Map.of("b", new MatchingGroup(Set.of("any"), Set.of("worker.any"))), config, List.of());
        assertThat(bindings.matchingGroups().get("a").functions()).containsExactly("custom.take");
        assertThat(bindings.matchingGroups().get("b").pools()).containsExactly("any");
        var definition = bindings.definitions().stream().filter(v -> v.poolName().equals("custom")).findFirst().orElseThrow();
        assertThat(definition.limitsByGroup()).containsOnlyKeys("a", "c");
        assertThat(definition.limitsByGroup().get("a").windowMillis()).isEqualTo(100);
        assertThat(bindings.group("a").fixedWindowPools().getFirst().maxCount()).isEqualTo(2);
        assertThat(bindings.group("a").fixedWindowPools().getFirst().feedback().messageEvents()).containsExactly("event.one", "event.two");
        assertThat(bindings.group("b").fixedWindowPools()).isEmpty();
        assertThat(bindings.group("missing").fixedWindowPools()).isEmpty();
        var selected = bindings.projections().getFirst().projectionsByGroup();
        assertThat(selected).containsOnlyKeys("a", "c");
        assertThat(selected.get("a")).hasSize(2);
        assertThat(selected.get("a").getFirst().project().apply(Map.of(), List.of(99L, 100L))).containsEntry("observedCount", 1L);
        assertThat(selected.get("c").getFirst().project().apply(Map.of(), List.of(99L, 100L))).containsEntry("observedCount", 2L);
        assertThat(bindings.requiredEvents()).containsOnlyKeys("a", "c");
        assertThat(bindings.definitions().stream().filter(v -> v.poolName().equals("unused")).findFirst().orElseThrow().limitsByGroup()).isEmpty();
        assertThatThrownBy(() -> bindings.matchingGroups().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void unknownNamesDuplicateEnablementAndOverlappingFeedbackFailWithoutResources() {
        assertThatThrownBy(() -> resolve(Map.of(), Map.of("bad", new Group(Map.of("missing", new Limits(null, null))))))
                .hasMessageContaining("groups.bad.pools.missing");
        assertThatThrownBy(() -> resolve(Map.of("g", new MatchingGroup(Set.of("custom"), Set.of())), Map.of("g", selected(null, null))))
                .hasMessageContaining("duplicate raw Matching");
        assertThatThrownBy(() -> new Feedback(List.of("one", "one"))).hasMessageContaining("duplicate");
        assertThatThrownBy(() -> new Feedback(List.of())).hasMessageContaining("must not be empty");
        assertThatThrownBy(() -> new Pool("take", "at", "count", 100, 2, null)).hasMessageContaining("feedback is required");
        for (var second : List.of(
                new Pool("other.take", "observedAt", "otherCount", 200, 10, new Feedback(List.of("different"))),
                new Pool("other.take", "otherAt", "otherCount", 200, 10, new Feedback(List.of("event.one"))))) {
            assertThatThrownBy(() -> new WorkerGroupBindings(Map.of(), new FixedWindowBindingsProperties(Map.of("custom", pool, "other", second),
                    Map.of("g", new Group(Map.of("custom", new Limits(null, null), "other", new Limits(null, null))))), List.of()))
                    .hasMessageContaining("groups.g.pools");
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ServerWorkerAssemblyProperties.class)
    static class Binding {}

    @Test void actualYamlBindsDefaultsAndRejectsRetiredShapes() {
        String yaml = """
                xa.mass.worker-assembly.fixed-window:
                  pools:
                    custom:
                      function-name: custom.take
                      timestamp-property: observedAt
                      count-property: observedCount
                      window-millis: 200
                      max-count: 10
                      feedback:
                        message-events: [event.one]
                  groups:
                    g:
                      pools:
                        custom: {}
                """;
        var runner = yamlRunner(yaml);
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            var bindings = new WorkerGroupBindings(Map.of(), context.getBean(ServerWorkerAssemblyProperties.class).fixedWindow(), List.of());
            var window = bindings.group("g").fixedWindowPools().getFirst();
            assertThat(window.windowMillis()).isEqualTo(200);
            assertThat(window.feedback().observationEventName()).isEqualTo("worker.assigned");
        });
        for (String field : List.of("projects.p.pools.custom.max-count=1", "feedbacks.old.pool-name=custom",
                "groups.g.feedbacks[0]=old", "groups.g.pools.custom.extra=1", "groups.g.pools.custom.max-count=0"))
            runner.withPropertyValues("xa.mass.worker-assembly.fixed-window." + field).run(context -> assertThat(context).hasFailed());
        yamlRunner(yaml.replace("      feedback:\n        message-events: [event.one]\n", ""))
                .run(context -> assertThat(context).hasFailed());
        new ApplicationContextRunner().withUserConfiguration(Binding.class).run(context -> {
            assertThat(context).hasNotFailed();
            var empty = new WorkerGroupBindings(Map.of(), context.getBean(ServerWorkerAssemblyProperties.class).fixedWindow(), List.of());
            assertThat(empty.definitions()).isEmpty(); assertThat(empty.projections()).isEmpty();
        });
    }
    private static ApplicationContextRunner yamlRunner(String yaml) {
        try {
            var sources = new org.springframework.boot.env.YamlPropertySourceLoader().load("binding-yaml",
                    new ByteArrayResource(yaml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            return new ApplicationContextRunner().withUserConfiguration(Binding.class).withInitializer(context ->
                    sources.forEach(source -> context.getEnvironment().getPropertySources().addFirst(source)));
        } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
    }
}
