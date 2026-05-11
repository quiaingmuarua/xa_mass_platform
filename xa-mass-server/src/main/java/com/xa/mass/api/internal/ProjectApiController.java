package com.xa.mass.api.internal;

import com.xa.mass.api.model.ApiResponse;
import com.xa.mass.sdk.SubmitterOperations;
import com.xa.mass.sdk.auth.PrincipalContext;
import com.xa.mass.sdk.auth.SubmitterMetadata;
import com.xa.mass.sdk.catalog.ProjectMetadata;
import com.xa.mass.sdk.catalog.SdkMetadataCatalog;
import com.xa.mass.sdk.event.EventDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

@RestController
@RequestMapping("/api/v1/projects")
public class ProjectApiController {

    private final SdkMetadataCatalog metadataCatalog;
    private final SubmitterOperations submitterOperations;

    public ProjectApiController(SdkMetadataCatalog metadataCatalog) {
        this(metadataCatalog, (SubmitterOperations) null);
    }

    @Autowired
    public ProjectApiController(SdkMetadataCatalog metadataCatalog,
                                ObjectProvider<SubmitterOperations> submitterOperationsProvider) {
        this(
                metadataCatalog,
                submitterOperationsProvider == null ? null : submitterOperationsProvider.getIfAvailable()
        );
    }

    public ProjectApiController(SdkMetadataCatalog metadataCatalog,
                                SubmitterOperations submitterOperations) {
        this.metadataCatalog = metadataCatalog;
        this.submitterOperations = submitterOperations;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<ProjectMetadata>>> listProjects() {
        return ResponseEntity.ok(ApiResponse.success(metadataCatalog.listProjects()));
    }

    @GetMapping("/{projectCode}")
    public ResponseEntity<ApiResponse<ProjectMetadata>> getProject(@PathVariable String projectCode) {
        ProjectMetadata projectMetadata = metadataCatalog.getProject(projectCode);
        if (projectMetadata == null) {
            return ResponseEntity.status(404)
                    .body(ApiResponse.error(404, "Project metadata not found: " + projectCode));
        }
        return ResponseEntity.ok(ApiResponse.success(projectMetadata));
    }

    @GetMapping("/{projectCode}/events")
    public ResponseEntity<ApiResponse<List<EventDefinition>>> getProjectEvents(@PathVariable String projectCode) {
        ProjectMetadata projectMetadata = metadataCatalog.getProject(projectCode);
        if (projectMetadata == null) {
            return ResponseEntity.status(404)
                    .body(ApiResponse.error(404, "Project metadata not found: " + projectCode));
        }
        return ResponseEntity.ok(ApiResponse.success(metadataCatalog.getEventsForProject(projectCode)));
    }

    @GetMapping("/{projectCode}/submitters")
    public ResponseEntity<ApiResponse<List<SubmitterMetadata>>> getProjectSubmitters(@PathVariable String projectCode) {
        ProjectMetadata projectMetadata = metadataCatalog.getProject(projectCode);
        if (projectMetadata == null) {
            return ResponseEntity.status(404)
                    .body(ApiResponse.error(404, "Project metadata not found: " + projectCode));
        }
        if (submitterOperations == null) {
            return ResponseEntity.ok(ApiResponse.success(List.of()));
        }
        List<SubmitterMetadata> submitters = submitterOperations.listSubmitters().stream()
                .filter(submitter -> supportsProject(submitter, projectCode))
                .sorted(Comparator.comparing(SubmitterMetadata::getPrincipalId, String::compareToIgnoreCase))
                .toList();
        return ResponseEntity.ok(ApiResponse.success(submitters));
    }

    private boolean supportsProject(SubmitterMetadata submitter, String projectCode) {
        if (submitter == null || projectCode == null || projectCode.isBlank()) {
            return false;
        }
        String normalizedProjectCode = projectCode.trim();
        if (normalizedProjectCode.equalsIgnoreCase(submitter.getProjectScope())) {
            return true;
        }
        return submitter.getProjectScopes().stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .anyMatch(scope -> PrincipalContext.WILDCARD_SCOPE.equals(scope)
                        || normalizedProjectCode.equalsIgnoreCase(scope));
    }
}
