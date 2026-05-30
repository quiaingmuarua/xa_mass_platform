package com.xa.mass.workerpack.sample.api;

import com.xa.mass.kernel.spi.rule.RuleDefinition;
import com.xa.mass.workerpack.sample.api.model.SampleCatalogBootstrapRequest;
import com.xa.mass.workerpack.sample.api.model.SampleRuleBootstrapRequest;
import com.xa.mass.sdk.MassSdkApplication;
import com.xa.mass.sdk.auth.SubmitterRegistration;
import com.xa.mass.sdk.catalog.PayloadType;
import com.xa.mass.sdk.catalog.ProjectDefinition;
import com.xa.mass.sdk.catalog.TaskMode;
import com.xa.mass.sdk.event.EventDefinition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Sample-only bootstrap write surface used by the external dev launcher.
 *
 * <p>This is intentionally scoped to the dev shell rather than promoted as a
 * platform-wide control-plane contract.
 */
@RestController
@Profile("dev")
@RequestMapping("/sample-api/bootstrap")
public class SampleBootstrapController {

    private static final String API_KEY_HEADER = "X-Sample-Bootstrap-Key";

    private final MassSdkApplication app;
    private final String bootstrapApiKey;

    public SampleBootstrapController(MassSdkApplication app,
                                     @Value("${sample.bootstrap.api-key:dev-bootstrap-key}") String bootstrapApiKey) {
        this.app = app;
        this.bootstrapApiKey = bootstrapApiKey;
    }

    @PostMapping("/catalog")
    public ResponseEntity<SampleApiResponse<?>> bootstrapCatalog(
            @RequestHeader(value = API_KEY_HEADER, required = false) String apiKey,
            @RequestBody SampleCatalogBootstrapRequest request) {
        ResponseEntity<SampleApiResponse<?>> authFailure = requireAuthorized(apiKey);
        if (authFailure != null) {
            return authFailure;
        }
        Objects.requireNonNull(request, "request");

        int eventCount = 0;
        for (SampleCatalogBootstrapRequest.EventRegistration event : request.getEvents()) {
            app.registerEventDefinition(EventDefinition.builder()
                    .code(event.getCode())
                    .name(event.getName())
                    .description(event.getDescription())
                    .payloadTypes(event.getPayloadTypes().stream().map(SampleBootstrapController::parsePayloadType).toList())
                    .taskModes(event.getTaskModes().stream().map(SampleBootstrapController::parseTaskMode).toList())
                    .enabled(event.isEnabled())
                    .defaultRoutingCode(event.getDefaultRoutingCode())
                    .projectCodes(event.getProjectCodes())
                    .build());
            eventCount += 1;
        }

        int projectCount = 0;
        for (SampleCatalogBootstrapRequest.ProjectRegistration project : request.getProjects()) {
            app.registerProject(ProjectDefinition.builder()
                    .code(project.getCode())
                    .name(project.getName())
                    .description(project.getDescription())
                    .enabled(project.isEnabled())
                    .eventCodes(project.getEventCodes())
                    .build());
            projectCount += 1;
        }

        int submitterCount = 0;
        for (SampleCatalogBootstrapRequest.SubmitterResource submitter : request.getSubmitters()) {
            app.registerSubmitter(SubmitterRegistration.builder()
                    .principalId(submitter.getPrincipalId())
                    .credential(submitter.getCredential())
                    .keyPrefix(submitter.getKeyPrefix())
                    .userId(submitter.getUserId())
                    .projectScope(submitter.getProjectScope())
                    .permissions(submitter.getPermissions())
                    .projectScopes(submitter.getProjectScopes())
                    .eventScopes(submitter.getEventScopes())
                    .enabled(submitter.isEnabled())
                    .attributes(submitter.getAttributes())
                    .build());
            submitterCount += 1;
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("events", eventCount);
        data.put("projects", projectCount);
        data.put("submitters", submitterCount);
        return ResponseEntity.ok(SampleApiResponse.success(data));
    }

    @PostMapping("/rules")
    public ResponseEntity<SampleApiResponse<?>> bootstrapRules(
            @RequestHeader(value = API_KEY_HEADER, required = false) String apiKey,
            @RequestBody SampleRuleBootstrapRequest request) {
        ResponseEntity<SampleApiResponse<?>> authFailure = requireAuthorized(apiKey);
        if (authFailure != null) {
            return authFailure;
        }
        Objects.requireNonNull(request, "request");
        List<RuleDefinition> rules = request.getRules();
        app.replaceDefaultRules(rules);
        return ResponseEntity.ok(SampleApiResponse.success(Map.of("rules", rules.size())));
    }

    private ResponseEntity<SampleApiResponse<?>> requireAuthorized(String apiKey) {
        if (bootstrapApiKey.equals(apiKey)) {
            return null;
        }
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(SampleApiResponse.error(401, "invalid sample bootstrap key"));
    }

    private static PayloadType parsePayloadType(String value) {
        return PayloadType.valueOf(requireEnumValue(value, "payloadType"));
    }

    private static TaskMode parseTaskMode(String value) {
        return TaskMode.valueOf(requireEnumValue(value, "taskMode"));
    }

    private static String requireEnumValue(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private record SampleApiResponse<T>(int code, String msg, T data) {
        private static <T> SampleApiResponse<T> success(T data) {
            return new SampleApiResponse<>(0, "ok", data);
        }

        private static <T> SampleApiResponse<T> error(int code, String msg) {
            return new SampleApiResponse<>(code, msg, null);
        }
    }
}
