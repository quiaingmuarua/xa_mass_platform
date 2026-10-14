package com.xa.mass.server.api.v1.controller;

import com.xa.mass.server.api.v1.contract.ApiErrorResponse;
import com.xa.mass.server.api.ApiTags;
import com.xa.mass.server.task.access.TaskCaller;
import com.xa.mass.server.user.UserDirectory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@Tag(name = ApiTags.USERS)
@ApiResponse(responseCode = "200", description = "Current directory value")
@ApiResponse(responseCode = "400", description = "Invalid user, role, Project or update",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
public final class UserController {
    private final UserDirectory directory;
    public UserController(UserDirectory directory) { this.directory = directory; }

    @GetMapping("/users")
    @Operation(summary = "Read development users for the login chooser and administration")
    public List<UserDirectory.User> users() { return directory.users(); }

    @GetMapping("/roles")
    @Operation(summary = "Read immutable development role definitions loaded at Server startup")
    public List<UserDirectory.Role> roles() { return directory.roles(); }

    @PostMapping("/users")
    @Operation(summary = "Add a development user to this Server process without persisting it")
    public UserDirectory.User create(@Parameter(hidden = true) TaskCaller caller, @jakarta.validation.Valid @RequestBody UserDirectory.User input) {
        return directory.create(caller, input);
    }

    @PutMapping("/users/{userId}")
    @Operation(summary = "Replace a development user's name, role and Projects in Server memory")
    public UserDirectory.User update(@Parameter(hidden = true) TaskCaller caller, @PathVariable String userId,
            @jakarta.validation.Valid @RequestBody UserDirectory.UpdateUser input) {
        return directory.update(caller, userId, input);
    }
}
