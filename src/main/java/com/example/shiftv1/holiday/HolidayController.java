package com.example.shiftv1.holiday;

import com.example.shiftv1.common.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/config/holiday")
public class HolidayController {
    private final HolidayRepository holidayRepository;

    public HolidayController(HolidayRepository holidayRepository) {
        this.holidayRepository = holidayRepository;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<LocalDate>>> list() {
        List<LocalDate> dates = holidayRepository.findAll().stream().map(Holiday::getDate).sorted().toList();
        return ResponseEntity.ok(ApiResponse.success("祝日一覧を取得しました", dates));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> add(@RequestParam("date") String dateStr) {
        LocalDate date = LocalDate.parse(dateStr);
        if (!holidayRepository.existsByDate(date)) {
            holidayRepository.save(new Holiday(date));
        }
        return ResponseEntity.ok(ApiResponse.success("祝日を登録しました", Map.of("date", date)));
    }

    @DeleteMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> remove(@RequestParam("date") String dateStr) {
        LocalDate date = LocalDate.parse(dateStr);
        holidayRepository.deleteByDate(date);
        return ResponseEntity.ok(ApiResponse.success("祝日を削除しました", Map.of("date", date)));
    }
}

