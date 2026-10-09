package com.xa.mass.workermatching;

import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** One pure qualification shared by refill and indexed identity lookup. */
public final class QualifiedCountryEligibility {
    private final QualifiedCountryDefinition definition;

    public QualifiedCountryEligibility(QualifiedCountryDefinition definition) {
        this.definition = Objects.requireNonNull(definition);
    }

    public @Nullable String country(@Nullable Map<String, Object> facts) {
        return facts != null && definition.requiredValue().equals(facts.get(definition.requiredProperty()))
                && facts.get(definition.countryProperty()) instanceof String country && RuleInputs.validCountry(country)
                ? country : null;
    }
}
