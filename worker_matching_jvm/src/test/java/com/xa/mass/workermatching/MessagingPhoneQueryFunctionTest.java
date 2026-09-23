package com.xa.mass.workermatching;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.xa.mass.kernel.assignment.WorkerMatching.WorkerCandidate;
import com.xa.mass.workermatching.functions.MessagingPhoneQueryFunction;
import com.xa.mass.workermatching.index.PropertyIndex;
import java.util.*;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;

class MessagingPhoneQueryFunctionTest {
    private final PropertyIndex phones = mock(PropertyIndex.class);
    @SuppressWarnings("unchecked")
    private final BiFunction<String, List<String>, Map<String, Map<String, Object>>> facts = mock(BiFunction.class);
    private final MessagingPhoneQueryFunction function = new MessagingPhoneQueryFunction(phones, facts);

    @Test void normalizationRequiresPhoneAndValidOptionalCountriesWithoutReadingResources() {
        for (Object invalid : Arrays.asList(null, "+1", Map.of(), Map.of("phone", ""), Map.of("phone", " "),
                Map.of("phone", 1), Map.of("phone", "+1", "country", List.of()),
                Map.of("phone", "+1", "country", List.of("cn")), Map.of("phone", "+1", "unknown", true))) {
            assertThrows(IllegalArgumentException.class, () -> function.normalizeInput("g", invalid));
        }
        assertEquals(Map.of("phone", " +1 ", "country", List.of("CN", "US")),
                function.normalizeInput("g", Map.of("phone", " +1 ", "country", List.of("US", "CN", "US"))));
        assertEquals(Map.of(), function.apply("g", Map.of()));
        verifyNoInteractions(phones, facts);
    }

    @Test void oneMappingGoesToFirstCompatibleRequestWithoutLookingForAnotherWorker() {
        var requests = new LinkedHashMap<String, Object>();
        requests.put("us", Map.of("phone", "same", "country", List.of("US")));
        requests.put("other", Map.of("phone", "other"));
        requests.put("cn", Map.of("phone", "same", "country", List.of("CN")));
        requests.put("any", Map.of("phone", "same"));
        var found = new LinkedHashMap<String, String>();
        found.put("same", "cn"); found.put("other", "other");
        when(phones.lookup("g", List.of("same", "other"))).thenReturn(found);
        when(facts.apply("g", List.of("cn", "other"))).thenReturn(Map.of(
                "cn", eligible("CN", "same"), "us", eligible("US", "same"),
                "gb", eligible("GB", "same"), "other", eligible("CN", "other")));

        var result = function.apply("g", requests);
        assertEquals(List.of("other", "cn"), List.copyOf(result.keySet()));
        assertEquals(List.of(new WorkerCandidate("other", 0), new WorkerCandidate("cn", 0)), List.copyOf(result.values()));
        assertThrows(UnsupportedOperationException.class, result::clear);
        verify(phones).lookup("g", List.of("same", "other"));
        verify(facts).apply("g", List.of("cn", "other"));
        verifyNoMoreInteractions(phones, facts);
    }

    @Test void filtersChangedPhoneDisabledMissingAndInvalidFactsWithoutAnotherLookup() {
        var ids = List.of("moved", "disabled", "invalid-country", "boolean-enabled", "missing", "no-phone", "country-miss");
        var found = new LinkedHashMap<String, String>(); ids.forEach(id -> found.put(id, id));
        when(phones.lookup("g", ids)).thenReturn(found);
        when(facts.apply("g", ids)).thenReturn(Map.of(
                "moved", eligible("CN", "new"),
                "disabled", Map.of("phone", "disabled", "country", "CN", "messaging.enabled", "false"),
                "invalid-country", eligible("cn", "invalid-country"),
                "boolean-enabled", Map.of("phone", "boolean-enabled", "country", "CN", "messaging.enabled", true),
                "no-phone", Map.of("country", "CN", "messaging.enabled", "true"),
                "country-miss", eligible("US", "country-miss")));
        var requests = new LinkedHashMap<String, Object>();
        ids.forEach(id -> requests.put(id, Map.of("phone", id, "country", List.of("CN"))));
        assertEquals(Map.of(), function.apply("g", requests));
        verify(phones).lookup("g", ids);
        verify(facts).apply("g", ids);
        verifyNoMoreInteractions(phones, facts);
    }

    @Test void lookupMissSkipsFactsAndFactsFailureDoesNotReplayLookup() {
        when(phones.lookup("g", List.of("missing"))).thenReturn(Map.of());
        assertEquals(Map.of(), function.apply("g", Map.of("m", Map.of("phone", "missing"))));
        verifyNoInteractions(facts);
        when(phones.lookup("g", List.of("present"))).thenReturn(Map.of("present", "w"));
        var failure = new IllegalStateException("malformed facts");
        when(facts.apply("g", List.of("w"))).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> function.apply("g", Map.of("m", Map.of("phone", "present")))));
        verify(phones).lookup("g", List.of("missing"));
        verify(phones).lookup("g", List.of("present"));
        verify(facts).apply("g", List.of("w"));
        verifyNoMoreInteractions(phones, facts);
    }

    private static Map<String, Object> eligible(String country, String phone) {
        return Map.of("country", country, "phone", phone, "messaging.enabled", "true");
    }
}
