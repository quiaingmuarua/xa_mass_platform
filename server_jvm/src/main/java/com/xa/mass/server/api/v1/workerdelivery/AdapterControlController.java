package com.xa.mass.server.api.v1.workerdelivery;

import com.xa.mass.server.api.v1.control.ControlCallHttpContract.ControlCallRequest;
import com.xa.mass.server.api.v1.control.ControlCallHttpContract.ControlBatchCallResponse;
import com.xa.mass.server.control.ControlCallService;
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

@Validated
@RestController
@RequestMapping(
        "/api/v1/worker-delivery/endpoint-managers/{endpointManagerId}"
)
public class AdapterControlController {

    private final ControlCallService controlCalls;

    public AdapterControlController(ControlCallService controlCalls) {
        this.controlCalls = controlCalls;
    }

    @PostMapping("/controls:call")
    public DeferredResult<ResponseEntity<ControlBatchCallResponse>>
            call(
            @PathVariable @NotBlank String endpointManagerId,
            @Valid @RequestBody ControlCallRequest request
    ) {
        return controlCalls.call(endpointManagerId, request);
    }

}
