package com.xa.mass.workersimulator;

import static org.assertj.core.api.Assertions.*;

import com.xa.mass.transport.client.TextMessageReconnectPolicy;
import com.xa.mass.workerdelivery.json.Jsons;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkerSimulatorPopulationTest {
    @TempDir Path temp;

    @Test void seedIsOptionalButExplicitValuesMustBeSigned64BitIntegers() {
        assertThat(parse("{\"workerGroups\":{}}").seed()).isZero();
        for (long seed : new long[]{0, 712, -1, Long.MIN_VALUE, Long.MAX_VALUE}) {
            assertThat(parse("{\"seed\":" + seed + ",\"workerGroups\":{}}").seed()).isEqualTo(seed);
        }
        for (String invalid : List.of("null", "true", "\"712\"", "1.5", "[]", "{}",
                "9223372036854775808", "-9223372036854775809")) {
            assertThatThrownBy(() -> parse("{\"seed\":" + invalid + ",\"workerGroups\":{}}"))
                    .as(invalid).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> parse("{\"seed\":0,\"seed\":712,\"workerGroups\":{}}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parse("{\"workerGroups\":{\"demo-sim\":{\"seed\":712}}}"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void goldenVectorsFixTupleEncodingUnsignedReductionAndOperatorSemantics() {
        // Independent SHA-256 vectors: 8-byte seed, length-prefixed UTF-8 strings, 8-byte offset.
        golden(0, "demo-sim", "value", 2810961919410003305L, "C", "-8");
        golden(712, "demo-sim", "value", 3696166868763344923L, "B", "0");
        golden(-1, "demo-sim", "value", 1222723576287341531L, "C", "1");
        golden(Long.MIN_VALUE, "群组", "网络.type", 2382990040569715154L, "A", "8");
        golden(Long.MAX_VALUE, "demo-sim", "value", 1137578359702476610L, "B", "6");
        golden(0, "ab", "c", 4326363560218589952L, "B", "3");
        golden(0, "a", "bc", 633493394578389221L, "C", "-8");
    }

    private void golden(long seed, String group, String property, long wide, String choice, String narrow) {
        assertThat(group(group, 101, Map.of(property, Map.of("$range", List.of(0L, Long.MAX_VALUE - 1))))
                .generateProperties(seed, 100)).containsEntry(property, Long.toString(wide));
        assertThat(group(group, 101, Map.of(property, Map.of("$choice", List.of("A", "B", "C"))))
                .generateProperties(seed, 100)).containsEntry(property, choice);
        assertThat(group(group, 101, Map.of(property, Map.of("$range", List.of(-10, 10))))
                .generateProperties(seed, 100)).containsEntry(property, narrow);
        assertThat(group(group, 101, Map.of(property, Map.of("$index", List.of(10, 2))))
                .generateProperties(seed, 100)).containsEntry(property, "208");
    }

    @Test void populationIsIndependentOfIterationOrderOtherPropertiesGroupsAndCount() {
        var template = new LinkedHashMap<String, Object>();
        template.put("country", Map.of("$choice", List.of("CN", "US", "GB")));
        template.put("app", Map.of("$choice", List.of("IG", "TG", "WA")));
        template.put("battery", Map.of("$range", List.of(0, 1000)));
        template.put("phone", Map.of("$index", List.of(861700000001L, 1)));
        var original = group("demo-sim", 101, template);
        var baseline = population(original, 712);
        for (int ordinal = 101; ordinal >= 1; ordinal--) {
            assertThat(original.generateProperties(712, ordinal)).isEqualTo(baseline.get(ordinal - 1));
        }
        var reversed = new LinkedHashMap<String, Object>();
        var entries = new ArrayList<>(template.entrySet());
        Collections.reverse(entries);
        entries.forEach(entry -> reversed.put(entry.getKey(), entry.getValue()));
        assertThat(population(group("demo-sim", 150, reversed), 712).subList(0, 101)).isEqualTo(baseline);
        reversed.put("added", Map.of("$range", List.of(-1000, 1000)));
        reversed.put("battery", "changed independently");
        var changed = group("demo-sim", 101, reversed);
        for (int ordinal = 1; ordinal <= 101; ordinal++) {
            var actual = changed.generateProperties(712, ordinal);
            for (String key : List.of("country", "app", "phone")) {
                assertThat(actual.get(key)).isEqualTo(baseline.get(ordinal - 1).get(key));
            }
        }
        var groupInput = Map.of("events", List.of("test"), "count", 101, "propertiesTemplate", template);
        var first = parse("{\"seed\":712,\"workerGroups\":" + Jsons.toJson(Map.of("demo-sim", groupInput)) + "}");
        var secondGroups = new LinkedHashMap<String, Object>();
        secondGroups.put("another", groupInput); secondGroups.put("demo-sim", groupInput);
        var second = parse("{\"seed\":712,\"workerGroups\":" + Jsons.toJson(secondGroups) + "}");
        assertThat(population(second.workerGroups().get(1), second.seed()))
                .isEqualTo(population(first.workerGroups().get(0), first.seed()));
        assertThat(population(original, 713)).isNotEqualTo(baseline);
        assertThat(population(group("another", 101, template), 712)).isNotEqualTo(baseline);
        // Fixed sample breaks the old three-pair lockstep; this is not an exact quota assertion.
        assertThat(baseline.stream().map(p -> p.get("country") + "/" + p.get("app")).distinct().toList())
                .hasSizeGreaterThan(3);
    }

    @Test void emptyStringsRepeatedChoicesSingletonsAndFullWidthRangesRemainValid() {
        var value = group("demo-sim", 101, Map.of(
                "empty", "", "single", Map.of("$choice", List.of("")),
                "fixed", Map.of("$range", List.of(Long.MIN_VALUE, Long.MIN_VALUE)),
                "negative", Map.of("$range", List.of(Long.MIN_VALUE, -2L)),
                "country", Map.of("$choice", List.of("CN", "CN", "US")),
                "app", Map.of("$choice", List.of("CN", "CN", "US"))));
        var population = population(value, 0);
        assertThat(population).allSatisfy(p -> {
            assertThat(p).containsEntry("empty", "").containsEntry("single", "")
                    .containsEntry("fixed", Long.toString(Long.MIN_VALUE));
            assertThat(Long.parseLong(p.get("negative"))).isBetween(Long.MIN_VALUE, -2L);
        });
        assertThat(population.get(1)).containsEntry("country", "CN"); // slot 1 must not be deduplicated
        assertThat(population.get(59)).containsEntry("country", "US");
        assertThat(population.stream().anyMatch(p -> !p.get("country").equals(p.get("app")))).isTrue();
    }

    @Test void seedOnlyAppliesOnGenerationAndResetPreservesCoordinates() {
        var config = group("g", 101, Map.of("value", Map.of("$range", List.of(0, 1000000))));
        var lab = new WorkerSimulatorLab(temp.resolve("data/scenario-workers").toString());
        var initial = lab.prepare(List.of(config), 712, ignored -> {}).get(0).workers();
        assertThat(initial.get(100).labWorkerKey()).isEqualTo("workers-001.jsonl:1");
        assertThat(lab.prepare(List.of(config), 713, ignored -> {}).get(0).workers())
                .extracting(worker -> worker.workerProperties())
                .containsExactlyElementsOf(initial.stream().map(worker -> worker.workerProperties()).toList());
        var reset = new WorkerSimulatorGroupConfig(config.workerGroupId(), config.events(), config.count(),
                config.propertiesTemplate(), true, config.requestTimeout(), config.reconnectPolicy());
        var regenerated = lab.prepare(List.of(reset), 713, ignored -> {}).get(0).workers();
        assertThat(regenerated).extracting(worker -> worker.labWorkerKey())
                .containsExactlyElementsOf(initial.stream().map(worker -> worker.labWorkerKey()).toList());
        assertThat(regenerated.get(100).workerProperties()).containsAllEntriesOf(config.generateProperties(713, 101));
        assertThat(regenerated.stream().map(worker -> worker.workerProperties()).toList())
                .isNotEqualTo(initial.stream().map(worker -> worker.workerProperties()).toList());
    }

    private static WorkerSimulatorConfig parse(String json) {
        return WorkerSimulatorJsonParser.parse(json, Path.of("."));
    }

    private static WorkerSimulatorGroupConfig group(String id, int count, Map<String, Object> template) {
        return new WorkerSimulatorGroupConfig(id, List.of("test"), count, template, false,
                Duration.ofSeconds(1), TextMessageReconnectPolicy.defaults());
    }

    private static List<Map<String, String>> population(WorkerSimulatorGroupConfig group, long seed) {
        return IntStream.rangeClosed(1, group.count()).mapToObj(i -> group.generateProperties(seed, i)).toList();
    }
}
