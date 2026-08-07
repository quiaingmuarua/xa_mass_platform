package com.xa.mass.scenarioworkers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScenarioWorkersJsonParserTest {

    @Test
    void parsesWorkerGroupsInDeclarationOrder() {
        List<ScenarioWorkerGroupConfig> configs =
                ScenarioWorkersJsonParser.parse("""
                        {
                          "phone-group": {
                            "eventCodes":["phone.first","phone.second"],
                            "workers":[{
                              "clientWorkerKey":"worker-1",
                              "workerProperties":{"region":"local"},
                              "indexedPropertyUpdates":{
                                "index.worker.region":"local"
                              }
                            }]
                          },
                          "string-group": {
                            "eventCodes":["string.first"],
                            "requestTimeoutMillis":2000,
                            "retryPolicy":{
                              "maxPrepareAttempts":4,
                              "prepareRetryIntervalMillis":200,
                              "maxReconnectAttempts":6,
                              "reconnectIntervalMillis":300,
                              "stableConnectionDurationMillis":5000
                            },
                            "connectTimeoutMillis":4000,
                            "workers":[{"clientWorkerKey":"worker-2"}]
                          }
                        }
                        """);

        assertThat(configs)
                .extracting(ScenarioWorkerGroupConfig::workerGroupId)
                .containsExactly("phone-group", "string-group");
        ScenarioWorkerGroupConfig phone = configs.get(0);
        assertThat(phone.eventCodes())
                .containsExactly("phone.first", "phone.second");
        assertThat(phone.requestTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(phone.retryPolicy().maxPrepareAttempts()).isEqualTo(10);
        assertThat(phone.retryPolicy().connectionPolicy()
                .reconnectInterval()).isEqualTo(Duration.ofMillis(500));
        assertThat(phone.connectTimeout()).isEqualTo(Duration.ofSeconds(15));
        assertThat(phone.workers().get(0).workerProperties())
                .containsEntry("region", "local");
        assertThat(phone.workers().get(0).indexedPropertyUpdates())
                .containsEntry("index.worker.region", "local");
        assertThat(phone.workers().get(0).sandboxDirectory()).isNull();

        ScenarioWorkerGroupConfig strings = configs.get(1);
        assertThat(strings.requestTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(strings.retryPolicy().maxPrepareAttempts()).isEqualTo(4);
        assertThat(strings.retryPolicy().prepareRetryInterval())
                .isEqualTo(Duration.ofMillis(200));
        assertThat(strings.retryPolicy().connectionPolicy()
                .reconnectInterval())
                .isEqualTo(Duration.ofMillis(300));
        assertThat(strings.retryPolicy().connectionPolicy()
                .stableConnectionDuration())
                .isEqualTo(Duration.ofSeconds(5));
        assertThat(strings.connectTimeout()).isEqualTo(Duration.ofSeconds(4));
    }

    @Test
    void emptyObjectProducesNoWorkerGroups() {
        assertThat(ScenarioWorkersJsonParser.parse("{}"))
                .isEmpty();
    }

    @Test
    void rejectsOldBundleFields() {
        assertThatThrownBy(() -> ScenarioWorkersJsonParser.parse("""
                {
                  "group": {
                    "type":"PHONE_NUMBER",
                    "workerGroupId":"group",
                    "attributes":{},
                    "eventCodes":["phone.first"],
                    "endpointManagerId":"adapter",
                    "workers":[{"workerId":"worker-1"}]
                  }
                }
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown field");
    }

    @Test
    void rejectsLegacyAndPartialRetryPolicy() {
        assertThatThrownBy(() -> ScenarioWorkersJsonParser.parse("""
                {
                  "group": {
                    "eventCodes":["event.one"],
                    "reconnectIntervalMillis":300,
                    "workers":[{"clientWorkerKey":"worker-1"}]
                  }
                }
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(
                        "unknown field reconnectIntervalMillis"
                );

        assertThatThrownBy(() -> ScenarioWorkersJsonParser.parse("""
                {
                  "group": {
                    "eventCodes":["event.one"],
                    "retryPolicy":{"maxPrepareAttempts":3},
                    "workers":[{"clientWorkerKey":"worker-1"}]
                  }
                }
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must contain all retry policy fields");
    }

    @Test
    void rejectsDuplicateEventCodes() {
        assertThatThrownBy(() -> ScenarioWorkersJsonParser.parse("""
                {
                  "group": {
                    "eventCodes":["event.one","event.one"],
                    "workers":[{"clientWorkerKey":"worker-1"}]
                  }
                }
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not contain duplicates");
    }

    @Test
    void allowsTheSameClientWorkerKeyInDifferentGroups() {
        assertThat(ScenarioWorkersJsonParser.parse("""
                {
                  "group-1": {
                    "eventCodes":["event.one"],
                    "workers":[{"clientWorkerKey":"worker-1"}]
                  },
                  "group-2": {
                    "eventCodes":["event.two"],
                    "workers":[{"clientWorkerKey":"worker-1"}]
                  }
                }
                """)).hasSize(2);
    }

    @Test
    void rejectsLegacyUriAndInvalidIndexField() {
        assertThatThrownBy(() -> ScenarioWorkersJsonParser.parse("""
                {
                  "group": {
                    "eventCodes":["event.one"],
                    "websocketUri":"ws://127.0.0.1/connect",
                    "workers":[{"clientWorkerKey":"worker-1"}]
                  }
                }
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown field websocketUri");

        assertThatThrownBy(() -> ScenarioWorkersJsonParser.parse("""
                {
                  "group": {
                    "eventCodes":["event.one"],
                    "workers":[{
                      "clientWorkerKey":"worker-1",
                      "indexedPropertyUpdates":{"worker.region":"local"}
                    }]
                  }
                }
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must use index.*");
    }

    @Test
    void parsesUniqueSandboxDirectoriesAndRejectsDuplicates() {
        Path sandbox = Path.of("data/scenario-workers/worker-1")
                .toAbsolutePath()
                .normalize();
        List<ScenarioWorkerGroupConfig> configs =
                ScenarioWorkersJsonParser.parse("""
                        {
                          "group": {
                            "eventCodes":["event.one"],
                            "workers":[{
                              "clientWorkerKey":"worker-1",
                              "sandboxDirectory":
                                "data/scenario-workers/worker-1"
                            }]
                          }
                        }
                        """);

        assertThat(configs.get(0).workers().get(0).sandboxDirectory())
                .isEqualTo(sandbox);

        assertThatThrownBy(() -> ScenarioWorkersJsonParser.parse("""
                {
                  "group-1": {
                    "eventCodes":["event.one"],
                    "workers":[{
                      "clientWorkerKey":"worker-1",
                      "sandboxDirectory":"data/scenario-workers/shared"
                    }]
                  },
                  "group-2": {
                    "eventCodes":["event.two"],
                    "workers":[{
                      "clientWorkerKey":"worker-2",
                      "sandboxDirectory":"data/scenario-workers/shared/../shared"
                    }]
                  }
                }
                """)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sandboxDirectory must be unique");
    }

    @Test
    void rejectsBlankSandboxDirectory() {
        assertThatThrownBy(() -> ScenarioWorkersJsonParser.parse("""
                {
                  "group": {
                    "eventCodes":["event.one"],
                    "workers":[{
                      "clientWorkerKey":"worker-1",
                      "sandboxDirectory":" "
                    }]
                  }
                }
                """)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be a non-blank string");
    }
}
