package com.xa.mass.workerdelivery.protocol;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class WorkerConnectionCloseTest {
    @Test void terminalCloseAndCooldownHaveDistinctStrictPayloads() {
        assertNull(WorkerConnectionClose.decode("null").cooldownUntilMillis());
        assertEquals(123L, WorkerConnectionClose.decode(WorkerConnectionClose.cooldown(123).encode()).cooldownUntilMillis());
        for (String invalid : new String[]{"{}", "[]", "{\"reason\":\"cooldown\"}",
                "{\"reason\":\"other\",\"cooldownUntilMillis\":123}",
                "{\"reason\":\"cooldown\",\"cooldownUntilMillis\":1.5}",
                "{\"reason\":\"cooldown\",\"cooldownUntilMillis\":0}",
                "{\"reason\":\"cooldown\",\"cooldownUntilMillis\":\"123\"}",
                "{\"reason\":\"cooldown\",\"cooldownUntilMillis\":123,\"extra\":true}"}) {
            assertThrows(IllegalArgumentException.class, () -> WorkerConnectionClose.decode(invalid));
        }
    }
}
