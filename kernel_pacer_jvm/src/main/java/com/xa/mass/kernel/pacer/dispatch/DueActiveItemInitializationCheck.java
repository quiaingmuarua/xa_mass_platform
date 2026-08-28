package com.xa.mass.kernel.pacer.dispatch;

import com.xa.mass.kernel.score.TaskItemScoreBandCore;
import com.xa.mass.kernel.score.TaskScoreBandCore;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class DueActiveItemInitializationCheck
        implements TaskInitializationCheck {

    private final TaskItemScoreBandCore itemScores;
    private final TaskScoreBandCore taskScores;

    DueActiveItemInitializationCheck(
            TaskItemScoreBandCore itemScores,
            TaskScoreBandCore taskScores
    ) {
        this.itemScores = Objects.requireNonNull(itemScores, "itemScores");
        this.taskScores = Objects.requireNonNull(taskScores, "taskScores");
    }

    @Override
    public void check(Map<String, Long> initialTaskScores) {
        LinkedHashMap<String, Long> observed = new LinkedHashMap<>(
                Objects.requireNonNull(
                        initialTaskScores,
                        "initialTaskScores"
                )
        );
        if (observed.isEmpty()) {
            return;
        }
        Map<String, Boolean> due = Objects.requireNonNull(
                itemScores.hasDueActiveItems(List.copyOf(observed.keySet())),
                "Task item score owner returned null due states"
        );
        LinkedHashMap<String, Long> ready = new LinkedHashMap<>();
        observed.forEach((taskId, score) -> {
            if (due.getOrDefault(taskId, false)) {
                ready.put(taskId, score);
            }
        });
        if (ready.isEmpty()) {
            return;
        }
        taskScores.promoteObservedInitialTasks(ready);
    }
}
