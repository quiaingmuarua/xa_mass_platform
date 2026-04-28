package com.xa.mass.api.internal;

import com.xa.mass.sdk.TransportOperations;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/message")
@Tag(name = "Message Dispatch")
public class MessageController {

    private final TransportOperations transportOperations;

    public MessageController(TransportOperations transportOperations) {
        this.transportOperations = transportOperations;
    }

    @PostMapping("/send")
    @Operation(summary = "Push a raw message envelope into the outbound transporter")
    public Map<String, Object> sendMessage(@RequestBody Map<String, Object> req) {
        return success(new HashMap<>(transportOperations.enqueueRawMessage(req)));
    }

    private Map<String, Object> success(Object data) {
        Map<String, Object> resp = new HashMap<>();
        resp.put("code", 0);
        resp.put("msg", "ok");
        resp.put("data", data);
        return resp;
    }
}
