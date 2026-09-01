package com.xa.mass.kernel.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xa.mass.kernel.task.TaskRuntime.TaskItemResult;
import org.junit.jupiter.api.Test;

class TaskItemResultTest {

    @Test
    void exact200IsTheOnlySuccessCode() {
        TaskItemResult succeeded = TaskItemResult.succeeded("payload");
        TaskItemResult failed = TaskItemResult.failed();
        TaskItemResult detailedFailure = new TaskItemResult(
                "3303",
                "execution failed"
        );

        assertEquals("200", succeeded.code());
        assertEquals("payload", succeeded.opaqueResultPayload());
        assertTrue(succeeded.succeeded());
        assertEquals("failed", failed.code());
        assertEquals(
                "TaskItem ended without a successful result",
                failed.opaqueResultPayload()
        );
        assertFalse(failed.succeeded());
        assertFalse(detailedFailure.succeeded());
        assertEquals(
                "execution failed",
                detailedFailure.opaqueResultPayload()
        );
    }

    @Test
    void codeAndPayloadAreRequired() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new TaskItemResult("", null)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new TaskItemResult("200", null)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new TaskItemResult("200", "")
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new TaskItemResult("failed", null)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new TaskItemResult("failed", "")
        );
    }
}
