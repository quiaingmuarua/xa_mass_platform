package com.xa.mass.integration.workercallperformance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LaneSaturationTest {

    @Test
    void saturationCasesAreNamedAndRoutedSeparatelyFromOpenLoopCases() {
        assertThat(LaneSaturation.parse("sat-task-any")).isEqualTo(LaneSaturation.Path.TASK_ANY);
        assertThat(LaneSaturation.parse("sat-task-targeted")).isEqualTo(LaneSaturation.Path.TASK_TARGETED);
        assertThat(LaneSaturation.handles("sat-task-any")).isTrue();
        assertThat(LaneSaturation.handles("task-any-500")).isFalse();
        assertThatThrownBy(() -> LaneSaturation.parse("sat-direct")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void seededItemsUseTheAnyPoolOrRotateTargetWorkersWithALongTtl() {
        var ids = List.of("w0", "w1", "w2");
        var any = LaneSaturation.items(LaneSaturation.Path.TASK_ANY, "perf-a", ids, "p", 200);
        var targeted = LaneSaturation.items(LaneSaturation.Path.TASK_TARGETED, "perf-b", ids, "p", 0);

        assertThat(any).hasSize(LaneSaturation.APPEND_BATCH);
        assertThat(any.getFirst()).containsEntry("messageId", "p-perf-a-200").containsEntry("ttlMillis", 900_000)
                .containsEntry("workerSelector", Map.of("executorName", "worker.any", "input", Map.of()));
        assertThat(targeted.subList(0, 4)).extracting(item -> CallApi.object(item.get("workerSelector")).get("input"))
                .containsExactly("w0", "w1", "w2", "w0");
        assertThat(LaneSaturation.ITEMS_PER_GROUP % LaneSaturation.APPEND_BATCH).isZero();
    }

    @Test
    void turnaroundIsTheAverageLeaseCycleOfEveryWorker() {
        // 2000 Workers completing 171,720 Items in 30s: each Worker cycles every ~349ms.
        assertThat(LaneSaturation.perWorkerTurnaroundMillis(171_720, 30.0)).isCloseTo(349.4, org.assertj.core.data.Offset.offset(.1));
        assertThat(LaneSaturation.perWorkerTurnaroundMillis(0, 30.0)).isNull();
    }
}
