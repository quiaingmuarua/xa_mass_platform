package com.xa.mass.api.internal;

import com.xa.mass.base.enums.Project;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.ui.Model;

/**
 * Controller for the lightweight config page.
 */
@Controller
public class ConfigController {

    /**
     * Render the config page.
     */
    @GetMapping("/config")
    public String configPage(Model model) {
        model.addAttribute("projectCodes", Project.getAllCodes());
        return "config";
    }
}
