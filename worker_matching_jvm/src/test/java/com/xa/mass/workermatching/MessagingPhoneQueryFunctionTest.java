package com.xa.mass.workermatching;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.xa.mass.kernel.assignment.WorkerMatching.WorkerCandidate;
import com.xa.mass.workermatching.functions.MessagingPhoneQueryFunction;
import com.xa.mass.workermatching.index.PhoneIndex;
import java.util.*;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;

class MessagingPhoneQueryFunctionTest {
    private final PhoneIndex phones = mock(PhoneIndex.class);
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

    @Test void batchesLookupAndFactsThenAssignsCompatibleUnusedIdentitiesInRequestOrder() {
        var requests = new LinkedHashMap<String, Object>();
        requests.put("us", Map.of("phone", "same", "country", List.of("US")));
        requests.put("other", Map.of("phone", "other"));
        requests.put("cn", Map.of("phone", "same", "country", List.of("CN")));
        requests.put("any", Map.of("phone", "same"));
        var found = new LinkedHashMap<String, List<String>>();
        found.put("same", List.of("cn", "us", "gb")); found.put("other", List.of("other"));
        when(phones.lookup("g", Map.of("same", 3, "other", 1))).thenReturn(found);
        when(facts.apply("g", List.of("cn", "us", "gb", "other"))).thenReturn(Map.of(
                "cn", eligible("CN", "same"), "us", eligible("US", "same"),
                "gb", eligible("GB", "same"), "other", eligible("CN", "other")));

        var result = function.apply("g", requests);
        assertEquals(List.of("us", "other", "cn", "any"), List.copyOf(result.keySet()));
        assertEquals(List.of(new WorkerCandidate("us", 0), new WorkerCandidate("other", 0),
                new WorkerCandidate("cn", 0), new WorkerCandidate("gb", 0)), List.copyOf(result.values()));
        assertThrows(UnsupportedOperationException.class, result::clear);
        verify(phones).lookup("g", Map.of("same", 3, "other", 1));
        verify(facts).apply("g", List.of("cn", "us", "gb", "other"));
        verifyNoMoreInteractions(phones, facts);
    }

    @Test void filtersChangedPhoneDisabledMissingAndInvalidFactsWithoutAnotherLookup() {
        var ids = List.of("moved", "disabled", "invalid-country", "boolean-enabled", "missing", "no-phone", "country-miss");
        when(phones.lookup("g", Map.of("old", ids.size()))).thenReturn(Map.of("old", ids));
        when(facts.apply("g", ids)).thenReturn(Map.of(
                "moved", eligible("CN", "new"),
                "disabled", Map.of("phone", "old", "country", "CN", "messaging.enabled", "false"),
                "invalid-country", eligible("cn", "old"),
                "boolean-enabled", Map.of("phone", "old", "country", "CN", "messaging.enabled", true),
                "no-phone", Map.of("country", "CN", "messaging.enabled", "true"),
                "country-miss", eligible("US", "old")));
        var requests = new LinkedHashMap<String, Object>();
        ids.forEach(id -> requests.put(id, Map.of("phone", "old", "country", List.of("CN"))));
        assertEquals(Map.of(), function.apply("g", requests));
        verify(phones).lookup("g", Map.of("old", ids.size()));
        verify(facts).apply("g", ids);
        verifyNoMoreInteractions(phones, facts);
    }

    @Test void lookupMissSkipsFactsAndFactsFailureDoesNotReplayLookup() {
        when(phones.lookup("g", Map.of("missing", 1))).thenReturn(Map.of("missing", List.of()));
        assertEquals(Map.of(), function.apply("g", Map.of("m", Map.of("phone", "missing"))));
        verifyNoInteractions(facts);
        when(phones.lookup("g", Map.of("present", 1))).thenReturn(Map.of("present", List.of("w")));
        var failure = new IllegalStateException("malformed facts");
        when(facts.apply("g", List.of("w"))).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> function.apply("g", Map.of("m", Map.of("phone", "present")))));
        verify(phones).lookup("g", Map.of("missing", 1));
        verify(phones).lookup("g", Map.of("present", 1));
        verify(facts).apply("g", List.of("w"));
        verifyNoMoreInteractions(phones, facts);
    }

    private static Map<String, Object> eligible(String country, String phone) {
        return Map.of("country", country, "phone", phone, "messaging.enabled", "true");
    }
}
