package com.xa.mass.server.api.v1.workerdelivery;

import com.xa.mass.server.api.ApiTags;
import com.xa.mass.server.api.v1.model.ApiErrorResponse;
import com.xa.mass.server.api.v1.directcall.DirectCallHttpContract.DirectCallRequest;
import com.xa.mass.server.api.v1.directcall.DirectCallHttpContract.DirectCallResponse;
import com.xa.mass.server.delivery.directcall.DirectCallService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

@Tag(name = ApiTags.WORKER_DELIVERY)
@Validated
@RestController
@RequestMapping(
        "/api/v1/worker-delivery/endpoint-managers/{endpointManagerId}"
)
public class AdapterDirectCallController {

    private final DirectCallService directCalls;

    public AdapterDirectCallController(DirectCallService directCalls) {
        this.directCalls = directCalls;
    }

    @Operation(
            summary = "Call an Adapter or caller-selected Workers directly",
            description = "Best-effort direct execution. This API does not "
                    + "provide TASK exclusion, pause, drain, recall, or "
                    + "idempotency guarantees."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Direct Call completed with per-target outcomes",
                    content = @Content(schema = @Schema(
                            implementation = DirectCallResponse.class
                    ))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Direct Call request was rejected",
                    content = @Content(schema = @Schema(
                            implementation = ApiErrorResponse.class
                    ))
            ),
            @ApiResponse(
                    responseCode = "429",
                    description = "Direct Call admission capacity is exhausted",
                    content = @Content(schema = @Schema(
                            implementation = ApiErrorResponse.class
                    ))
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "Direct Call Owner is unavailable",
                    content = @Content(schema = @Schema(
                            implementation = ApiErrorResponse.class
                    ))
            )
    })
    @PostMapping("/direct-calls")
    public DeferredResult<ResponseEntity<DirectCallResponse>> call(
            @PathVariable @NotBlank String endpointManagerId,
            @Valid @RequestBody DirectCallRequest request
    ) {
        return directCalls.call(endpointManagerId, request);
    }
}
