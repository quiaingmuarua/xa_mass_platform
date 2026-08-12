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
                            "eventCodes":["phone.first","phone.second"]
                          },
                          "string-group": {
                            "eventCodes":["string.first"],
                            "requestTimeoutMillis":2000,
                            "reconnectPolicy":{
                              "maxUnstableAttempts":6,
                              "reconnectIntervalMillis":300,
                              "stableConnectionDurationMillis":5000
                            },
                            "connectTimeoutMillis":4000
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
        assertThat(phone.reconnectPolicy().maxUnstableAttempts())
                .isEqualTo(20);
        assertThat(phone.connectTimeout()).isEqualTo(Duration.ofSeconds(15));

        ScenarioWorkerGroupConfig strings = configs.get(1);
        assertThat(strings.requestTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(strings.reconnectPolicy().maxUnstableAttempts())
                .isEqualTo(6);
        assertThat(strings.reconnectPolicy().reconnectInterval())
                .isEqualTo(Duration.ofMillis(300));
        assertThat(strings.reconnectPolicy().stableConnectionDuration())
                .isEqualTo(Duration.ofSeconds(5));
        assertThat(strings.connectTimeout()).isEqualTo(Duration.ofSeconds(4));
    }

    @Test
    void emptyObjectProducesNoWorkerGroups() {
        assertThat(ScenarioWorkersJsonParser.parse("{}"))
                .isEmpty();
    }

    @Test
    void rejectsTheOldInlineWorkerManifest() {
        assertThatThrownBy(() -> ScenarioWorkersJsonParser.parse("""
                {
                  "group": {
                    "eventCodes":["event.one"],
                    "workers":[{"clientWorkerKey":"worker-1"}]
                  }
                }
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown field workers");
    }

    @Test
    void rejectsLegacyAndPartialReconnectPolicy() {
        assertThatThrownBy(() -> ScenarioWorkersJsonParser.parse("""
                {
                  "group": {
                    "eventCodes":["event.one"],
                    "reconnectIntervalMillis":300
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
                    "reconnectPolicy":{"maxUnstableAttempts":3}
                  }
                }
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must contain all fields");
    }

    @Test
    void rejectsDuplicateOrUnknownEventConfiguration() {
        assertThatThrownBy(() -> ScenarioWorkersJsonParser.parse("""
                {
                  "group": {
                    "eventCodes":["event.one","event.one"]
                  }
                }
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not contain duplicates");

        assertThatThrownBy(() -> ScenarioWorkersJsonParser.parse("""
                {
                  "group": {
                    "eventCodes":["event.one"],
                    "attributes":{}
                  }
                }
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown field attributes");
    }
}
