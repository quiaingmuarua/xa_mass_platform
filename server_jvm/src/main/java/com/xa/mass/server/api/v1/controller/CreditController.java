package com.xa.mass.server.api.v1.controller;

import com.xa.mass.server.api.ApiTags;
import com.xa.mass.server.api.v1.contract.ApiErrorResponse;
import com.xa.mass.server.api.v1.contract.credits.CreditAdjustmentRequest;
import com.xa.mass.server.credits.CreditModels.*;
import com.xa.mass.server.credits.CreditService;
import com.xa.mass.server.task.access.TaskCaller;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/users/{userId}/credits")
@Tag(name = ApiTags.USERS)
@ApiResponse(responseCode = "400", description = "Invalid credit request", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
@ApiResponse(responseCode = "404", description = "Neither directory user nor retained account exists", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
@ApiResponse(responseCode = "503", description = "Credit storage unavailable; a write may have committed, reconcile the original requestId", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
public final class CreditController {
    private final CreditService credits;
    public CreditController(CreditService credits) { this.credits = credits; }

    @GetMapping
    @Operation(summary = "Read a user's credit account")
    @ApiResponse(responseCode = "200", description = "Current account", content = @Content(schema = @Schema(implementation = CreditAccountResponse.class)))
    public CreditAccountResponse account(@Parameter(hidden = true) TaskCaller caller, @PathVariable String userId) {
        return credits.account(caller, userId);
    }
    @GetMapping("/transactions")
    @Operation(summary = "Read immutable credit transactions in descending account sequence")
    @ApiResponse(responseCode = "200", description = "Bounded transaction page", content = @Content(schema = @Schema(implementation = CreditTransactionsResponse.class)))
    public CreditTransactionsResponse transactions(@Parameter(hidden = true) TaskCaller caller, @PathVariable String userId,
            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "50") int limit) {
        return credits.transactions(caller, userId, cursor, limit);
    }
    @PostMapping("/adjustments")
    @Operation(summary = "Adjust a directory user's credits, including the administrator's own account",
            description = "requestId is the business idempotency identity, independent of HTTP X-Request-Id. Replay returns the original outcome; conflicting content is rejected. No Task or Scenario operation occurs.")
    @ApiResponse(responseCode = "409", description = "Insufficient balance, safe-integer limit or idempotency conflict", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "200", description = "Confirmed adjustment", content = @Content(schema = @Schema(implementation = CreditAdjustmentResponse.class)))
    public CreditAdjustmentResponse adjust(@Parameter(hidden = true) TaskCaller caller, @PathVariable String userId,
            @Valid @RequestBody CreditAdjustmentRequest input) {
        return credits.adjust(caller, userId, input.normalized());
    }
    @GetMapping("/adjustments/{requestId}")
    @Operation(summary = "Reconcile an adjustment by its original business requestId",
            description = "Returns applied, rejected, or not_observed. Absence does not prove that an in-flight request cannot commit.")
    @ApiResponse(responseCode = "200", description = "Original adjustment observation", content = @Content(schema = @Schema(implementation = CreditAdjustmentResponse.class)))
    public CreditAdjustmentResponse adjustment(@Parameter(hidden = true) TaskCaller caller, @PathVariable String userId,
            @PathVariable String requestId) {
        return credits.adjustment(caller, userId, requestId);
    }
}
