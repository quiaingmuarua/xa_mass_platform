package com.xa.mass.scenarioworkers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ScenarioWorkersJsonParserTest {

    @Test
    void parsesOrderedBundlesAndExplicitWorkersWithDefaults() {
        List<ScenarioWorkerBundleConfig> configs =
                ScenarioWorkersJsonParser.parse("""
                        {
                          "phone-number": {
                            "type": "PHONE_NUMBER",
                            "endpointManagerId": "scenario-websocket",
                            "websocketUri": "ws://127.0.0.1:18083/api/v1/worker-delivery/websocket",
                            "workerGroupId": "phone-workers",
                            "workers": [{
                              "workerId": "phone-worker-001",
                              "workerProperties": {"region": "local"},
                              "indexedPropertyUpdates": {
                                "index.worker.region": "local"
                              }
                            }]
                          },
                          "string-utils": {
                            "type": "STRING_UTILS",
                            "endpointManagerId": "scenario-websocket",
                            "websocketUri": "wss://worker.example.test/connect",
                            "workerGroupId": "string-workers",
                            "requestTimeoutMillis": 20,
                            "reconnectIntervalMillis": 30,
                            "connectTimeoutMillis": 40,
                            "workers": [{"workerId": "string-worker-001"}]
                          }
                        }
                        """);

        assertThat(configs)
                .extracting(ScenarioWorkerBundleConfig::bundleId)
                .containsExactly("phone-number", "string-utils");
        ScenarioWorkerBundleConfig phone = configs.get(0);
        assertThat(phone.requestTimeout())
                .isEqualTo(Duration.ofSeconds(10));
        assertThat(phone.reconnectInterval())
                .isEqualTo(Duration.ofMillis(250));
        assertThat(phone.connectTimeout())
                .isEqualTo(Duration.ofSeconds(15));
        assertThat(phone.workers().get(0).workerProperties())
                .containsEntry("region", "local");
        assertThat(phone.workers().get(0).indexedPropertyUpdates())
                .containsEntry("index.worker.region", "local");

        ScenarioWorkerBundleConfig strings = configs.get(1);
        assertThat(strings.requestTimeout()).isEqualTo(Duration.ofMillis(20));
        assertThat(strings.reconnectInterval()).isEqualTo(Duration.ofMillis(30));
        assertThat(strings.connectTimeout()).isEqualTo(Duration.ofMillis(40));
        assertThat(strings.workers().get(0).workerProperties()).isEmpty();
        assertThat(strings.workers().get(0).indexedPropertyUpdates()).isEmpty();
    }

    @Test
    void acceptsEmptyTopLevelObject() {
        assertThat(ScenarioWorkersJsonParser.parse("{}"))
                .isEmpty();
    }

    @Test
    void rejectsUnknownFieldsLegacyGenerationAndInvalidBounds() {
        assertInvalid(validConfig().replace(
                "\"workers\":",
                "\"unexpected\":true,\"workers\":"
        ));
        assertInvalid(validConfig().replace(
                "\"workers\":",
                "\"workerIdPrefix\":\"worker-\",\"workers\":"
        ));
        assertInvalid(validConfig().replace(
                "\"connectTimeoutMillis\":15",
                "\"connectTimeoutMillis\":0"
        ));
        assertInvalid(validConfig().replace(
                "\"PHONE_NUMBER\"",
                "\"UNKNOWN\""
        ));
        assertInvalid(validConfig().replace(
                "ws://127.0.0.1:18083/connect",
                "http://127.0.0.1:18083/connect"
        ));
        assertInvalid(validConfig().replace(
                "[{\n      \"workerId\":\"worker-1\",\n"
                        + "      \"indexedPropertyUpdates\":"
                        + "{\"index.worker.region\":\"local\"}\n    }]",
                "[]"
        ));

        String tooManyWorkers = IntStream.rangeClosed(1, 101)
                .mapToObj(index -> "{\"workerId\":\"worker-"
                        + index
                        + "\"}")
                .collect(Collectors.joining(",", "[", "]"));
        assertInvalid(validConfig().replace(
                "[{\n      \"workerId\":\"worker-1\",\n"
                        + "      \"indexedPropertyUpdates\":"
                        + "{\"index.worker.region\":\"local\"}\n    }]",
                tooManyWorkers
        ));
    }

    @Test
    void rejectsDuplicateWorkerAndWorkerGroupIdentities() {
        assertInvalid(twoBundleConfig("group-two", "worker-1"));
        assertInvalid(twoBundleConfig("group-one", "worker-2"));
    }

    @Test
    void rejectsMalformedJsonAndInvalidIndexField() {
        assertInvalid("{bad-json");
        assertInvalid(validConfig().replace(
                "index.worker.region",
                "worker.region"
        ));
    }

    private static void assertInvalid(String value) {
        assertThatThrownBy(() -> ScenarioWorkersJsonParser.parse(value))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static String validConfig() {
        return """
                {
                  "phone": {
                    "type":"PHONE_NUMBER",
                    "endpointManagerId":"adapter",
                    "websocketUri":"ws://127.0.0.1:18083/connect",
                    "workerGroupId":"group",
                    "connectTimeoutMillis":15,
                    "workers":[{
                      "workerId":"worker-1",
                      "indexedPropertyUpdates":{"index.worker.region":"local"}
                    }]
                  }
                }
                """;
    }

    private static String twoBundleConfig(
            String secondGroupId,
            String secondWorkerId
    ) {
        return """
                {
                  "one": {
                    "type":"PHONE_NUMBER",
                    "endpointManagerId":"adapter",
                    "websocketUri":"ws://127.0.0.1:18083/connect",
                    "workerGroupId":"group-one",
                    "workers":[{"workerId":"worker-1"}]
                  },
                  "two": {
                    "type":"STRING_UTILS",
                    "endpointManagerId":"adapter",
                    "websocketUri":"ws://127.0.0.1:18083/connect",
                    "workerGroupId":"%s",
                    "workers":[{"workerId":"%s"}]
                  }
                }
                """.formatted(secondGroupId, secondWorkerId);
    }
}
