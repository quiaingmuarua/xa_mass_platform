package com.xa.mass.api.internal;

import com.xa.mass.api.model.ApiResponse;
import com.xa.mass.sdk.catalog.EventMetadata;
import com.xa.mass.sdk.catalog.ProjectEventCatalog;
import com.xa.mass.sdk.catalog.ProjectMetadata;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read-only SDK/platform metadata endpoints.
 */
@RestController
@RequestMapping("/sdk/meta")
public class SdkMetadataController {

    private final ProjectEventCatalog projectEventCatalog;

    public SdkMetadataController(ProjectEventCatalog projectEventCatalog) {
        this.projectEventCatalog = projectEventCatalog;
    }

    @GetMapping("/projects")
    public ResponseEntity<ApiResponse<List<ProjectMetadata>>> listProjects() {
        return ResponseEntity.ok(ApiResponse.success(projectEventCatalog.listProjects()));
    }

    @GetMapping("/projects/{projectCode}")
    public ResponseEntity<ApiResponse<ProjectMetadata>> getProject(@PathVariable String projectCode) {
        ProjectMetadata projectMetadata = projectEventCatalog.getProject(projectCode);
        if (projectMetadata == null) {
            return ResponseEntity.status(404)
                    .body(ApiResponse.error(404, "Project metadata not found: " + projectCode));
        }
        return ResponseEntity.ok(ApiResponse.success(projectMetadata));
    }

    @GetMapping("/projects/{projectCode}/events")
    public ResponseEntity<ApiResponse<List<EventMetadata>>> getProjectEvents(@PathVariable String projectCode) {
        ProjectMetadata projectMetadata = projectEventCatalog.getProject(projectCode);
        if (projectMetadata == null) {
            return ResponseEntity.status(404)
                    .body(ApiResponse.error(404, "Project metadata not found: " + projectCode));
        }
        return ResponseEntity.ok(ApiResponse.success(projectEventCatalog.getEventsForProject(projectCode)));
    }

    @GetMapping("/events")
    public ResponseEntity<ApiResponse<List<EventMetadata>>> listEvents() {
        return ResponseEntity.ok(ApiResponse.success(projectEventCatalog.listEvents()));
    }

    @GetMapping("/events/{eventCode}")
    public ResponseEntity<ApiResponse<EventMetadata>> getEvent(@PathVariable String eventCode) {
        EventMetadata eventMetadata = projectEventCatalog.getEvent(eventCode);
        if (eventMetadata == null) {
            return ResponseEntity.status(404)
                    .body(ApiResponse.error(404, "Event metadata not found: " + eventCode));
        }
        return ResponseEntity.ok(ApiResponse.success(eventMetadata));
    }
}
