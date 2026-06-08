package com.xa.mass.api.internal;

import com.xa.mass.api.auth.ApiAuthInterceptor;
import com.xa.mass.api.model.ApiResponse;
import com.xa.mass.sdk.auth.PrincipalContext;
import com.xa.mass.sdk.auth.PrincipalType;
import com.xa.mass.sdk.catalog.ProjectDefinition;
import com.xa.mass.sdk.catalog.ControlPlaneCatalog;
import com.xa.mass.sdk.event.EventDefinition;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/projects")
public class ProjectApiController {

    private final ControlPlaneCatalog catalog;

    public ProjectApiController(ControlPlaneCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<ProjectDefinition>>> listProjects(HttpServletRequest request) {
        PrincipalContext scopedApiKeyPrincipal = resolveScopedApiKeyPrincipal(request);
        return ResponseEntity.ok(ApiResponse.success(catalog.listProjects().stream()
                .filter(project -> canViewProject(scopedApiKeyPrincipal, project.getCode()))
                .toList()));
    }

    @GetMapping("/{projectCode}")
    public ResponseEntity<ApiResponse<ProjectDefinition>> getProject(@PathVariable String projectCode,
                                                                     HttpServletRequest request) {
        ProjectDefinition projectDefinition = catalog.getProject(projectCode);
        if (projectDefinition == null || !canViewProject(resolveScopedApiKeyPrincipal(request), projectCode)) {
            return ResponseEntity.status(404)
                    .body(ApiResponse.error(404, "Project not found: " + projectCode));
        }
        return ResponseEntity.ok(ApiResponse.success(projectDefinition));
    }

    @GetMapping("/{projectCode}/events")
    public ResponseEntity<ApiResponse<List<EventDefinition>>> getProjectEvents(@PathVariable String projectCode,
                                                                               HttpServletRequest request) {
        ProjectDefinition projectDefinition = catalog.getProject(projectCode);
        PrincipalContext scopedApiKeyPrincipal = resolveScopedApiKeyPrincipal(request);
        if (projectDefinition == null || !canViewProject(scopedApiKeyPrincipal, projectCode)) {
            return ResponseEntity.status(404)
                    .body(ApiResponse.error(404, "Project not found: " + projectCode));
        }
        return ResponseEntity.ok(ApiResponse.success(catalog.getEventsForProject(projectCode).stream()
                .filter(event -> canViewEvent(scopedApiKeyPrincipal, event.getCode()))
                .toList()));
    }

    private PrincipalContext resolveScopedApiKeyPrincipal(HttpServletRequest request) {
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

}
