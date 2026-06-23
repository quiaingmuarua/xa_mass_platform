package com.xa.mass.transport.runtime.embedded;

import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.runtime.delivery.DispatchMessage;
import com.xa.mass.transport.runtime.delivery.DispatchOutcomeFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class AdapterCommandExecutors {
    private static final Logger logger = LoggerFactory.getLogger(AdapterCommandExecutors.class);

    private AdapterCommandExecutors() {
    }

    public static AdapterCommandExecutor perMessage(String name, FinalHopDispatchAttempt attempt) {
        String executorName = requireText(name, "name");
        FinalHopDispatchAttempt requiredAttempt = Objects.requireNonNull(attempt, "attempt");
        return items -> dispatchPerMessage(executorName, requiredAttempt, items);
    }

    private static List<DispatchOutcome> dispatchPerMessage(String name,
                                                            FinalHopDispatchAttempt attempt,
                                                            List<DispatchMessage> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        List<DispatchOutcome> outcomes = new ArrayList<>(items.size());
        for (DispatchMessage item : items) {
            outcomes.add(dispatchOne(name, attempt, item));
        }
        return Collections.unmodifiableList(outcomes);
    }

    private static DispatchOutcome dispatchOne(String name,
                                               FinalHopDispatchAttempt attempt,
                                               DispatchMessage item) {
        if (item == null) {
            return DispatchOutcome.invalid(null, null, null, "request must not be null");
        }
        try {
            if (attempt.send(item)) {
                return DispatchOutcomeFactory.delivered(item);
            }
            logger.warn("{} final-hop skipped because endpoint is unavailable: selectedWorkerId={}",
                    name, item.selectedWorkerId());
            return DispatchOutcomeFactory.noEndpoint(item, "endpoint is unavailable");
        } catch (RuntimeException ex) {
            logger.warn("{} final-hop failed: selectedWorkerId={}, reason={}",
                    name, item.selectedWorkerId(), ex.getMessage());
            return DispatchOutcomeFactory.failed(item, ex.getMessage(), true);
        }
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value.trim();
    }
}
