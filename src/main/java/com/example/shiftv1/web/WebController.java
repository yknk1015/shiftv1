package com.example.shiftv1.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class WebController {

    @GetMapping("/login")
    public String login() {
        return "login";
    }

    @GetMapping("/dashboard")
    public String dashboard() {
        return "dashboard";
    }

    @GetMapping("/timeline")
    public String timeline() {
        return "timeline"; // renders src/main/resources/templates/timeline.html
    }

    @GetMapping("/demand")
    public String demand() {
        return "demand";
    }

    @GetMapping("/breaks")
    public String breaks() {
        return "breaks";
    }

    @GetMapping("/leave")
    public String leave() {
        return "leave";
    }
}
