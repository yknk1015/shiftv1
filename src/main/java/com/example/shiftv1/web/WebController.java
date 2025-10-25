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

    @GetMapping("/demand-analytics")
    public String demandAnalytics() {
        return "demand-analytics";
    }

    @GetMapping("/breaks")
    public String breaks() {
        return "breaks";
    }

    @GetMapping("/leave")
    public String leave() {
        return "leave";
    }

    @GetMapping("/admin-actions")
    public String adminActions() {
        return "admin-actions";
    }

    // Simple mappings to expose existing templates for quick access
    @GetMapping("/calendar")
    public String calendar() { return "calendar"; }

    @GetMapping("/rules")
    public String rules() { return "rules"; }

    @GetMapping("/skills")
    public String skills() { return "skills"; }

    @GetMapping("/skill-management")
    public String skillManagement() { return "skill-management"; }

    @GetMapping("/employee-master")
    public String employeeMaster() { return "employee-master"; }

    @GetMapping("/skill-patterns")
    public String skillPatterns() { return "skill-patterns"; }
}
