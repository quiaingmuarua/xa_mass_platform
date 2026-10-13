package com.xa.mass.scenario.appchecks;

/** Scenario-local rejection before task or configuration effects. */
final class AppCheckAccessException extends RuntimeException {
    final int status;
    AppCheckAccessException(int status, String message) { super(message); this.status = status; }
}
