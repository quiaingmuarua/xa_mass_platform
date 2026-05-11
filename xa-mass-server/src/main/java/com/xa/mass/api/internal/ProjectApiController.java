package com.xa.mass.api.internal;

import com.xa.mass.api.auth.ApiAuthInterceptor;
import com.xa.mass.api.model.ApiResponse;
import com.xa.mass.sdk.SubmitterOperations;
import com.xa.mass.sdk.auth.PrincipalContext;
import com.xa.mass.sdk.auth.PrincipalType;
import com.xa.mass.sdk.auth.SubmitterProfile;
import com.xa.mass.sdk.catalog.ProjectDefinition;
import com.xa.mass.sdk.catalog.ControlPlaneCatalog;
import com.xa.mass.sdk.event.EventDefinition;
import jakarta.servlet.http.HttpServletRequest;
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

    private final ControlPlaneCatalog catalog;
    private final SubmitterOperations submitterOperations;

    public ProjectApiController(ControlPlaneCatalog catalog) {
        this(catalog, (SubmitterOperations) null);
    }

    @Autowired
    public ProjectApiController(ControlPlaneCatalog catalog,
                                ObjectProvider<SubmitterOperations> submitterOperationsProvider) {
        this(
                catalog,
                submitterOperationsProvider == null ? null : submitterOperationsProvider.getIfAvailable()
        );
    }

    public ProjectApiController(ControlPlaneCatalog catalog,
                                SubmitterOperations submitterOperations) {
        this.catalog = catalog;
        this.submitterOperations = submitterOperations;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<ProjectDefinition>>> listProjects(HttpServletRequest request) {
        PrincipalContext scopedSubmitter = resolveScopedSubmitter(request);
        return ResponseEntity.ok(ApiResponse.success(catalog.listProjects().stream()
                .filter(project -> canViewProject(scopedSubmitter, project.getCode()))
                .toList()));
    }

    @GetMapping("/{projectCode}")
    public ResponseEntity<ApiResponse<ProjectDefinition>> getProject(@PathVariable String projectCode,
                                                                     HttpServletRequest request) {
        ProjectDefinition projectDefinition = catalog.getProject(projectCode);
        if (projectDefinition == null || !canViewProject(resolveScopedSubmitter(request), projectCode)) {
            return ResponseEntity.status(404)
                    .body(ApiResponse.error(404, "Project not found: " + projectCode));
        }
        return ResponseEntity.ok(ApiResponse.success(projectDefinition));
    }

    @GetMapping("/{projectCode}/events")
    public ResponseEntity<ApiResponse<List<EventDefinition>>> getProjectEvents(@PathVariable String projectCode,
                                                                               HttpServletRequest request) {
        ProjectDefinition projectDefinition = catalog.getProject(projectCode);
        PrincipalContext scopedSubmitter = resolveScopedSubmitter(request);
        if (projectDefinition == null || !canViewProject(scopedSubmitter, projectCode)) {
            return ResponseEntity.status(404)
                    .body(ApiResponse.error(404, "Project not found: " + projectCode));
        }
        return ResponseEntity.ok(ApiResponse.success(catalog.getEventsForProject(projectCode).stream()
                .filter(event -> canViewEvent(scopedSubmitter, event.getCode()))
                .toList()));
    }

    @GetMapping("/{projectCode}/submitters")
    public ResponseEntity<ApiResponse<List<SubmitterProfile>>> getProjectSubmitters(@PathVariable String projectCode,
                                                                                    HttpServletRequest request) {
        ProjectDefinition projectDefinition = catalog.getProject(projectCode);
        if (projectDefinition == null || !canViewProject(resolveScopedSubmitter(request), projectCode)) {
            return ResponseEntity.status(404)
                    .body(ApiResponse.error(404, "Project not found: " + projectCode));
        }
        if (submitterOperations == null) {
            return ResponseEntity.ok(ApiResponse.success(List.of()));
        }
        List<SubmitterProfile> submitters = submitterOperations.listSubmitters().stream()
                .filter(submitter -> supportsProject(submitter, projectCode))
                .sorted(Comparator.comparing(SubmitterProfile::getPrincipalId, String::compareToIgnoreCase))
                .toList();
        return ResponseEntity.ok(ApiResponse.success(submitters));
    }

    private PrincipalContext resolveScopedSubmitter(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        Object principal = request.getAttribute(ApiAuthInterceptor.AUTHENTICATED_PRINCIPAL_ATTR);
        if (!(principal instanceof PrincipalContext context)) {
            return null;
        }
        return context.getPrincipalType() == PrincipalType.OPERATOR ? null : context;
    }

    private boolean canViewProject(PrincipalContext principal, String projectCode) {
        return principal == null || principal.allowsProject(projectCode);
    }

    private boolean canViewEvent(PrincipalContext principal, String eventCode) {
        return principal == null || principal.allowsEvent(eventCode);
    }

    private boolean supportsProject(SubmitterProfile submitter, String projectCode) {
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
