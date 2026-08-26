package com.xa.mass.server.frontend;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public final class FrontendController {

    @GetMapping({
            "/",
            "/runtime/workers",
            "/runtime/workers/",
            "/runtime/tasks",
            "/runtime/tasks/",
            "/api-reference",
            "/api-reference/",
            "/reference/error-codes",
            "/reference/error-codes/",
    })
    public String index() {
        return "forward:/index.html";
    }
}
