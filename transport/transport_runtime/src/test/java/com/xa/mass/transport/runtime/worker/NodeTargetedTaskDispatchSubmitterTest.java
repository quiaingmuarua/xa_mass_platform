package com.xa.mass.transport.runtime.worker;

import com.xa.mass.base.runtime.dispatch.NodeTargetedTaskDispatchHandoff;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBatch;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.base.runtime.dispatch.TaskDispatchContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NodeTargetedTaskDispatchSubmitterTest {

    @Test
    void splitsBatchByResolvedTransportNodeAndCompensatesMissingOwner() {
        CapturingNodeTargetedHandoff handoff = new CapturingNodeTargetedHandoff();
        List<TaskDispatchBinding> compensated = new ArrayList<>();
        NodeTargetedTaskDispatchSubmitter submitter = new NodeTargetedTaskDispatchSubmitter(
                handoff,
                binding -> switch (binding.workerId()) {
                    case "worker-1" -> Optional.of("node-1");
                    case "worker-2" -> Optional.of("node-2");
                    default -> Optional.empty();
                },
                (task, dispatchBindings, detail) -> {
                    compensated.addAll(dispatchBindings);
                    return true;
                }
        );

        submitter.onTaskDispatchBatch(context(), List.of(
                binding("msg-1", "worker-1"),
                binding("msg-2", "worker-2"),
                binding("msg-3", "worker-missing-route")
        ));

        assertEquals(List.of("msg-1"), messages(handoff.submittedByNode.get("node-1")));
        assertEquals(List.of("msg-2"), messages(handoff.submittedByNode.get("node-2")));
        assertEquals(List.of("msg-3"), compensated.stream().map(TaskDispatchBinding::messageId).toList());
    }

    @Test
    void compensatesWhenResolvedTransportNodeIsBlank() {
        CapturingNodeTargetedHandoff handoff = new CapturingNodeTargetedHandoff();
        List<TaskDispatchBinding> compensated = new ArrayList<>();
        List<String> details = new ArrayList<>();
        NodeTargetedTaskDispatchSubmitter submitter = new NodeTargetedTaskDispatchSubmitter(
                handoff,
                ignored -> Optional.of(" "),
                (task, dispatchBindings, detail) -> {
                    compensated.addAll(dispatchBindings);
                    details.add(detail);
                    return true;
                }
        );

        submitter.onTaskDispatchBatch(context(), List.of(binding("msg-1", "worker-1")));

        assertEquals(List.of(), new ArrayList<>(handoff.submittedByNode.keySet()));
        assertEquals(List.of("msg-1"), compensated.stream().map(TaskDispatchBinding::messageId).toList());
        assertEquals(List.of("transport route owner is unavailable after assignment"), details);
    }

    private static TaskDispatchContext context() {
        return new TaskDispatchContext("task-1", "task", "demo", "user", "demo.event", Map.of());
    }

    private static TaskDispatchBinding binding(String messageId, String workerId) {
        return new TaskDispatchBinding(
                "task-1",
                messageId,
                "demo.event",
                Map.of(),
                null,
                0,
                "attempt-" + messageId,
                1,
                "lease-" + messageId,
                workerId,
                "batch-1"
        );
    }

    private static List<String> messages(TaskDispatchBatch batch) {
        return batch == null
                ? List.of()
                : batch.dispatchBindings().stream().map(TaskDispatchBinding::messageId).toList();
    }

    private static final class CapturingNodeTargetedHandoff implements NodeTargetedTaskDispatchHandoff {
        private final Map<String, TaskDispatchBatch> submittedByNode = new LinkedHashMap<>();

        @Override
        public void submit(String transportNodeId, TaskDispatchBatch batch) {
            submittedByNode.put(transportNodeId, batch);
        }

        @Override
        public TaskDispatchBatch poll(String transportNodeId, long timeoutMillis) {
            return null;
        }

        @Override
        public void submit(TaskDispatchBatch batch) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TaskDispatchBatch poll(long timeoutMillis) {
            return null;
        }

        @Override
        public void shutdown() {
        }
    }
}
