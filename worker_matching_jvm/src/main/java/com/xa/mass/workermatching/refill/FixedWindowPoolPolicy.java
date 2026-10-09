package com.xa.mass.workermatching.refill;

import com.xa.mass.kernel.assignment.EligibilityQuery;
import com.xa.mass.workermatching.FixedWindowPoolDefinition;
import com.xa.mass.workermatching.FixedWindowPoolDefinition.WindowLimit;
import com.xa.mass.workermatching.WorkerProperties.WorkerFacts;
import com.xa.mass.workermatching.pool.WorkerCandidatePool;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;

/** One bounded Facts snapshot qualifies offered identities before any stock is admitted. */
public final class FixedWindowPoolPolicy extends PoolMaintenance<Boolean> {
    private static final String BUCKET = "available";
    private static final System.Logger LOG = System.getLogger(FixedWindowPoolPolicy.class.getName());
    private final BiFunction<String, List<String>, Map<String, WorkerFacts>> readFacts;
    private final LongSupplier clock;
    private final FixedWindowPoolDefinition definition;

    public FixedWindowPoolPolicy(WorkerCandidatePool pool,
            BiFunction<String, List<String>, Map<String, WorkerFacts>> readFacts,
            LongSupplier clock, FixedWindowPoolDefinition definition) {
        super(pool);
        this.readFacts = Objects.requireNonNull(readFacts);
        this.clock = Objects.requireNonNull(clock);
        this.definition = Objects.requireNonNull(definition);
    }

    @Override protected EligibilityQuery normalize(String group, EligibilityQuery query) {
        if (!definition.limitsByGroup().containsKey(group)) throw new IllegalArgumentException("fixed-window Pool is unavailable");
        if (!query.query().isEmpty()) throw new IllegalArgumentException("fixed-window Pool requires an empty target");
        return query;
    }

    @Override protected Map<String, Boolean> readQualifications(String group, List<String> ids) {
        var policy = definition.limitsByGroup().get(group);
        var facts = readFacts.apply(group, ids);
        long window = Math.floorDiv(clock.getAsLong(), policy.windowMillis());
        var qualified = new LinkedHashMap<String, Boolean>();
        int malformed = 0;
        for (String id : ids) {
            var snapshot = facts.get(id);
            if (snapshot == null) continue;
            try {
                if (available(snapshot.platformProperties(), policy, window)) qualified.put(id, true);
            } catch (IllegalArgumentException | ArithmeticException invalid) {
                malformed++;
            }
        }
        if (malformed != 0) LOG.log(System.Logger.Level.WARNING,
                "operation=fixedWindowPool.refill skipped {0} malformed property records in Group {1}, Pool {2}",
                malformed, group, definition.poolName());
        return qualified;
    }

    @Override protected @Nullable String bucketKey(String group, String workerId, @Nullable Boolean qualified) {
        return Boolean.TRUE.equals(qualified) ? BUCKET : null;
    }

    @Override protected Map<EligibilityQuery, Set<String>> matchingKeys(String group,
            Collection<EligibilityQuery> queries, Set<String> keys) {
        var result = new LinkedHashMap<EligibilityQuery, Set<String>>();
        var matching = keys.contains(BUCKET) ? Set.of(BUCKET) : Set.<String>of();
        queries.forEach(query -> result.put(query, matching));
        return result;
    }

    private boolean available(Map<String, Object> properties, WindowLimit policy, long window) {
        boolean present = properties.containsKey(definition.timestampProperty());
        if (present != properties.containsKey(definition.countProperty())) throw new IllegalArgumentException("Incomplete fixed window");
        if (!present) return true;
        long last = integer(properties.get(definition.timestampProperty())), count = integer(properties.get(definition.countProperty()));
        long observedWindow = last / policy.windowMillis();
        if (observedWindow > window) throw new IllegalArgumentException("Future fixed window");
        return observedWindow < window || count < policy.maxCount();
    }

    private static long integer(Object value) {
        long result = switch (value) {
            case Byte number -> number.longValue();
            case Short number -> number.longValue();
            case Integer number -> number.longValue();
            case Long number -> number;
            case BigInteger number -> number.longValueExact();
            case BigDecimal number -> number.longValueExact();
            case null -> throw new IllegalArgumentException("Window fields must be integers");
            default -> throw new IllegalArgumentException("Window fields must be integers");
        };
        if (result < 0) throw new IllegalArgumentException("Window fields must be non-negative");
        return result;
    }
}
