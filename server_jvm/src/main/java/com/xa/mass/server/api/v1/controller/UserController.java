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
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
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

    @GetMapping("/me")
    @Operation(summary = "Read the authenticated user's directory record and role grants")
    public UserDirectory.CurrentUser me(@Parameter(hidden = true) TaskCaller caller) { return directory.current(caller); }

    @GetMapping("/users")
    @Operation(summary = "Read directory users; access-key presence only, never key material")
    public List<UserDirectory.UserView> users() { return directory.users(); }

    @GetMapping("/roles")
    @Operation(summary = "Read role definitions from the user directory file")
    public List<UserDirectory.Role> roles() { return directory.roles(); }

    @PostMapping("/users")
    @Operation(summary = "Add a user to the persisted directory without an access key")
    @ApiResponse(responseCode = "409", description = "Directory is read-only or changed outside this Server",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    public UserDirectory.UserView create(@Parameter(hidden = true) TaskCaller caller, @jakarta.validation.Valid @RequestBody UserDirectory.User input) {
        return directory.create(caller, input);
    }

    @PutMapping("/users/{userId}")
    @Operation(summary = "Replace another user's name, role and Projects in the persisted directory")
    @ApiResponse(responseCode = "409", description = "Directory is read-only or changed outside this Server",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    public UserDirectory.UserView update(@Parameter(hidden = true) TaskCaller caller, @PathVariable String userId,
            @jakarta.validation.Valid @RequestBody UserDirectory.UpdateUser input) {
        return directory.update(caller, userId, input);
    }

    @PostMapping("/users/{userId}/access-key")
    @Operation(summary = "Issue another user's access key, replacing any previous key; the key is returned only once")
    @ApiResponse(responseCode = "409", description = "Directory is read-only or changed outside this Server",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    public ResponseEntity<UserDirectory.IssuedAccessKey> issueAccessKey(@Parameter(hidden = true) TaskCaller caller,
            @PathVariable String userId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(directory.issueAccessKey(caller, userId));
    }

    @DeleteMapping("/users/{userId}/access-key")
    @Operation(summary = "Clear another user's access key; later requests with it are rejected")
    @ApiResponse(responseCode = "409", description = "Directory is read-only or changed outside this Server",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    public UserDirectory.UserView clearAccessKey(@Parameter(hidden = true) TaskCaller caller, @PathVariable String userId) {
        return directory.clearAccessKey(caller, userId);
    }
}
