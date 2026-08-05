package com.xa.mass.scenarioworkers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
                            "reconnectIntervalMillis":300,
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
        assertThat(phone.reconnectInterval())
                .isEqualTo(Duration.ofMillis(250));
        assertThat(phone.connectTimeout()).isEqualTo(Duration.ofSeconds(15));
        assertThat(phone.workers().get(0).workerProperties())
                .containsEntry("region", "local");
        assertThat(phone.workers().get(0).indexedPropertyUpdates())
                .containsEntry("index.worker.region", "local");

        ScenarioWorkerGroupConfig strings = configs.get(1);
        assertThat(strings.requestTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(strings.reconnectInterval())
                .isEqualTo(Duration.ofMillis(300));
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
}
