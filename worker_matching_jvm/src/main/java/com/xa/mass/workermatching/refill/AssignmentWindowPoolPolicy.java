package com.xa.mass.workermatching.refill;

import com.xa.mass.kernel.assignment.EligibilityQuery;
import com.xa.mass.workermatching.MatchingGroup.AssignmentWindowPool;
import com.xa.mass.workermatching.WorkerProperties.WorkerFacts;
import com.xa.mass.workermatching.pool.WorkerCandidatePool;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;

/** One bounded Facts snapshot qualifies offered identities before any stock is admitted. */
public final class AssignmentWindowPoolPolicy extends PoolMaintenance<Boolean> {
    private static final String LAST = "lastAssignedAt";
    private static final String COUNT = "windowAssignmentCount";
    private static final String BUCKET = "available";
    private static final System.Logger LOG = System.getLogger(AssignmentWindowPoolPolicy.class.getName());
    private final BiFunction<String, List<String>, Map<String, WorkerFacts>> readFacts;
    private final LongSupplier clock;
    private final Map<String, AssignmentWindowPool> windows;

    public AssignmentWindowPoolPolicy(WorkerCandidatePool pool,
            BiFunction<String, List<String>, Map<String, WorkerFacts>> readFacts,
            LongSupplier clock, Map<String, AssignmentWindowPool> windows) {
        super(pool);
        this.readFacts = Objects.requireNonNull(readFacts);
        this.clock = Objects.requireNonNull(clock);
        this.windows = Map.copyOf(windows);
    }

    @Override protected EligibilityQuery normalize(String group, EligibilityQuery query) {
        if (!windows.containsKey(group)) throw new IllegalArgumentException("assignment-window Pool is unavailable");
        if (!query.query().isEmpty()) throw new IllegalArgumentException("assignment-window Pool requires an empty target");
        return query;
    }

    @Override protected Map<String, Boolean> readQualifications(String group, List<String> ids) {
        var policy = windows.get(group);
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
                "operation=assignmentWindowPool.refill skipped {0} malformed property records in Group {1}", malformed, group);
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

    private static boolean available(Map<String, Object> properties, AssignmentWindowPool policy, long window) {
        boolean present = properties.containsKey(LAST);
        if (present != properties.containsKey(COUNT)) throw new IllegalArgumentException("Incomplete assignment window");
        if (!present) return true;
        long last = integer(properties.get(LAST)), count = integer(properties.get(COUNT));
        long observedWindow = last / policy.windowMillis();
        if (observedWindow > window) throw new IllegalArgumentException("Future assignment window");
        return observedWindow < window || count < policy.maxAssignments();
    }

    private static long integer(Object value) {
        long result = switch (value) {
            case Byte number -> number.longValue();
            case Short number -> number.longValue();
            case Integer number -> number.longValue();
            case Long number -> number;
            case BigInteger number -> number.longValueExact();
            case BigDecimal number -> number.longValueExact();
            case null -> throw new IllegalArgumentException("Assignment window fields must be integers");
            default -> throw new IllegalArgumentException("Assignment window fields must be integers");
        };
        if (result < 0) throw new IllegalArgumentException("Assignment window fields must be non-negative");
        return result;
    }
}
