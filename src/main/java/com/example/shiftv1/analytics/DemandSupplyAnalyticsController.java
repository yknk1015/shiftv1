package com.example.shiftv1.analytics;

import com.example.shiftv1.common.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

@RestController
@RequestMapping("/api/analytics")
public class DemandSupplyAnalyticsController {

    private final DemandSupplyAnalyticsService analyticsService;

    public DemandSupplyAnalyticsController(DemandSupplyAnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @GetMapping("/demand-supply")
    public ResponseEntity<ApiResponse<DemandSupplyAnalyticsService.DemandSupplySnapshot>> getDemandSupply(
            @RequestParam(name = "start", required = false) LocalDate start,
            @RequestParam(name = "end", required = false) LocalDate end,
            @RequestParam(name = "granularity", required = false, defaultValue = "60") Integer granularity,
            @RequestParam(name = "skillIds", required = false) String skillIdsCsv
    ) {
        try {
            Set<Long> filters = parseSkillIds(skillIdsCsv);
            int g = granularity == null ? 60 : granularity;
            var snapshot = analyticsService.summarize(start, end, g, filters);
            return ResponseEntity.ok(ApiResponse.success("需要/供給サマリーを取得しました", snapshot));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(ApiResponse.failure("需要/供給サマリーの取得に失敗しました"));
        }
    }

    private Set<Long> parseSkillIds(String csv) {
        if (csv == null || csv.isBlank()) {
            return Collections.emptySet();
        }
        Set<Long> ids = new HashSet<>();
        for (String tok : csv.split(",")) {
            if (tok == null || tok.isBlank()) {
                continue;
            }
            try {
                ids.add(Long.parseLong(tok.trim()));
            } catch (NumberFormatException ignore) {
            }
        }
        return ids;
    }
}
