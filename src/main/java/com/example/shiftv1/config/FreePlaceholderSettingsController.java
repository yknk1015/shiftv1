package com.example.shiftv1.config;

import com.example.shiftv1.common.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/config/free-placeholders")
public class FreePlaceholderSettingsController {

    private final FreePlaceholderSettings settings;

    public FreePlaceholderSettingsController(FreePlaceholderSettings settings) {
        this.settings = settings;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> get() {
        Map<String, Object> data = new HashMap<>();
        data.put("onlyWeekdays", settings.isOnlyWeekdays());
        data.put("skipHolidays", settings.isSkipHolidays());
        return ResponseEntity.ok(ApiResponse.success("FREEプレースホルダ設定", data));
    }

    public static class UpdateRequest {
        public Boolean onlyWeekdays;
        public Boolean skipHolidays;
    }

    @PutMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> update(@RequestBody UpdateRequest req) {
        if (req.onlyWeekdays != null) settings.setOnlyWeekdays(req.onlyWeekdays);
        if (req.skipHolidays != null) settings.setSkipHolidays(req.skipHolidays);
        Map<String, Object> data = new HashMap<>();
        data.put("onlyWeekdays", settings.isOnlyWeekdays());
        data.put("skipHolidays", settings.isSkipHolidays());
        return ResponseEntity.ok(ApiResponse.success("FREEプレースホルダ設定を更新しました", data));
    }
}

