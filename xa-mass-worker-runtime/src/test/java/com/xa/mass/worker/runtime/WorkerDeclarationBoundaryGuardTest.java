package com.xa.mass.worker.runtime;

import com.xa.mass.worker.runtime.resource.WorkerDeclarationRecord;
import com.xa.mass.worker.runtime.resource.WorkerDeclarationStore;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerDeclarationBoundaryGuardTest {

    private static final Pattern RUNTIME_OR_HISTORY_METHOD = Pattern.compile(
            "(?i)(schedul|dispatch|lease|heartbeat|history|analytics|attempt|reservation|runtime)"
    );

    @Test
    void workerDeclarationStoreDoesNotGrowRuntimeOrHistoryMethods() {
        List<String> violations = new ArrayList<>();
        for (Method method : WorkerDeclarationStore.class.getDeclaredMethods()) {
            if (RUNTIME_OR_HISTORY_METHOD.matcher(method.getName()).find()) {
                violations.add("WorkerDeclarationStore." + method.getName());
            }
        }

        assertTrue(violations.isEmpty(),
                "WorkerDeclarationStore must not grow runtime/history-shaped methods:\n"
                        + String.join("\n", violations));
    }

    @Test
    void workerDeclarationStoreDoesNotExposeBaseWorkerModel() {
        List<String> violations = new ArrayList<>();
        for (Method method : WorkerDeclarationStore.class.getDeclaredMethods()) {
            if (method.getReturnType().getName().equals("com.xa.mass.base.model.Worker")) {
                violations.add(method.getName() + " returns base.model.Worker");
            }
            for (Class<?> parameterType : method.getParameterTypes()) {
                if (parameterType.getName().equals("com.xa.mass.base.model.Worker")) {
                    violations.add(method.getName() + " accepts base.model.Worker");
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "WorkerDeclarationStore must persist WorkerDeclarationRecord, not the mixed base Worker model:\n"
                        + String.join("\n", violations));
    }

    @Test
    void workerDeclarationRecordDoesNotCarryRuntimeOrCapabilityHintFields() {
        Set<String> forbiddenComponents = Set.of(
                "statusName",
                "status",
                "lastHeartbeat",
                "supportedProjects",
                "supportedEventCodes",
                "dispatchEnabled",
                "reservedPermits",
                "exclusiveLeaseHeld"
        );

        List<String> violations = Stream.of(WorkerDeclarationRecord.class.getRecordComponents())
                .map(component -> component.getName())
                .filter(forbiddenComponents::contains)
                .toList();

        assertTrue(violations.isEmpty(),
                "WorkerDeclarationRecord must stay declaration-only and must not carry runtime state "
                        + "or worker-level capability hints:\n"
                        + String.join("\n", violations));
    }
}
