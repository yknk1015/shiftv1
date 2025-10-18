package com.example.shiftv1.leave;

import com.example.shiftv1.common.ApiResponse;
import com.example.shiftv1.constraint.EmployeeConstraint;
import com.example.shiftv1.constraint.EmployeeConstraintRepository;
import com.example.shiftv1.employee.Employee;
import com.example.shiftv1.employee.EmployeeRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/leave")
public class LeaveController {

    private final LeaveBalanceRepository balanceRepository;
    private final LeaveRequestRepository requestRepository;
    private final EmployeeRepository employeeRepository;
    private final EmployeeConstraintRepository constraintRepository;

    public LeaveController(LeaveBalanceRepository balanceRepository,
                           LeaveRequestRepository requestRepository,
                           EmployeeRepository employeeRepository,
                           EmployeeConstraintRepository constraintRepository) {
        this.balanceRepository = balanceRepository;
        this.requestRepository = requestRepository;
        this.employeeRepository = employeeRepository;
        this.constraintRepository = constraintRepository;
    }

    @GetMapping("/balance/{employeeId}")
    public ResponseEntity<ApiResponse<Map<String,Object>>> getBalance(@PathVariable Long employeeId) {
        Optional<Employee> emp = employeeRepository.findById(employeeId);
        if (emp.isEmpty()) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.failure("従業員が見つかりません"));
        LeaveBalance bal = balanceRepository.findTopByEmployeeOrderByIdDesc(emp.get()).orElseGet(() -> new LeaveBalance(emp.get(), 0, 0, null, null));
        return ResponseEntity.ok(ApiResponse.success("残数を取得しました", Map.of(
                "annualGranted", bal.getAnnualGranted(),
                "carriedOver", bal.getCarriedOver(),
                "used", bal.getUsed(),
                "remaining", bal.remaining()
        )));
    }

    @PostMapping("/balance/grant")
    public ResponseEntity<ApiResponse<LeaveBalance>> grant(@Valid @RequestBody GrantRequest req) {
        Optional<Employee> emp = employeeRepository.findById(req.employeeId());
        if (emp.isEmpty()) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.failure("従業員が見つかりません"));
        LeaveBalance bal = balanceRepository.findTopByEmployeeOrderByIdDesc(emp.get()).orElse(new LeaveBalance(emp.get(), 0, 0, null, null));
        bal.setAnnualGranted((bal.getAnnualGranted() == null ? 0 : bal.getAnnualGranted()) + req.days());
        LeaveBalance saved = balanceRepository.save(bal);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("有給を付与しました", saved));
    }

    @PostMapping("/requests")
    public ResponseEntity<ApiResponse<LeaveRequest>> request(@Valid @RequestBody LeaveRequestBody body) {
        Optional<Employee> emp = employeeRepository.findById(body.employeeId());
        if (emp.isEmpty()) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.failure("従業員が見つかりません"));
        Employee e = emp.get();
        LeaveBalance bal = balanceRepository.findTopByEmployeeOrderByIdDesc(e).orElse(new LeaveBalance(e, 0, 0, null, null));
        if (bal.remaining() <= 0) {
            return ResponseEntity.badRequest().body(ApiResponse.failure("有給残数が不足しています"));
        }
        LeaveRequest req = new LeaveRequest(e, body.date());
        req.setStatus(LeaveRequest.Status.APPROVED);
        LeaveRequest saved = requestRepository.save(req);
        // 承認済みはVACATION制約を発行
        EmployeeConstraint c = new EmployeeConstraint(e, body.date(), EmployeeConstraint.ConstraintType.VACATION, "PTO");
        constraintRepository.save(c);
        // 残数を消化
        bal.setUsed((bal.getUsed() == null ? 0 : bal.getUsed()) + 1);
        balanceRepository.save(bal);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("有給を登録しました", saved));
    }

    public record GrantRequest(Long employeeId, Integer days) {}
    public record LeaveRequestBody(Long employeeId, LocalDate date) {}
}

