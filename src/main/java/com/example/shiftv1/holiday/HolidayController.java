package com.example.shiftv1.holiday;

import com.example.shiftv1.common.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/holidays")
public class HolidayController {
    private final HolidayRepository holidayRepository;

    public HolidayController(HolidayRepository holidayRepository) {
        this.holidayRepository = holidayRepository;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<HolidayDto>>> list() {
        List<HolidayDto> holidays = holidayRepository.findAll().stream()
                .sorted((a, b) -> a.getDate().compareTo(b.getDate()))
                .map(HolidayDto::from)
                .toList();
        return ResponseEntity.ok(ApiResponse.success("祝日一覧を取得しました", holidays));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<HolidayDto>> create(@RequestBody HolidayRequest request) {
        String validationError = request.validationError();
        if (validationError != null) {
            return ResponseEntity.badRequest().body(ApiResponse.failure(validationError));
        }
        LocalDate date = request.date();
        Holiday holiday = holidayRepository.findByDate(date)
                .map(existing -> {
                    existing.setName(request.name());
                    return existing;
                })
                .orElseGet(() -> new Holiday(date, request.name()));
        Holiday saved = holidayRepository.save(holiday);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("祝日を登録しました", HolidayDto.from(saved)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<HolidayDto>> update(@PathVariable Long id,
                                                          @RequestBody HolidayRequest request) {
        String validationError = request.validationError();
        if (validationError != null) {
            return ResponseEntity.badRequest().body(ApiResponse.failure(validationError));
        }
        Holiday holiday = holidayRepository.findById(id)
                .orElse(null);
        if (holiday == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.failure("祝日が見つかりません (ID=" + id + ")"));
        }
        if (!holiday.getDate().equals(request.date())) {
            if (holidayRepository.existsByDate(request.date())) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(ApiResponse.failure("同じ日付の祝日が既に登録されています"));
            }
            holiday.setDate(request.date());
        }
        holiday.setName(request.name());
        Holiday saved = holidayRepository.save(holiday);
        return ResponseEntity.ok(ApiResponse.success("祝日を更新しました", HolidayDto.from(saved)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        if (!holidayRepository.existsById(id)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.failure("祝日が見つかりません (ID=" + id + ")"));
        }
        holidayRepository.deleteById(id);
        return ResponseEntity.ok(ApiResponse.success("祝日を削除しました", null));
    }

    public record HolidayRequest(LocalDate date, String name) {
        String validationError() {
            if (date == null) {
                return "日付は必須です";
            }
            return null;
        }
    }

    public record HolidayDto(Long id, LocalDate date, String name) {
        static HolidayDto from(Holiday holiday) {
            return new HolidayDto(holiday.getId(), holiday.getDate(), holiday.getName());
        }
    }
}
