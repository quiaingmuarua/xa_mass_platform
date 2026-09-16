package com.xa.mass.kernel.task;

import com.xa.mass.kernel.assignment.RefillTarget;
import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import com.xa.mass.kernel.task.TaskRuntime.TaskIdleDisposition;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TaskDescriptorTest {
    private static TaskDescriptor descriptor(String rule, List<RefillTarget> targets) {
        return new TaskDescriptor("task", "group", TaskIdleDisposition.CLOSE_WHEN_IDLE,
                Map.of("priority", "0", "maxRetryTimes", "1"), rule, targets);
    }

    @Test void capturesTargetsWithoutInterpretingOrNormalizingRuleParameters() {
        var values = new ArrayList<>(List.of("b", "a", "b"));
        var target = new RefillTarget(Map.of("custom.field", values), 1000);
        var supplied = new ArrayList<>(List.of(target));
        var descriptor = descriptor("external.rule", supplied);
        values.clear(); supplied.clear();
        assertEquals(List.of(target), descriptor.refillTargets());
        assertEquals(List.of("b", "a", "b"), descriptor.refillTargets().getFirst().query().query().get("custom.field"));
        assertThrows(UnsupportedOperationException.class, () -> descriptor.refillTargets().clear());
        assertThrows(UnsupportedOperationException.class, () -> target.query().query().clear());
        assertEquals(descriptor, descriptor("external.rule", List.of(target)));
        assertNotEquals(descriptor, descriptor("other.rule", List.of(target)));
        assertNotEquals(descriptor, descriptor("external.rule", List.of(new RefillTarget(Map.of(), 1))));
    }

    @Test void requiresAnExplicitBoundedConfiguration() {
        var target = new RefillTarget(Map.of(), 1);
        for (String rule : new String[]{null, "", " "})
            assertThrows(IllegalArgumentException.class, () -> descriptor(rule, List.of(target)));
        assertThrows(NullPointerException.class, () -> descriptor("r", null));
        assertThrows(NullPointerException.class, () -> descriptor("r", Collections.singletonList(null)));
        assertThrows(IllegalArgumentException.class, () -> descriptor("r", List.of()));
        assertThrows(IllegalArgumentException.class, () -> descriptor("r", Collections.nCopies(101, target)));
        assertEquals(100, descriptor("r", Collections.nCopies(100, target)).refillTargets().size());
    }
}
