package com.xa.mass.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class AdminApiHealthService {
    private static final long DEFAULT_BUDGET_MS = 1_000L;

    private final ObjectMapper objectMapper;
    private final Clock clock;

    AdminApiHealthService(ObjectMapper objectMapper, Clock clock) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    Map<String, Object> health(AdminEnvConfig.Loaded loaded) {
        Objects.requireNonNull(loaded, "loaded config is required");
        AdminHttpClient operatorClient = client(loaded);
        AdminHttpClient anonymousClient = client(loaded);
        Instant startedAt = clock.instant();
        AuthConfig authConfig = operatorClient.authConfig();
        if (!"session".equalsIgnoreCase(authConfig.authMode()) || !authConfig.sessionCookieSupported()) {
            throw new EnvInitFailure("operator-auth/readiness",
                    "api health requires session operator auth; actual authMode=" + authConfig.authMode());
        }
        operatorClient.login(loaded.config().operator().user(), loaded.operatorPassword());
        operatorClient.requireCurrentOperator();

        List<Map<String, Object>> routeTimings = new ArrayList<>();
        boolean failed = false;
        boolean warning = false;
        for (RouteHealthSpec spec : RouteHealthSpec.repeatableReadRoutes()) {
            AdminHttpClient routeClient = "none".equals(spec.credentialUsedByHealthRunner())
                    ? anonymousClient
                    : operatorClient;
            RouteHttpResponse response = routeClient.getRoute(spec.path());
            RouteHealthStatus status = classify(spec, response);
            failed = failed || status == RouteHealthStatus.FAILED;
            warning = warning || status == RouteHealthStatus.WARNING;
            routeTimings.add(toRouteTiming(spec, response, status));
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("status", (failed || warning) ? "failed" : "passed");
        report.put("gateMode", "hard");
        report.put("startedAt", startedAt.toString());
        report.put("completedAt", clock.instant().toString());
        report.put("profile", loaded.config().server().profile());
        report.put("budgetMs", DEFAULT_BUDGET_MS);
        report.put("routeTimings", routeTimings);
        report.put("routeManifestVersion", 1);
        report.put("exactDtoContractChecked", false);
        report.put("note", "LRAH route timings fail the local readiness gate when a selected route exceeds budget.");
        if (failed || warning) {
            throw new ApiHealthFailure(report);
        }
        return report;
    }

    private AdminHttpClient client(AdminEnvConfig.Loaded loaded) {
        return new AdminHttpClient(
                loaded.config().server().baseUrl(),
                loaded.config().server().connectTimeout(),
                loaded.config().server().requestTimeout(),
                objectMapper
        );
    }

    private Map<String, Object> toRouteTiming(RouteHealthSpec spec,
                                              RouteHttpResponse response,
                                              RouteHealthStatus status) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("method", spec.method());
        item.put("path", spec.path());
        item.put("routeAuthPolicy", spec.routeAuthPolicy());
        item.put("credentialUsedByHealthRunner", spec.credentialUsedByHealthRunner());
        item.put("readOrWrite", spec.readOrWrite());
        item.put("sourceCommand", spec.sourceCommand());
        item.put("normalDataPresence", spec.normalDataPresence());
        item.put("repeatable", spec.repeatable());
        item.put("budgetMs", spec.budgetMs());
        item.put("elapsedMs", response.elapsedMs());
        item.put("httpStatus", response.httpStatus());
        item.put("code", response.code());
        item.put("responseBytes", response.responseBytes());
        item.put("status", status.name().toLowerCase(java.util.Locale.ROOT));
        if (!response.message().isBlank()) {
            item.put("message", response.message());
        }
        return item;
    }

    private RouteHealthStatus classify(RouteHealthSpec spec, RouteHttpResponse response) {
        if (!response.successEnvelope() || !hasNormalData(spec, response.data())) {
            return RouteHealthStatus.FAILED;
        }
        return response.elapsedMs() >= spec.budgetMs() ? RouteHealthStatus.WARNING : RouteHealthStatus.PASSED;
    }

    private boolean hasNormalData(RouteHealthSpec spec, JsonNode data) {
        if (data == null || data.isMissingNode() || data.isNull()) {
            return false;
        }
        return switch (spec.path()) {
            case "/api/v1/auth/config" -> !data.path("authMode").asText("").isBlank();
            case "/api/v1/projects", "/api/v1/catalog/events",
                    "/api/v1/catalog/worker-capabilities",
                    "/api/v1/catalog/worker-group-capabilities" -> data.isArray() && !data.isEmpty();
            case "/api/v1/admin/rules" -> {
                JsonNode items = data.path("items");
                yield items.isArray() && !items.isEmpty();
            }
            case "/api/v1/runtime/workers" -> {
                JsonNode items = data.path("items");
                yield items.isArray() && !items.isEmpty();
            }
            default -> true;
        };
    }

    private enum RouteHealthStatus {
        PASSED,
        WARNING,
        FAILED
    }

    record RouteHealthSpec(String method,
                           String path,
                           String routeAuthPolicy,
                           String credentialUsedByHealthRunner,
                           String readOrWrite,
                           String sourceCommand,
                           String normalDataPresence,
                           boolean repeatable,
                           long budgetMs) {
        static List<RouteHealthSpec> repeatableReadRoutes() {
            return List.of(
                    read("/api/v1/auth/config", "public", "none", "auth mode exists"),
                    read("/api/v1/projects", "operator or SDK read", "operator-session", "configured projects exist"),
                    read("/api/v1/catalog/events", "SDK_CREDENTIAL_BYPASS read", "none", "configured events exist"),
                    read("/api/v1/admin/rules", "operator-session", "operator-session", "configured rules exist"),
                    read("/api/v1/runtime/workers", "operator-session", "operator-session", "initialized worker rows exist"),
                    read("/api/v1/catalog/worker-capabilities", "SDK_CREDENTIAL_BYPASS read", "none", "worker capability rows exist"),
                    read("/api/v1/catalog/worker-group-capabilities", "SDK_CREDENTIAL_BYPASS read", "none", "worker group capability rows exist")
            );
        }

        private static RouteHealthSpec read(String path,
                                            String routeAuthPolicy,
                                            String credentialUsedByHealthRunner,
                                            String normalDataPresence) {
            return new RouteHealthSpec(
                    "GET",
                    path,
                    routeAuthPolicy,
                    credentialUsedByHealthRunner,
                    "read",
                    "admin CLI api health",
                    normalDataPresence,
                    true,
                    DEFAULT_BUDGET_MS
            );
        }
    }
}

final class ApiHealthFailure extends RuntimeException {
    private final Map<String, Object> report;

    ApiHealthFailure(Map<String, Object> report) {
        super("api health failed");
        this.report = Map.copyOf(report);
    }

    Map<String, Object> report() {
        return report;
    }
}
