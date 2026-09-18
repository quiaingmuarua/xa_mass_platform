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
        return new TaskDescriptor("task", "test-project", "group", TaskIdleDisposition.CLOSE_WHEN_IDLE, Map.of("priority", "0", "maxRetryTimes", "1"), targets == null ? null : targets.stream().map(t -> new RefillTarget(rule,t.target(),t.count())).toList());
    }

    @Test void capturesTargetsWithoutInterpretingOrNormalizingRuleParameters() {
        var values = new ArrayList<>(List.of("b", "a", "b"));
        var target = new RefillTarget("external.rule", new com.xa.mass.kernel.assignment.EligibilityQuery(Map.of("custom.field", values)), 1000);
        var supplied = new ArrayList<>(List.of(target));
        var descriptor = descriptor("external.rule", supplied);
        values.clear(); supplied.clear();
        assertEquals(List.of(target), descriptor.refill());
        assertEquals(List.of("b", "a", "b"), descriptor.refill().getFirst().target().query().get("custom.field"));
        assertThrows(UnsupportedOperationException.class, () -> descriptor.refill().clear());
        assertThrows(UnsupportedOperationException.class, () -> target.target().query().clear());
        assertEquals(descriptor, descriptor("external.rule", List.of(target)));
        assertNotEquals(descriptor, descriptor("other.rule", List.of(target)));
        assertNotEquals(descriptor, descriptor("external.rule", List.of(new RefillTarget("any", new com.xa.mass.kernel.assignment.EligibilityQuery(Map.of()), 1))));
    }

    @Test void projectIsRequiredPassiveDataWithoutAProjectRegistry() {
        for (String project : new String[]{null, "", " "}) {
            assertThrows(IllegalArgumentException.class, () -> new TaskDescriptor(
                    "task", project, "group", TaskIdleDisposition.CLOSE_WHEN_IDLE,
                    Map.of("priority", "0", "maxRetryTimes", "1"), List.of()));
        }
        assertEquals("test-project", descriptor("r", List.of()).projectId());
    }

    @Test void requiresAnExplicitBoundedConfiguration() {
        var target = new RefillTarget("any", new com.xa.mass.kernel.assignment.EligibilityQuery(Map.of()), 1);
        for (String rule : new String[]{null, "", " "})
            assertThrows(IllegalArgumentException.class, () -> descriptor(rule, List.of(target)));
        assertThrows(NullPointerException.class, () -> descriptor("r", null));
        assertThrows(NullPointerException.class, () -> descriptor("r", Collections.singletonList(null)));
        assertTrue(descriptor("r", List.of()).refill().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> descriptor("r", Collections.nCopies(101, target)));
        assertEquals(100, descriptor("r", Collections.nCopies(100, target)).refill().size());
    }
}
