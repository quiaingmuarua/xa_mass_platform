package com.xa.mass.worker.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.naming.CommunicationException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class DomainInspectHandlerTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void normalizesDomainAndMapsUnstableResolverAddresses()
            throws Exception {
        AtomicReference<String> observedDomain = new AtomicReference<>();
        DomainInspectHandler handler = new DomainInspectHandler(
                domain -> {
                    observedDomain.set(domain);
                    return Map.of(
                            "A",
                            List.of(
                                    "192.0.2.1",
                                    "10.0.0.1",
                                    "192.0.2.1"
                            ),
                            "AAAA",
                            List.of("2001:db8::1", "2001:db8::1")
                    );
                },
                json
        );

        JsonNode result = handler.execute(payload("B\u00dcCHER.de."));

        assertEquals("xn--bcher-kva.de", observedDomain.get());
        assertEquals(
                """
                {"domain":"xn--bcher-kva.de","resolves":true,\
                "ipv4Addresses":["10.0.0.1","192.0.2.1"],\
                "ipv6Addresses":["2001:db8::1"]}\
                """,
                json.writeValueAsString(result)
        );
    }

    @Test
    void emptyDnsRecordsAreACompletedBusinessResult() throws Exception {
        DomainInspectHandler handler = new DomainInspectHandler(
                ignored -> Map.of(),
                json
        );

        assertEquals(
                """
                {"domain":"missing.example","resolves":false,\
                "ipv4Addresses":[],"ipv6Addresses":[]}\
                """,
                json.writeValueAsString(
                        handler.execute(payload("missing.example"))
                )
        );
    }

    @Test
    void invalidInputDoesNotReachTheResolver() {
        DomainInspectHandler handler = new DomainInspectHandler(
                ignored -> {
                    throw new AssertionError("resolver must not be called");
                },
                json
        );

        assertThrows(
                WorkerInputException.class,
                () -> handler.execute(payload("https://example.com"))
        );
        assertThrows(
                WorkerInputException.class,
                () -> handler.execute(json.createObjectNode())
        );
    }

    @Test
    void dnsFailuresRemainWorkerExecutionFailures() {
        CommunicationException failure =
                new CommunicationException("DNS timeout");
        DomainInspectHandler handler = new DomainInspectHandler(
                ignored -> {
                    throw failure;
                },
                json
        );

        assertEquals(
                failure,
                assertThrows(
                        CommunicationException.class,
                        () -> handler.execute(payload("example.com"))
                )
        );
    }

    private ObjectNode payload(String domain) {
        ObjectNode payload = json.createObjectNode();
        payload.put("domain", domain);
        return payload;
    }
}
