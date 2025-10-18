package com.example.shiftv1.breaks;

import com.example.shiftv1.common.ApiResponse;
import com.example.shiftv1.schedule.ShiftAssignment;
import com.example.shiftv1.schedule.ShiftAssignmentRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/breaks")
public class BreakController {

    private final BreakPeriodRepository breakRepository;
    private final ShiftAssignmentRepository assignmentRepository;

    public BreakController(BreakPeriodRepository breakRepository, ShiftAssignmentRepository assignmentRepository) {
        this.breakRepository = breakRepository;
        this.assignmentRepository = assignmentRepository;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<BreakPeriod>>> list(@RequestParam("date") LocalDate date) {
        return ResponseEntity.ok(ApiResponse.success("休憩一覧を取得しました", breakRepository.findByWorkDate(date)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<BreakPeriod>> create(@Valid @RequestBody BreakRequest req) {
        Optional<ShiftAssignment> oa = assignmentRepository.findById(req.assignmentId());
        if (oa.isEmpty()) return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.failure("シフトが見つかりません"));
        BreakPeriod p = new BreakPeriod(oa.get(), req.type(), req.startTime(), req.endTime());
        BreakPeriod saved = breakRepository.save(p);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("休憩を登録しました", saved));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Map<String,Object>>> delete(@PathVariable Long id) {
        if (!breakRepository.existsById(id)) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.failure("休憩が見つかりません"));
        breakRepository.deleteById(id);
        return ResponseEntity.ok(ApiResponse.success("休憩を削除しました", Map.of()));
    }

    public record BreakRequest(Long assignmentId, BreakPeriod.BreakType type, java.time.LocalTime startTime, java.time.LocalTime endTime) {}
}

