package com.xa.mass.sms.backend;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public final class SmsFrontendController {
    @GetMapping({"/sms", "/sms/", "/sms/listeners", "/sms/listeners/", "/sms/metrics", "/sms/metrics/"})
    public String index() { return "forward:/sms/index.html"; }
}
