package com.xa.mass.api.internal;

import com.xa.mass.api.console.FrontendConsoleRoutingService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

@Controller
public class FrontendConsoleController {

    private final FrontendConsoleRoutingService routingService;

    public FrontendConsoleController(FrontendConsoleRoutingService routingService) {
        this.routingService = routingService;
    }

    @GetMapping({
            "/status",
            "/status/",
            "/status/tasks",
            "/status/workers",
            "/status/rules",
            "/config"
    })
    public ResponseEntity<Void> redirectLegacyPage(HttpServletRequest request) {
        String localSpaPath = routingService.resolveSpaPath(request.getRequestURI());
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(localSpaPath))
                .build();
    }

    @GetMapping({
            "/",
            "/forbidden",
            "/tasks",
            "/tasks/{taskId}",
            "/resources/workers",
            "/resources/workers/{workerId}",
            "/resources/worker-contexts",
            "/resources/rules",
            "/resources/configs",
            "/runtime/diagnostics",
            "/system/users",
            "/system/roles",
            "/system/audit"
    })
    @ResponseBody
    public ResponseEntity<?> serveConsoleApp(HttpServletRequest request) {
        Optional<Resource> localIndex = routingService.loadLocalIndexHtml();
        if (localIndex.isPresent()) {
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_HTML)
                    .cacheControl(CacheControl.noCache())
                    .body(localIndex.get());
        }

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8")
                .body("""
                        Frontend console is unavailable.
                        Build frontend/dist before starting the backend-hosted console.
                        """.stripIndent().getBytes(StandardCharsets.UTF_8));
    }
}
