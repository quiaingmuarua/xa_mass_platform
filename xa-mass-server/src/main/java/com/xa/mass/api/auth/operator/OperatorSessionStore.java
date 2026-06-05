package com.xa.mass.api.auth.operator;

public interface OperatorSessionStore {

    OperatorSessionRecord get(String sessionId);

    OperatorSessionRecord save(OperatorSessionRecord session);

    void revoke(String sessionId);
}
