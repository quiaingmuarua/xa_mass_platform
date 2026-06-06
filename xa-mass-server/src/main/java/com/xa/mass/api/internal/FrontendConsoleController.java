package com.xa.mass.api.internal;

import com.xa.mass.api.console.FrontendConsoleRoutingService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
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
            "/tasks/",
            "/tasks/{taskId}",
            "/tasks/{taskId}/",
            "/resources/projects",
            "/resources/projects/{projectCode}",
            "/resources/projects/{projectCode}/",
            "/resources/workers",
            "/resources/workers/{workerId}",
            "/resources/workers/{workerId}/",
            "/resources/rules",
            "/resources/configs",
            "/runtime/discovery",
            "/runtime/diagnostics",
            "/submitter-viewer",
            "/system/users",
            "/system/roles",
            "/system/api-keys",
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
