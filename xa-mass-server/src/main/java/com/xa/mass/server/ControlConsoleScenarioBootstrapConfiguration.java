package com.xa.mass.server;

import com.xa.mass.sdk.MassSdkApplication;
import com.xa.mass.sdk.auth.PrincipalContext;
import com.xa.mass.sdk.auth.SubmitterRegistration;
import com.xa.mass.sdk.catalog.PayloadType;
import com.xa.mass.sdk.catalog.ProjectDefinition;
import com.xa.mass.sdk.catalog.TaskMode;
import com.xa.mass.sdk.event.EventDefinition;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.util.List;
import java.util.Map;

/**
 * Mainline dev-shell bootstrap for the backend-hosted control-console scenario.
 */
@Configuration
@Profile("dev")
@ConditionalOnProperty(prefix = "mass.control-console.scenario", name = "enabled", havingValue = "true")
public class ControlConsoleScenarioBootstrapConfiguration {

    private static final List<String> PUBLIC_PROBE_EVENT_CODES = List.of(
            "probe.weather.current",
            "probe.fx.latest",
            "probe.crypto.price",
            "probe.ip.geo",
            "probe.url.dns",
            "probe.http.status"
    );
    private static final List<String> DEVICE_PROBE_EVENT_CODES = List.of(
            "probe.phone.metadata"
    );
    private static final List<String> DATA_QUALITY_EVENT_CODES = List.of(
            "probe.market.daily-csv",
            "probe.csv.validate",
            "probe.json.schema"
    );

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public CommandLineRunner controlConsoleScenarioCatalogBootstrapRunner(MassSdkApplication app) {
        return args -> {
            registerProbeEvents(app);
            app.registerProject(ProjectDefinition.builder()
                    .code("publicProbe")
                    .name("Public Probe")
                    .description("Public API, DNS, HTTP, price, and network reachability probes.")
                    .eventCodes(PUBLIC_PROBE_EVENT_CODES)
                    .build());
            app.registerProject(ProjectDefinition.builder()
                    .code("deviceProbe")
                    .name("Device Probe")
                    .description("Phone metadata and device fingerprint routing probes.")
                    .eventCodes(DEVICE_PROBE_EVENT_CODES)
                    .build());
            app.registerProject(ProjectDefinition.builder()
                    .code("dataQualityProbe")
                    .name("Data Quality Probe")
                    .description("Local CSV and JSON validation probes.")
                    .eventCodes(DATA_QUALITY_EVENT_CODES)
                    .build());
        };
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 1)
    public CommandLineRunner controlConsoleScenarioSubmitterBootstrapRunner(MassSdkApplication app) {
        return args -> {
            app.registerSubmitter(SubmitterRegistration.builder()
                    .principalId("public-probe-runner")
                    .credential("public-probe-key")
                    .userId("public-probe-user")
                    .projectScope("publicProbe")
                    .eventScopes(PUBLIC_PROBE_EVENT_CODES)
                    .attributes(Map.of("label", "Public Probe Runner"))
                    .build());
            app.registerSubmitter(SubmitterRegistration.builder()
                    .principalId("device-probe-runner")
                    .credential("device-probe-key")
                    .userId("device-probe-user")
                    .projectScope("deviceProbe")
                    .eventScopes(DEVICE_PROBE_EVENT_CODES)
                    .attributes(Map.of("label", "Device Probe Runner"))
                    .build());
            app.registerSubmitter(SubmitterRegistration.builder()
                    .principalId("data-quality-runner")
                    .credential("data-quality-key")
                    .userId("data-quality-user")
                    .projectScope("dataQualityProbe")
                    .eventScopes(DATA_QUALITY_EVENT_CODES)
                    .attributes(Map.of("label", "Data Quality Runner"))
                    .build());
            app.registerSubmitter(SubmitterRegistration.builder()
                    .principalId("public-probe-reviewer")
                    .credential("public-probe-reviewer-key")
                    .userId("public-probe-reviewer")
                    .permissions(List.of("task:govern"))
                    .projectScopes(List.of("publicProbe", "deviceProbe", "dataQualityProbe"))
                    .eventScopes(allProbeEvents())
                    .attributes(Map.of("label", "Public Probe Reviewer"))
                    .build());
            app.registerSubmitter(SubmitterRegistration.builder()
                    .principalId("public-probe-ops")
                    .credential("public-probe-ops-key")
                    .userId("public-probe-ops")
                    .permissions(List.of(
                            PrincipalContext.EXTERNAL_WORKER_PERMISSION,
                            PrincipalContext.TASK_CREATE_PERMISSION,
                            "task:control",
                            "task:govern"
                    ))
                    .projectScopes(List.of("publicProbe", "deviceProbe", "dataQualityProbe"))
                    .eventScopes(allProbeEvents())
                    .attributes(Map.of("label", "Public Probe Ops"))
                    .build());
        };
    }

    private void registerProbeEvents(MassSdkApplication app) {
        registerEvent(app, "probe.weather.current", "Weather Current Probe",
                "Fetches or validates current weather JSON and checks required weather fields.",
                List.of("publicProbe"));
        registerEvent(app, "probe.fx.latest", "FX Latest Probe",
                "Fetches or validates latest FX rates and checks selected positive currency rates.",
                List.of("publicProbe"));
        registerEvent(app, "probe.crypto.price", "Crypto Price Probe",
                "Fetches or validates selected crypto prices and checks positive numeric values.",
                List.of("publicProbe"));
        registerEvent(app, "probe.ip.geo", "IP Geo Probe",
                "Checks egress or target IP metadata such as country and ASN facts.",
                List.of("publicProbe"));
        registerEvent(app, "probe.url.dns", "URL DNS Probe",
                "Parses URLs and classifies DNS, timeout, TLS, and HTTP reachability outcomes.",
                List.of("publicProbe"));
        registerEvent(app, "probe.http.status", "HTTP Status Probe",
                "Checks HTTP status, delay, headers, timeout, and retry behavior.",
                List.of("publicProbe"));
        registerEvent(app, "probe.phone.metadata", "Phone Metadata Probe",
                "Parses phone numbers into E.164, region, type, and possible/valid metadata.",
                List.of("deviceProbe"));
        registerEvent(app, "probe.market.daily-csv", "Market Daily CSV Probe",
                "Validates market CSV columns and latest positive close price values.",
                List.of("dataQualityProbe"));
        registerEvent(app, "probe.csv.validate", "CSV Validate Probe",
                "Validates local CSV shape, row count, required columns, and numeric ranges.",
                List.of("dataQualityProbe"));
        registerEvent(app, "probe.json.schema", "JSON Schema Probe",
                "Validates local JSON fixtures against required fields and type checks.",
                List.of("dataQualityProbe"));
    }

    private void registerEvent(MassSdkApplication app,
                               String code,
                               String name,
                               String description,
                               List<String> projectCodes) {
        app.registerEventDefinition(EventDefinition.builder()
                .code(code)
                .name(name)
                .description(description)
                .payloadTypes(List.of(PayloadType.JSON))
                .taskModes(List.of(TaskMode.SINGLE_RUN, TaskMode.STREAMING))
                .projectCodes(projectCodes)
                .build());
    }

    private List<String> allProbeEvents() {
        return java.util.stream.Stream.of(
                        PUBLIC_PROBE_EVENT_CODES,
                        DEVICE_PROBE_EVENT_CODES,
                        DATA_QUALITY_EVENT_CODES)
                .flatMap(List::stream)
                .toList();
    }
}
