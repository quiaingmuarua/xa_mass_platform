package com.xa.mass.kernel.pacer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ResultRoutingApplicationConfigTest {

    @Test
    void readsOnlyOwnedSectionAndUsesDefaults() {
        var defaults = ResultRoutingApplicationConfig
                .fromKernelConfigJson("{}");
        assertEquals(100L, defaults.intervalMillis());
        assertEquals(100, defaults.routing().perOutcomeBatchLimit());

        var configured = ResultRoutingApplicationConfig
                .fromKernelConfigJson("""
                        {
                          "assignmentDispatch":{"ignored":true},
                          "resultRouting":{"intervalMillis":17}
                        }
                        """);
        assertEquals(17L, configured.intervalMillis());
    }

    @Test
    void rejectsUnknownOrInvalidOwnedFields() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ResultRoutingApplicationConfig.fromKernelConfigJson(
                        "{\"resultRouting\":{\"extra\":1}}"
                )
        );
        for (String value : new String[]{"0", "-1", "1.5", "true"}) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ResultRoutingApplicationConfig
                            .fromKernelConfigJson(
                                    "{\"resultRouting\":"
                                            + "{\"intervalMillis\":"
                                            + value
                                            + "}}"
                            )
            );
        }
    }
}
