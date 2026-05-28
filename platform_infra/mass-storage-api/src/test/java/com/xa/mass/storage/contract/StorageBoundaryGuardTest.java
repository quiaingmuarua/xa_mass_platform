package com.xa.mass.storage.contract;

import com.xa.mass.storage.api.TaskShellStore;
import com.xa.mass.storage.api.WorkerDeclarationStore;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageBoundaryGuardTest {

    private static final Pattern RUNTIME_OR_HISTORY_METHOD = Pattern.compile(
            "(?i)(schedul|dispatch|lease|heartbeat|history|analytics|attempt|reservation|runtime)"
    );

    private static final Map<Class<?>, Set<String>> KNOWN_TWH_2_RESIDUE = Map.of(
            TaskShellStore.class, Set.of(),
            WorkerDeclarationStore.class, Set.of()
    );

    @Test
    void shellAndDeclarationStoresDoNotGrowRuntimeOrHistoryMethods() {
        List<String> violations = new ArrayList<>();
        for (Class<?> contract : List.of(TaskShellStore.class, WorkerDeclarationStore.class)) {
            Set<String> knownResidue = KNOWN_TWH_2_RESIDUE.getOrDefault(contract, Set.of());
            for (Method method : contract.getDeclaredMethods()) {
                String methodName = method.getName();
                if (knownResidue.contains(methodName)) {
                    continue;
                }
                if (RUNTIME_OR_HISTORY_METHOD.matcher(methodName).find()) {
                    violations.add(contract.getSimpleName() + "." + methodName);
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "Task shell and worker declaration stores must not grow runtime/history-shaped methods. "
                        + "Known TWH-2 residue is explicitly allowlisted until that slice removes it:\n"
                        + String.join("\n", violations));
    }
}
