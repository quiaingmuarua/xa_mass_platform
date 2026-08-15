package com.xa.mass.server.api.v1;

import com.xa.mass.server.api.v1.control.ControlCallHttpContract.ControlBatchCallResponse;
import com.xa.mass.server.api.v1.control.ControlCallHttpContract.WorkerControlBatchCallRequest;
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
@RequestMapping("/api/v1/worker-groups")
public class WorkerControlController {

    private final ControlCallService controlCalls;

    public WorkerControlController(ControlCallService controlCalls) {
        this.controlCalls = controlCalls;
    }

    @PostMapping("/{workerGroupId}/workers/controls:call")
    public DeferredResult<ResponseEntity<ControlBatchCallResponse>>
            callWorkers(
            @PathVariable @NotBlank String workerGroupId,
            @Valid @RequestBody WorkerControlBatchCallRequest request
    ) {
        return controlCalls.callWorkers(
                workerGroupId,
                request
        );
    }
}
