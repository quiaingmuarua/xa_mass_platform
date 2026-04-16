package com.xa.mass.api.internal;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Controller for the lightweight config page.
 */
@Controller
public class ConfigController {

    /**
     * Render the config page.
     */
    @GetMapping("/config")
    public String configPage() {
        return "config";
    }
}
