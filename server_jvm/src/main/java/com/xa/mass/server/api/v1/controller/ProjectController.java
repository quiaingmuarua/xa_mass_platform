package com.xa.mass.server.api.v1.controller;

import com.xa.mass.server.api.ApiTags;
import com.xa.mass.server.api.v1.contract.ApiErrorResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import com.xa.mass.server.project.ProjectDirectory;
import com.xa.mass.server.project.ProjectTaskQueryService;
import com.xa.mass.server.project.ProjectWorkerGroupQueryService;
import java.util.List;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = ApiTags.TASKS)
@RequestMapping("/api/v1/projects")
@ApiResponses({
        @ApiResponse(responseCode = "400", description = "Project or limit is invalid",
                content = @Content(schema = @Schema(
                        implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "503", description = "Task Owner is unavailable",
                content = @Content(schema = @Schema(
                        implementation = ApiErrorResponse.class)))
})
public final class ProjectController {
    private final ProjectDirectory projects;
    private final ProjectTaskQueryService tasks;
    private final ProjectWorkerGroupQueryService groups;

    public ProjectController(ProjectDirectory projects, ProjectTaskQueryService tasks,
            ProjectWorkerGroupQueryService groups) {
        this.projects = projects;
        this.tasks = tasks;
        this.groups = groups;
    }

    @Operation(summary = "Read the immutable startup Project directory without Runtime reads")
    @GetMapping
    @ApiResponse(responseCode = "200", description = "Configured Project directory")
    public List<ProjectDirectory.ProjectView> list() {
        return List.copyOf(projects.projects().values());
    }

    @Operation(summary = "Point-read a known Task in a configured Project")
    @GetMapping("/{projectId}/tasks/{taskId}")
    @ApiResponse(responseCode = "200", description = "Known Project Task")
    public ProjectTaskQueryService.Entry task(@PathVariable String projectId, @PathVariable String taskId) {
        return tasks.get(projectId, taskId);
    }

    @Operation(summary = "Read bound Group descriptors and Matching enablement, without Worker discovery")
    @GetMapping("/{projectId}/worker-groups")
    @ApiResponse(responseCode = "200", description = "Bound Group evidence and Matching enablement")
    public List<ProjectWorkerGroupQueryService.ProjectWorkerGroup> groups(@PathVariable String projectId) {
        return groups.list(projectId);
    }

    @Operation(summary = "Read a configured Project and its managed Task coordinates")
    @GetMapping("/{projectId}")
    @ApiResponse(responseCode = "200", description = "Configured Project",
            content = @Content(schema = @Schema(
                    implementation = ProjectDirectory.ProjectView.class)))
    public ProjectDirectory.ProjectView get(@PathVariable String projectId) {
        return projects.require(projectId);
    }

    @Operation(summary = "Read newest Project Tasks, without pagination or Result reads")
    @GetMapping("/{projectId}/tasks")
    @ApiResponse(responseCode = "200", description = "Project Task window",
            content = @Content(schema = @Schema(
                    implementation = ProjectTaskQueryService.ProjectTasks.class)))
    public ProjectTaskQueryService.ProjectTasks tasks(@PathVariable String projectId,
            @RequestParam(defaultValue = "100") int limit) {
        return tasks.list(projectId, limit);
    }
}
