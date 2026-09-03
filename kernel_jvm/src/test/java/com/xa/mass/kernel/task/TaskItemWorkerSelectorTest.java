package com.xa.mass.kernel.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class TaskItemWorkerSelectorTest {

    @Test
    void normalizesAnyEqualsAndInSelectors() {
        assertEquals(
                List.of(),
                TaskItemWorkerSelector.targetWorkerIds(List.of())
        );
        assertEquals(
                List.of("worker-a"),
                TaskItemWorkerSelector.targetWorkerIds(List.of(
                        "workerId",
                        "$eq",
                        "worker-a"
                ))
        );
        assertEquals(
                List.of("worker-a", "worker-b"),
                TaskItemWorkerSelector.targetWorkerIds(List.of(
                        "workerId",
                        "$in",
                        List.of("worker-a", "worker-b")
                ))
        );
        assertEquals(
                TaskItemWorkerSelector.targetWorkerIds(List.of(
                        "workerId",
                        "$eq",
                        "worker-a"
                )),
                TaskItemWorkerSelector.targetWorkerIds(List.of(
                        "workerId",
                        "$in",
                        List.of("worker-a")
                ))
        );
    }

    @Test
    void rejectsUnsupportedOrMalformedSelectors() {
        List<List<?>> invalid = List.of(
                List.of("workerId"),
                List.of("workerId", "$eq"),
                List.of("workerId", "$eq", "worker-a", "extra"),
                List.of("worker.region", "$eq", "local"),
                List.of("workerId", "$unknown", "worker-a"),
                List.of("workerId", "$eq", 1),
                List.of("workerId", "$eq", " "),
                List.of("workerId", "$in", "worker-a"),
                List.of("workerId", "$in", List.of()),
                List.of(
                        "workerId",
                        "$in",
                        List.of("worker-a", "worker-a")
                )
        );
        invalid.forEach(selector -> assertThrows(
                IllegalArgumentException.class,
                () -> TaskItemWorkerSelector.targetWorkerIds(selector)
        ));
        assertThrows(
                IllegalArgumentException.class,
                () -> TaskItemWorkerSelector.targetWorkerIds(null)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> TaskItemWorkerSelector.targetWorkerIds(Arrays.asList(
                        "workerId",
                        "$eq",
                        null
                ))
        );
    }

    @Test
    void rejectsMoreThanOneHundredTargetsAndCopiesAcceptedOrder() {
        List<String> tooMany = IntStream.rangeClosed(1, 101)
                .mapToObj(index -> "worker-" + index)
                .toList();
        assertThrows(
                IllegalArgumentException.class,
                () -> TaskItemWorkerSelector.targetWorkerIds(List.of(
                        "workerId",
                        "$in",
                        tooMany
                ))
        );

        ArrayList<String> input = new ArrayList<>(List.of(
                "worker-b",
                "worker-a"
        ));
        List<String> normalized = TaskItemWorkerSelector.targetWorkerIds(
                List.of("workerId", "$in", input)
        );
        input.clear();
        assertEquals(List.of("worker-b", "worker-a"), normalized);
    }
}
