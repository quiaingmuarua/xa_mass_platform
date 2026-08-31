package com.xa.mass.integration.workerlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class WorkerConvergenceCampaignTest {

    @Test
    void campaignUsesIndependentOneShotLabOperations() {
        assertThat(WorkerConvergenceCampaign.ActionType.values())
                .containsExactly(
                        WorkerConvergenceCampaign.ActionType.START,
                        WorkerConvergenceCampaign.ActionType.STOP,
                        WorkerConvergenceCampaign.ActionType.SCHEDULE_STOP,
                        WorkerConvergenceCampaign.ActionType
                                .CANCEL_SCHEDULED_STOP,
                        WorkerConvergenceCampaign.ActionType.REPROPERTY,
                        WorkerConvergenceCampaign.ActionType.CREATE_TASK
                );
    }

    @Test
    void fixedSeedProducesTheSameFiniteActionSequence() {
        var first = WorkerConvergenceCampaign.plannedActions(
                WorkerConvergenceCampaign.DEFAULT_SEED,
                20
        );
        var second = WorkerConvergenceCampaign.plannedActions(
                WorkerConvergenceCampaign.DEFAULT_SEED,
                20
        );

        assertThat(first).isEqualTo(second).hasSize(20);
        assertThat(first).allSatisfy(action -> {
            assertThat(action.round()).isBetween(1, 20);
            assertThat(action.labSlot()).isIn(
                    WorkerConvergenceCampaign.SLOT_A,
                    WorkerConvergenceCampaign.SLOT_B
            );
        });
    }

    @Test
    void rejectsUnboundedCampaignRounds() {
        assertThatThrownBy(() -> WorkerConvergenceCampaign.plannedActions(
                1,
                101
        )).isInstanceOf(IllegalArgumentException.class);
    }
}
