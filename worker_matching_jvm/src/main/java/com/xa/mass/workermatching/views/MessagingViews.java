package com.xa.mass.workermatching.views;

import com.xa.mass.workermatching.MessagingEligibility;
import com.xa.mass.workermatching.pool.CandidatePool;
import java.util.*;
import org.jspecify.annotations.Nullable;

/** Fixed country views over qualified Messaging candidates. */
public final class MessagingViews {
    private MessagingViews() { }

    public static @Nullable Map<String, String> memberships(@Nullable Map<String, Object> facts) {
        String country = MessagingEligibility.country(facts);
        return country == null ? null : Map.of("country", country);
    }

    public static CandidatePool.Selection select(List<String> countries) {
        return countries.isEmpty() ? CandidatePool.all() : CandidatePool.range("country", countries);
    }
}
