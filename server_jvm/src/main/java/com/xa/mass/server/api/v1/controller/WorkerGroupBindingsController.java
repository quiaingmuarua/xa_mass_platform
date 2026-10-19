package com.xa.mass.server.api.v1.controller;

import com.xa.mass.server.api.ApiTags;
import com.xa.mass.server.api.RequestIdFilter;
import com.xa.mass.server.api.v1.contract.runtimeview.WorkerGroupBindingsResponse;
import com.xa.mass.server.api.v1.contract.runtimeview.WorkerGroupBindingsDirectoryResponse;
import com.xa.mass.server.api.v1.contract.ApiErrorResponse;
import com.xa.mass.server.runtimeview.WorkerGroupBindingsQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@Tag(name = ApiTags.RUNTIME_VIEW)
@RestController
@RequestMapping("/api/v1/runtime-view/worker-groups")
public class WorkerGroupBindingsController {
    private final WorkerGroupBindingsQueryService bindings;
    public WorkerGroupBindingsController(WorkerGroupBindingsQueryService bindings) { this.bindings = bindings; }

    @GetMapping("/bindings")
    @Operation(summary = "List startup-associated Groups without Redis, Facts or inventory reads")
    public WorkerGroupBindingsDirectoryResponse directory() { return bindings.directory(); }

    @GetMapping("/{workerGroupId}/bindings")
    @Operation(summary = "Read effective WorkerGroup Pool and feedback bindings without reading Facts or stock")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Registered Group and immutable startup bindings",
                content = @Content(schema = @Schema(implementation = WorkerGroupBindingsResponse.class))),
        @ApiResponse(responseCode = "400", description = "Invalid Group identity",
                content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "503", description = "Group Owner read unavailable",
                content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    public WorkerGroupBindingsResponse load(@PathVariable String workerGroupId, HttpServletRequest request) {
        return bindings.load(workerGroupId, (String) request.getAttribute(RequestIdFilter.ATTRIBUTE_NAME));
    }
}
