package com.example.shiftv1;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
public class HomeController {

    @GetMapping("/")
    public Map<String, Object> home() {
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
        
        Map<String, String> adminEndpoints = new HashMap<>();
        adminEndpoints.put("システム状態確認", "GET /api/admin/status");
        adminEndpoints.put("従業員初期化", "POST /api/admin/initialize-employees");
        adminEndpoints.put("従業員リセット", "DELETE /api/admin/reset-employees");
        
        endpoints.put("schedule", scheduleEndpoints);
        endpoints.put("employees", employeeEndpoints);
        endpoints.put("admin", adminEndpoints);
        response.put("availableEndpoints", endpoints);
        
        response.put("note", "yearとmonthパラメータは省略可能です");
        response.put("dashboard", "http://localhost:8080/dashboard");
        
        return response;
    }
}
