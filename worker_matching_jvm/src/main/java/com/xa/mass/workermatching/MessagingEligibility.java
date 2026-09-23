package com.xa.mass.workermatching;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Shared Messaging qualification, independent of inventory and identity lookup. */
public final class MessagingEligibility {
    private MessagingEligibility() { }

    public static @Nullable String country(@Nullable Map<String, Object> facts) {
        return facts != null && "true".equals(facts.get("messaging.enabled"))
                && facts.get("country") instanceof String country && RuleInputs.validCountry(country)
                ? country : null;
    }
}
