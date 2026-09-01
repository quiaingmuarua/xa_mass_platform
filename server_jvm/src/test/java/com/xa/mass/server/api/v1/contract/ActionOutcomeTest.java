package com.xa.mass.server.api.v1.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xa.mass.server.error.ServerErrorCode;
import org.junit.jupiter.api.Test;

class ActionOutcomeTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void serializesOnlyFieldsOwnedByEachStatus() throws Exception {
        assertThat(json.writeValueAsString(ActionOutcome.applied()))
                .isEqualTo("{\"status\":\"applied\"}");
        assertThat(json.writeValueAsString(ActionOutcome.unchanged()))
                .isEqualTo("{\"status\":\"unchanged\"}");
        assertThat(json.writeValueAsString(ActionOutcome.rejected(
                ServerErrorCode.INVALID_TASK_DATA_REQUEST
        ))).isEqualTo(
                "{\"status\":\"rejected\",\"code\":12001,"
                        + "\"message\":\"Task data request is invalid\"}"
        );
    }

    @Test
    void rejectsInvalidFieldCombinations() {
        assertThatThrownBy(() -> new ActionOutcome(
                ActionOutcome.Status.APPLIED,
                1,
                "error"
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ActionOutcome(
                ActionOutcome.Status.UNCHANGED,
                null,
                "error"
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ActionOutcome(
                ActionOutcome.Status.REJECTED,
                null,
                null
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ActionOutcome(
                ActionOutcome.Status.REJECTED,
                1,
                " "
        )).isInstanceOf(IllegalArgumentException.class);
    }
}
