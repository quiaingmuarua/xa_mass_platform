package com.xa.mass.api.auth.operator;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class InMemoryOperatorSessionStore implements OperatorSessionStore {

    private final Map<String, OperatorSessionRecord> sessionsById = new LinkedHashMap<>();

    @Override
    public synchronized OperatorSessionRecord get(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        return sessionsById.get(sessionId.trim());
    }

    @Override
    public synchronized OperatorSessionRecord save(OperatorSessionRecord session) {
        OperatorSessionRecord normalized = Objects.requireNonNull(session, "session");
        sessionsById.put(normalized.sessionId(), normalized);
        return normalized;
    }

    @Override
    public synchronized void revoke(String sessionId) {
        OperatorSessionRecord existing = get(sessionId);
        if (existing != null) {
            sessionsById.put(existing.sessionId(), existing.markRevoked());
        }
    }
}
