package com.xa.mass.workermatching.functions;

import com.xa.mass.kernel.assignment.WorkerMatching.WorkerCandidate;
import com.xa.mass.workermatching.MatchingGroup.AssignmentWindow;
import com.xa.mass.workermatching.QueryFunction;
import com.xa.mass.workermatching.RuleInputs;
import com.xa.mass.workermatching.WorkerProperties.WorkerFacts;
import com.xa.mass.workermatching.pool.WorkerCandidatePool;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.LongSupplier;

/** Consumes Any stock and qualifies it against a best-effort Platform assignment observation. */
public final class AssignmentWindowQueryFunction implements QueryFunction {
    private static final String LAST = "lastAssignedAt";
    private static final String COUNT = "windowAssignmentCount";
    private static final System.Logger LOG = System.getLogger(AssignmentWindowQueryFunction.class.getName());
    private final WorkerCandidatePool pool;
    private final BiFunction<String, List<String>, Map<String, WorkerFacts>> readFacts;
    private final LongSupplier clock;
    private final Map<String, AssignmentWindow> windows;

    public AssignmentWindowQueryFunction(WorkerCandidatePool pool,
            BiFunction<String, List<String>, Map<String, WorkerFacts>> readFacts,
            LongSupplier clock, Map<String, AssignmentWindow> windows) {
        this.pool = Objects.requireNonNull(pool);
        this.readFacts = Objects.requireNonNull(readFacts);
        this.clock = Objects.requireNonNull(clock);
        this.windows = Map.copyOf(windows);
    }

    @Override public Object normalizeInput(String group, Object input) {
        return Collections.unmodifiableMap(RuleInputs.object(input, Set.of()));
    }

    @Override public Map<String, WorkerCandidate> apply(String group, Map<String, Object> inputs) {
        var policy = Objects.requireNonNull(windows.get(group), "assignment window unavailable");
        // One destructive poll only. A rejected or unreadable candidate is never restored or replaced.
        var candidates = new LinkedHashMap<String, WorkerCandidate>();
        for (var candidate : pool.pollAnyBatch(group, inputs.size()))
            candidates.putIfAbsent(candidate.workerId(), candidate);
        if (candidates.isEmpty()) return Map.of();
        var ids = List.copyOf(candidates.keySet());
        var facts = readFacts.apply(group, ids);
        long window = Math.floorDiv(clock.getAsLong(), policy.windowMillis());
        var messages = inputs.keySet().iterator();
        var result = new LinkedHashMap<String, WorkerCandidate>();
        int malformed = 0;
        for (var candidate : candidates.values()) {
            WorkerFacts snapshot = facts.get(candidate.workerId());
            if (snapshot == null) continue;
            try {
                if (available(snapshot.platformProperties(), policy, window))
                    result.put(messages.next(), candidate);
            } catch (IllegalArgumentException | ArithmeticException invalid) {
                malformed++;
            }
        }
        if (malformed != 0) LOG.log(System.Logger.Level.WARNING,
                "Assignment window skipped {0} malformed property records in Group {1}", malformed, group);
        return Collections.unmodifiableMap(result);
    }

    private static boolean available(Map<String, Object> properties, AssignmentWindow policy, long window) {
        boolean present = properties.containsKey(LAST);
        if (present != properties.containsKey(COUNT)) throw new IllegalArgumentException("Incomplete assignment window");
        if (!present) return true;
        long last = integer(properties.get(LAST));
        long count = integer(properties.get(COUNT));
        long observedWindow = last / policy.windowMillis();
        return observedWindow < window || observedWindow == window && count < policy.maxAssignments();
    }

    private static long integer(Object value) {
        long result = switch (value) {
            case Byte number -> number.longValue();
            case Short number -> number.longValue();
            case Integer number -> number.longValue();
            case Long number -> number.longValue();
            case BigInteger number -> number.longValueExact();
            case BigDecimal number -> number.longValueExact();
            case null -> throw new IllegalArgumentException("Assignment window fields must be integers");
            default -> throw new IllegalArgumentException("Assignment window fields must be integers");
        };
        if (result < 0) throw new IllegalArgumentException("Assignment window fields must be non-negative");
        return result;
    }
}
