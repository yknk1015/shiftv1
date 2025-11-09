package com.example.shiftv1.schedule;

import com.example.shiftv1.common.ApiResponse;
import com.example.shiftv1.employee.Employee;
import com.example.shiftv1.employee.EmployeeRepository;
import com.example.shiftv1.skill.Skill;
import com.example.shiftv1.skill.SkillRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/schedule/reservations")
public class ShiftReservationController {

    private final ShiftReservationRepository reservationRepository;
    private final EmployeeRepository employeeRepository;
    private final SkillRepository skillRepository;

    public ShiftReservationController(ShiftReservationRepository reservationRepository,
                                      EmployeeRepository employeeRepository,
                                      SkillRepository skillRepository) {
        this.reservationRepository = reservationRepository;
        this.employeeRepository = employeeRepository;
        this.skillRepository = skillRepository;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<ShiftReservationDto>>> list(
            @RequestParam("start") LocalDate start,
            @RequestParam("end") LocalDate end) {
        List<ShiftReservationDto> data = reservationRepository
                .findByWorkDateBetween(start, end)
                .stream()
                .map(ShiftReservationDto::from)
                .toList();
        return ResponseEntity.ok(ApiResponse.success("莠育ｴ・ｸ隕ｧ繧貞叙蠕励＠縺ｾ縺励◆", data));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ShiftReservationDto>> create(@Valid @RequestBody ShiftReservationRequest request) {
        Optional<Employee> empOpt = employeeRepository.findById(request.employeeId());
        if (empOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.failure("蠕捺･ｭ蜩｡縺瑚ｦ九▽縺九ｊ縺ｾ縺帙ｓ"));
        }
        Skill skill = null;
        if (request.skillId() != null) {
            skill = skillRepository.findById(request.skillId()).orElse(null);
        }
        ShiftReservation reservation = new ShiftReservation(
                empOpt.get(),
                skill,
                request.workDate(),
                request.startTime(),
                request.endTime(),
                request.label()
        );
        reservation.setNote(request.note());
        ShiftReservation saved = reservationRepository.save(reservation);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("莠育ｴ・ｒ逋ｻ骭ｲ縺励∪縺励◆", ShiftReservationDto.from(saved)));
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<ApiResponse<ShiftReservationDto>> updateStatus(@PathVariable Long id,
                                                                         @RequestBody StatusUpdateRequest body) {
        Optional<ShiftReservation> reservationOpt = reservationRepository.findById(id);
        if (reservationOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.failure("莠育ｴ・′隕九▽縺九ｊ縺ｾ縺帙ｓ"));
        }
        ShiftReservation reservation = reservationOpt.get();
        if (body.status() != null) {
            reservation.setStatus(body.status());
        }
        ShiftReservation saved = reservationRepository.save(reservation);
        return ResponseEntity.ok(ApiResponse.success("莠育ｴ・せ繝・・繧ｿ繧ｹ繧呈峩譁ｰ縺励∪縺励◆", ShiftReservationDto.from(saved)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        if (!reservationRepository.existsById(id)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.failure("莠育ｴ・′隕九▽縺九ｊ縺ｾ縺帙ｓ"));
        }
        reservationRepository.deleteById(id);
        return ResponseEntity.ok(ApiResponse.success("莠育ｴ・ｒ蜑企勁縺励∪縺励◆", null));
    }

    @DeleteMapping("/bulk")
    public ResponseEntity<ApiResponse<Map<String, Object>>> deleteRange(@RequestParam("start") LocalDate start,
                                                                        @RequestParam("end") LocalDate end) {
        if (end.isBefore(start)) {
            return ResponseEntity.badRequest().body(ApiResponse.failure("髢句ｧ区律縺ｯ邨ゆｺ・律莉･蜑阪↓縺励※縺上□縺輔＞"));
        }
        List<ShiftReservation> targets = reservationRepository.findByWorkDateBetween(start, end);
        int count = targets.size();
        if (count > 0) {
            reservationRepository.deleteAll(targets);
        }
        Map<String, Object> meta = Map.of(
                "start", start.toString(),
                "end", end.toString(),
                "deleted", count
        );
        return ResponseEntity.ok(ApiResponse.success("謖・ｮ壽悄髢薙・莠育ｴ・ｒ蜑企勁縺励∪縺励◆", meta));
    }

    public record StatusUpdateRequest(ShiftReservation.Status status) {}
}
