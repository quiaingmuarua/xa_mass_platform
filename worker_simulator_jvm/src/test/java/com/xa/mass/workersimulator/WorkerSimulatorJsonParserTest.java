package com.xa.mass.workersimulator;

import static org.assertj.core.api.Assertions.*;
import com.xa.mass.workerdelivery.json.Jsons;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;

class WorkerSimulatorJsonParserTest {
    private static WorkerSimulatorConfig parse(String groups) {
        return WorkerSimulatorJsonParser.parse("""
                {"runtimeApiBaseUrl":"http://127.0.0.1:18082","sandboxRoot":"data/scenario-workers",
                 "controlPort":0,"workerGroups":%s}
                """.formatted(groups), Path.of(".").toAbsolutePath());
    }

    @Test void groupsAndEventsKeepTheirOrderAndResourceDefaults() {
        var configs = parse("""
                {"phone":{"events":["phone.first","phone.second"],"count":1,"propertiesTemplate":{}},
                 "strings":{"events":["string.first"],"count":2,"propertiesTemplate":{},
                  "requestTimeoutMillis":2000,
                  "reconnectPolicy":{"maxUnstableAttempts":6,"reconnectIntervalMillis":300,"stableConnectionDurationMillis":5000}}}
                """).workerGroups();
        assertThat(configs).extracting(WorkerSimulatorGroupConfig::workerGroupId).containsExactly("phone", "strings");
        assertThat(configs.get(0).events()).containsExactly("phone.first", "phone.second");
        assertThat(configs.get(0).requestTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(configs.get(0).reconnectPolicy().maxUnstableAttempts()).isEqualTo(20);
        assertThat(configs.get(1).requestTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(configs.get(1).reconnectPolicy().reconnectInterval()).isEqualTo(Duration.ofMillis(300));
        assertThat(parse("{}").workerGroups()).isEmpty();
    }

    @Test void defaultsAreLocalAndUnknownGroupsMustChooseEvents() {
        var group = parse("{\"demo-sim\":{\"count\":0,\"propertiesTemplate\":{}}}").workerGroups().get(0);
        assertThat(group.events()).contains("extension.worker.sms.listen.start", "extension.worker.message.send")
                .doesNotContain(WorkerSimulatorExecutionWitnesses.EVENT, "extension.worker.lab.fail");
        assertThatThrownBy(() -> parse("{\"unknown\":{\"count\":1,\"propertiesTemplate\":{}}}"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no default");
        assertThatThrownBy(() -> parse("{\"unknown\":{\"events\":[\"one\"]}}"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("propertiesTemplate");
        var custom = parse("{\"unknown\":{\"events\":[\"one\"],\"propertiesTemplate\":{}}}").workerGroups().get(0);
        assertThat(custom.count()).isEqualTo(50);
        assertThat(custom.generateProperties(0, 1)).isEmpty();
    }

    @Test void onlyGroupSelectionIsRequiredAndDefaultsAreResolvedBeforeUse() {
        Path directory = Path.of("configuration").toAbsolutePath();
        var config = WorkerSimulatorJsonParser.parse("""
                {"workerGroups":{"scenario-phone-number-workers":{},"scenario-string-utils-workers":{},"demo-sim":{}}}
                """, directory);
        assertThat(config.runtimeApiBaseUrl()).isEqualTo(URI.create("http://127.0.0.1:18082"));
        assertThat(config.sandboxRoot()).isEqualTo(directory.resolve("data/scenario-workers"));
        assertThat(config.controlPort()).isEqualTo(18086);
        assertThat(config.seed()).isZero();
        assertThat(config.startupPlan().startAll()).isTrue();
        assertThat(config.workerGroups()).extracting(WorkerSimulatorGroupConfig::count).containsExactly(50, 50, 60);
        assertThat(config.workerGroups()).allSatisfy(group -> {
            assertThat(group.newEnvironment()).isFalse();
            assertThat(group.requestTimeout()).isEqualTo(Duration.ofSeconds(10));
            assertThat(group.reconnectPolicy().maxUnstableAttempts()).isEqualTo(20);
            assertThat(group.reconnectPolicy().reconnectInterval()).isEqualTo(Duration.ofMillis(500));
            assertThat(group.reconnectPolicy().stableConnectionDuration()).isEqualTo(Duration.ofSeconds(10));
            assertThat(group.events()).isNotEmpty();
        });
        assertThat(config.workerGroups().get(0).generateProperties(0, 50)).containsExactlyInAnyOrderEntriesOf(Map.of(
                "runtime", "java", "capability", "libphonenumber", "region", "local", "labSlot", "50", "convergenceSlot", "A"));
        assertThat(config.workerGroups().get(1).generateProperties(0, 1)).containsEntry("capability", "string-utils");
        var sim = config.workerGroups().get(2);
        assertThat(sim.generateProperties(0, 1)).containsEntry("phone", "861700000001").containsEntry("country", "CN")
                .containsEntry("messaging.enabled", "true");
        assertThat(sim.generateProperties(0, 2)).containsEntry("phone", "861700000002").containsEntry("country", "US");
        assertThat(sim.generateProperties(0, 60)).containsEntry("phone", "861700000060").containsEntry("country", "GB");
    }

    @Test void explicitTemplateReplacesDefaultsIncludingEmptyMap() {
        var replacement = parse("""
                {"scenario-string-utils-workers":{"propertiesTemplate":{"only":"custom"}}}
                """).workerGroups().get(0);
        assertThat(replacement.generateProperties(0, 1)).containsExactly(Map.entry("only", "custom"));
        var empty = parse("{\"scenario-string-utils-workers\":{\"propertiesTemplate\":{}}}").workerGroups().get(0);
        assertThat(empty.generateProperties(0, 1)).isEmpty();
        assertThat(parse("{\"scenario-string-utils-workers\":{}}").workerGroups().get(0).generateProperties(0, 1))
                .containsEntry("runtime", "java").containsEntry("labSlot", "1");
    }

    @Test void defaultPropertiesDoNotAdvertiseAnExplicitlyExcludedMessagesCapability() {
        var sms = parse("""
                {"demo-sim":{"events":["extension.worker.sms.listen.start","extension.worker.sms.listen.cancel"]}}
                """).workerGroups().get(0);
        assertThat(sms.generateProperties(0, 1)).containsEntry("phone", "861700000001")
                .containsEntry("country", "CN").doesNotContainKey("messaging.enabled");
        var messages = parse("""
                {"demo-sim":{"events":["extension.worker.message.send"]}}
                """).workerGroups().get(0);
        assertThat(messages.generateProperties(0, 1)).containsEntry("messaging.enabled", "true");
    }

    @Test void reconnectFieldsCanBeOverriddenIndependently() {
        var empty = parse("{\"demo-sim\":{\"reconnectPolicy\":{}}}").workerGroups().get(0).reconnectPolicy();
        assertThat(empty.maxUnstableAttempts()).isEqualTo(20);
        assertThat(empty.reconnectInterval()).isEqualTo(Duration.ofMillis(500));
        assertThat(empty.stableConnectionDuration()).isEqualTo(Duration.ofSeconds(10));
        for (String field : List.of("maxUnstableAttempts", "reconnectIntervalMillis", "stableConnectionDurationMillis")) {
            var policy = parse("{\"demo-sim\":{\"reconnectPolicy\":{\"" + field + "\":42}}}")
                    .workerGroups().get(0).reconnectPolicy();
            assertThat(policy.maxUnstableAttempts()).isEqualTo(field.equals("maxUnstableAttempts") ? 42 : 20);
            assertThat(policy.reconnectInterval().toMillis()).isEqualTo(field.equals("reconnectIntervalMillis") ? 42 : 500);
            assertThat(policy.stableConnectionDuration().toMillis()).isEqualTo(field.equals("stableConnectionDurationMillis") ? 42 : 10000);
        }
    }

    @Test void explicitNullAndInvalidValuesNeverFallBackToDefaults() {
        for (String field : List.of("runtimeApiBaseUrl", "sandboxRoot", "controlPort", "seed", "workerGroups", "startupPlan")) {
            String encoded = field.equals("workerGroups") ? "{\"workerGroups\":null}"
                    : "{\"workerGroups\":{},\"" + field + "\":null}";
            assertThatThrownBy(() -> WorkerSimulatorJsonParser.parse(encoded, Path.of(".")))
                    .as(field).isInstanceOf(IllegalArgumentException.class);
        }
        for (String field : List.of("events", "count", "propertiesTemplate", "newEnvironment", "requestTimeoutMillis", "reconnectPolicy")) {
            assertThatThrownBy(() -> parse("{\"demo-sim\":{\"" + field + "\":null}}"))
                    .as(field).isInstanceOf(IllegalArgumentException.class);
        }
        for (String field : List.of("maxUnstableAttempts", "reconnectIntervalMillis", "stableConnectionDurationMillis")) {
            for (String value : List.of("null", "0", "-1", "true", "\"20\"", "1.5")) {
                assertThatThrownBy(() -> parse("{\"demo-sim\":{\"reconnectPolicy\":{\"" + field + "\":" + value + "}}}"))
                        .as(field + "=" + value).isInstanceOf(IllegalArgumentException.class);
            }
        }
    }

    @Test void rejectsLegacyShapesMissingFieldsUnknownFieldsAndDuplicateKeys() {
        for (String group : List.of(
                "{\"eventCodes\":[\"event.one\"],\"count\":1,\"propertiesTemplate\":{}}",
                "{\"events\":[\"one\"],\"count\":1}",
                "{\"events\":[\"one\",\"one\"],\"count\":1,\"propertiesTemplate\":{}}",
                "{\"events\":[\"one\"],\"count\":1,\"count\":2,\"propertiesTemplate\":{}}",
                "{\"events\":[\"one\"],\"count\":1,\"propertiesTemplate\":{\"x\":\"a\",\"x\":\"b\"}}",
                "{\"events\":[\"one\"],\"count\":1,\"propertiesTemplate\":{},\"newEnvironment\":\"false\"}",
                "{\"events\":[\"one\"],\"count\":1,\"propertiesTemplate\":{},\"workers\":[]}",
                "{\"events\":[\"one\"],\"count\":1,\"propertiesTemplate\":{},\"reconnectPolicy\":{\"unknown\":1}}",
                "{\"events\":[\"one\"],\"count\":15001,\"propertiesTemplate\":{}}")) {
            assertThatThrownBy(() -> parse("{\"g\":" + group + "}")).isInstanceOf(IllegalArgumentException.class);
        }
        for (String encoded : List.of("{}", "{\"demo-sim\":{}}", "[]",
                "{\"runtimeApiBaseUrl\":\"http://localhost\",\"sandboxRoot\":\"data/scenario-workers\",\"controlPort\":0,\"controlPort\":1,\"workerGroups\":{}}")) {
            assertThatThrownBy(() -> WorkerSimulatorJsonParser.parse(encoded, Path.of("."))).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void templatesAreCapturedAndEvaluateOnlyToFlatStrings() {
        var choices = new ArrayList<>(List.of("CN", "CN", "US"));
        var template = new LinkedHashMap<String, Object>();
        template.put("i", Map.of("$index", List.of(10, 2)));
        template.put("battery", Map.of("$range", List.of(80, 82)));
        template.put("country", Map.of("$choice", choices));
        template.put("empty", "");
        var raw = new LinkedHashMap<String, Object>(Map.of("events", List.of("test"), "count", 101, "propertiesTemplate", template));
        var group = WorkerSimulatorJsonParser.parseGroups(Map.of("g", raw)).get(0);
        choices.clear(); template.clear(); raw.clear();
        assertThat(group.generateProperties(0, 1)).containsEntry("i", "10").containsEntry("country", "CN");
        assertThat(group.generateProperties(0, 2)).containsEntry("country", "CN");
        assertThat(group.generateProperties(0, 3)).containsEntry("country", "US");
        assertThat(group.generateProperties(0, 101)).containsEntry("i", "210").containsEntry("battery", "80").containsEntry("empty", "");
        assertThatThrownBy(() -> group.propertiesTemplate().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void invalidTemplatesAndOverflowFailWithoutCoercion() {
        for (Object value : List.of(1, true, List.of("x"), Map.of("nested", "x"),
                Map.of("$choice", List.of()), Map.of("$choice", List.of(1)),
                Map.of("$index", List.of(1, 0)), Map.of("$range", List.of(4, 3)),
                Map.of("$range", List.of(Long.MIN_VALUE, Long.MAX_VALUE)),
                Map.of("$index", List.of(1)), Map.of("$unknown", List.of(1, 2)))) {
            assertThatThrownBy(() -> parse(Jsons.toJson(Map.of("g", Map.of(
                    "events", List.of("test"), "count", 3, "propertiesTemplate", Map.of("x", value))))))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        var overflow = parse("{\"g\":{\"events\":[\"test\"],\"count\":2,\"propertiesTemplate\":{\"x\":{\"$index\":[9223372036854775807,1]}}}}").workerGroups().get(0);
        assertThatThrownBy(() -> overflow.generateProperties(0, 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parse("{\"g\":{\"events\":[\"test\"],\"count\":1,\"propertiesTemplate\":{\"labInventoryKey\":\"x\"}}}"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
