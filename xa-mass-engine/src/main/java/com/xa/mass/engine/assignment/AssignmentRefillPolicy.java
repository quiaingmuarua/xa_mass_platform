package com.xa.mass.engine.assignment;

/**
 * Engine-internal owner for deciding whether a released worker slot should
 * trigger another task assignment attempt.
 */
public interface AssignmentRefillPolicy {

    AssignmentRefillDecision decide(AssignmentRefillRequest request);
}
