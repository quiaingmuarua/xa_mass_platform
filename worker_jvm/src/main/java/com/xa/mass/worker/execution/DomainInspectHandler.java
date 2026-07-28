package com.xa.mass.worker.execution;

import java.net.IDN;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import javax.naming.Context;
import javax.naming.NameNotFoundException;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

public final class DomainInspectHandler implements WorkerEventHandler {

    public static final String EVENT_CODE = "network.domain.inspect";

    private final DomainAddressResolver resolver;
    private final JsonMapper json;

    public DomainInspectHandler() {
        this(
                new JndiDomainAddressResolver(),
                JsonMapper.builder().build()
        );
    }

    DomainInspectHandler(
            DomainAddressResolver resolver,
            JsonMapper json
    ) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.json = Objects.requireNonNull(json, "json");
    }

    @Override
    public JsonNode execute(JsonNode payload) throws Exception {
        JsonNode domainNode = payload.get("domain");
        if (domainNode == null || !domainNode.isString()) {
            throw new WorkerInputException("domain must be a string");
        }
        String domain = normalizeDomain(domainNode.stringValue());
        Map<String, List<String>> records = Objects.requireNonNull(
                resolver.resolve(domain),
                "resolver result"
        );
        List<String> ipv4Addresses = stableAddresses(records.get("A"));
        List<String> ipv6Addresses = stableAddresses(records.get("AAAA"));

        ObjectNode response = json.createObjectNode();
        response.put("domain", domain);
        response.put(
                "resolves",
                !ipv4Addresses.isEmpty() || !ipv6Addresses.isEmpty()
        );
        addAddresses(response, "ipv4Addresses", ipv4Addresses);
        addAddresses(response, "ipv6Addresses", ipv6Addresses);
        return response;
    }

    private static String normalizeDomain(String value)
            throws WorkerInputException {
        String candidate = value.trim();
        if (candidate.endsWith(".")) {
            candidate = candidate.substring(0, candidate.length() - 1);
        }
        if (candidate.isBlank() || candidate.endsWith(".")) {
            throw new WorkerInputException("domain must be non-blank");
        }
        try {
            String ascii = IDN.toASCII(
                    candidate,
                    IDN.USE_STD3_ASCII_RULES
            ).toLowerCase(Locale.ROOT);
            if (ascii.isBlank() || ascii.length() > 253) {
                throw new WorkerInputException("domain is invalid");
            }
            return ascii;
        } catch (IllegalArgumentException error) {
            throw new WorkerInputException("domain is invalid");
        }
    }

    private static List<String> stableAddresses(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        TreeSet<String> stable = new TreeSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                stable.add(value);
            }
        }
        return List.copyOf(stable);
    }

    private static void addAddresses(
            ObjectNode response,
            String field,
            List<String> addresses
    ) {
        ArrayNode array = response.putArray(field);
        addresses.forEach(array::add);
    }

    @FunctionalInterface
    interface DomainAddressResolver {

        Map<String, List<String>> resolve(String domain) throws Exception;
    }

    private static final class JndiDomainAddressResolver
            implements DomainAddressResolver {

        private static final String DNS_CONTEXT_FACTORY =
                "com.sun.jndi.dns.DnsContextFactory";
        private static final String DNS_TIMEOUT_INITIAL =
                "com.sun.jndi.dns.timeout.initial";
        private static final String DNS_TIMEOUT_RETRIES =
                "com.sun.jndi.dns.timeout.retries";

        @Override
        public Map<String, List<String>> resolve(String domain)
                throws NamingException {
            Hashtable<String, String> environment = new Hashtable<>();
            environment.put(
                    Context.INITIAL_CONTEXT_FACTORY,
                    DNS_CONTEXT_FACTORY
            );
            environment.put(DNS_TIMEOUT_INITIAL, "2000");
            environment.put(DNS_TIMEOUT_RETRIES, "1");

            DirContext context = new InitialDirContext(environment);
            try {
                return Map.of(
                        "A",
                        queryValues(context, domain, "A"),
                        "AAAA",
                        queryValues(context, domain, "AAAA")
                );
            } finally {
                closeQuietly(context);
            }
        }

        private static List<String> queryValues(
                DirContext context,
                String domain,
                String recordType
        ) throws NamingException {
            try {
                Attributes attributes = context.getAttributes(
                        domain,
                        new String[]{recordType}
                );
                return readValues(attributes.get(recordType));
            } catch (NameNotFoundException error) {
                return List.of();
            }
        }

        private static List<String> readValues(Attribute attribute)
                throws NamingException {
            if (attribute == null) {
                return List.of();
            }
            List<String> values = new ArrayList<>();
            NamingEnumeration<?> entries = attribute.getAll();
            try {
                while (entries.hasMore()) {
                    values.add(String.valueOf(entries.next()));
                }
            } finally {
                entries.close();
            }
            return List.copyOf(values);
        }

        private static void closeQuietly(DirContext context) {
            try {
                context.close();
            } catch (NamingException ignored) {
                // DNS query outcome is already authoritative for this call.
            }
        }
    }
}
