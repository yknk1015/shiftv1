package com.example.shiftv1;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.HashMap;
import java.util.Map;
import java.time.YearMonth;

@Controller
public class HomeController {

    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("application", "Shift Scheduler Demo");
        model.addAttribute("description", "シフト自動作成デモアプリケーション");
        YearMonth current = YearMonth.now();
        model.addAttribute("defaultYear", current.getYear());
        model.addAttribute("defaultMonth", current.getMonthValue());
        return "index";
    }

    @GetMapping("/employees")
    public String employees() {
        return "employees";
    }

    @GetMapping("/schedule")
    public String schedule(Model model) {
        YearMonth current = YearMonth.now();
        model.addAttribute("defaultYear", current.getYear());
        model.addAttribute("defaultMonth", current.getMonthValue());
        return "schedule";
    }

    @GetMapping("/stats")
    public String stats(Model model) {
        YearMonth current = YearMonth.now();
        model.addAttribute("defaultYear", current.getYear());
        model.addAttribute("defaultMonth", current.getMonthValue());
        return "stats";
    }

    @GetMapping("/api")
    @ResponseBody
    public Map<String, Object> apiInfo() {
        Map<String, Object> response = new HashMap<>();
        response.put("application", "Shift Scheduler Demo");
        response.put("version", "1.0.0");
        response.put("description", "シフト自動作成デモアプリケーション");

        Map<String, Object> endpoints = new HashMap<>();

        Map<String, String> scheduleEndpoints = new HashMap<>();
        scheduleEndpoints.put("シフト生成", "POST /api/schedule/generate?year=2024&month=7");
        scheduleEndpoints.put("シフト取得", "GET /api/schedule?year=2024&month=7");
        scheduleEndpoints.put("月次統計", "GET /api/schedule/stats/monthly?year=2024&month=7");
        scheduleEndpoints.put("従業員別勤務量", "GET /api/schedule/stats/employee-workload?year=2024&month=7");
        scheduleEndpoints.put("シフト分布", "GET /api/schedule/stats/shift-distribution?year=2024&month=7");

        Map<String, String> employeeEndpoints = new HashMap<>();
        employeeEndpoints.put("全従業員取得", "GET /api/employees");
        employeeEndpoints.put("従業員取得", "GET /api/employees/{id}");
        employeeEndpoints.put("従業員作成", "POST /api/employees");
        employeeEndpoints.put("従業員更新", "PUT /api/employees/{id}");
        employeeEndpoints.put("従業員削除", "DELETE /api/employees/{id}");
        employeeEndpoints.put("従業員数取得", "GET /api/employees/count");
        
        endpoints.put("schedule", scheduleEndpoints);
        endpoints.put("employees", employeeEndpoints);
        response.put("availableEndpoints", endpoints);

        response.put("note", "yearとmonthパラメータは省略可能です");

        return response;
    }
}
