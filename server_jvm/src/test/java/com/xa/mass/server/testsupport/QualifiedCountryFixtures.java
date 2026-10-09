package com.xa.mass.server.testsupport;

import com.xa.mass.workermatching.QualifiedCountryDefinition;
import com.xa.mass.workermatching.QualifiedCountryEligibility;
import java.util.List;

/** Owner proof input, deliberately independent of the business module. */
public final class QualifiedCountryFixtures {
    public static final QualifiedCountryDefinition MESSAGES = new QualifiedCountryDefinition(
            "messaging", "worker.messaging.available", "worker.messaging.phone", "messaging.enabled", "true", "country");
    public static final List<QualifiedCountryDefinition> DEFINITIONS = List.of(MESSAGES);

    public static QualifiedCountryEligibility eligibility() { return new QualifiedCountryEligibility(MESSAGES); }
    private QualifiedCountryFixtures() { }
}
