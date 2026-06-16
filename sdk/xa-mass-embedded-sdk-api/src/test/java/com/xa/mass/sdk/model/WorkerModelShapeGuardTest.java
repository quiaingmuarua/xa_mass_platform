package com.xa.mass.sdk.model;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerModelShapeGuardTest {

    private static final Set<String> FORBIDDEN_WORKER_DEFAULT_VIEW_FIELDS = Set.of(
            "adapterNodeId",
            "adapterId",
            "onlineStrategy",
            "lastHeartbeat",
            "lastHeartbeatMillis",
            "observedAt",
            "createTime",
            "updateTime",
            "registeredAt",
            "lastSeenAt",
            "expiresAt",
            "deadline",
            "leaseExpireAt"
    );

    @Test
    void workerRegistrationDoesNotExposeTransportTopologyOrRawViewTimestamps() {
        assertNoForbiddenFieldsOrGetters(WorkerRegistration.class);
    }

    @Test
    void workerSnapshotDefaultViewDoesNotExposeTransportTopologyOrRawViewTimestamps() {
        assertNoForbiddenFieldsOrGetters(WorkerSnapshot.class);
    }

    private static void assertNoForbiddenFieldsOrGetters(Class<?> type) {
        List<String> violations = new ArrayList<>();
        for (Field field : type.getDeclaredFields()) {
            if (FORBIDDEN_WORKER_DEFAULT_VIEW_FIELDS.contains(field.getName())) {
                violations.add(type.getSimpleName() + "." + field.getName());
            }
        }
        for (Method method : type.getDeclaredMethods()) {
            if (method.getParameterCount() == 0 && method.getName().startsWith("get")) {
                String propertyName = decapitalize(method.getName().substring("get".length()));
                if (FORBIDDEN_WORKER_DEFAULT_VIEW_FIELDS.contains(propertyName)) {
                    violations.add(type.getSimpleName() + "#" + method.getName() + "()");
                }
            }
        }

        assertTrue(violations.isEmpty(),
                type.getSimpleName() + " must keep default worker-facing surfaces minimal. "
                        + "Transport topology and raw timestamp fields belong to owner-specific "
                        + "topology, command, evidence, diagnostic, or audit contracts:\n"
                        + String.join("\n", violations));
    }

    private static String decapitalize(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return value.substring(0, 1).toLowerCase(Locale.ROOT) + value.substring(1);
    }
}
