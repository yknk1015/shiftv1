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

    // Removed: leave page (unused)


    @GetMapping("/warnings")
    public String warnings() {
        return "warnings";
    }

    // Simple mappings to expose existing templates for quick access
    @GetMapping("/calendar")
    public String calendar() { return "calendar"; }


    // Removed: skills page (replaced by skill-management)

    @GetMapping("/skill-management")
    public String skillManagement() { return "skill-management"; }

    @GetMapping("/employee-master")
    public String employeeMaster() { return "employee-master"; }

    // Removed: skill-patterns page (unused placeholder)

    @GetMapping("/pairing-settings")
    public String pairingSettings() { return "pairing-settings"; }

    @GetMapping("/admin-debug")
    public String adminDebug() { return "admin-debug"; }

    @GetMapping("/monthly-demand")
    public String monthlyDemand() { return "monthly-demand"; }
}
