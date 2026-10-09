package com.xa.mass.workermatching;

import java.util.List;

/** Explicit legacy wire-name fixture; production has no business defaults. */
public final class QualifiedCountryFixtures {
    public static final QualifiedCountryDefinition MESSAGES = new QualifiedCountryDefinition(
            "messaging", "worker.messaging.available", "worker.messaging.phone", "messaging.enabled", "true", "country");
    public static final List<QualifiedCountryDefinition> DEFINITIONS = List.of(MESSAGES);

    public static QualifiedCountryEligibility eligibility() { return new QualifiedCountryEligibility(MESSAGES); }
    private QualifiedCountryFixtures() { }
}
