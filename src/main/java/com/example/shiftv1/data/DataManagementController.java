package com.example.shiftv1.data;

import com.example.shiftv1.common.ApiResponse;
import com.example.shiftv1.employee.Employee;
import com.example.shiftv1.employee.EmployeeRepository;
import com.example.shiftv1.schedule.ShiftAssignment;
import com.example.shiftv1.schedule.ShiftAssignmentRepository;
import com.example.shiftv1.schedule.ScheduleCsvExporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriUtils;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/data")
public class DataManagementController {

    private static final Logger logger = LoggerFactory.getLogger(DataManagementController.class);
    
    private final EmployeeRepository employeeRepository;
    private final ShiftAssignmentRepository assignmentRepository;
    private final ScheduleCsvExporter scheduleCsvExporter;

    public DataManagementController(EmployeeRepository employeeRepository,
                                   ShiftAssignmentRepository assignmentRepository,
                                   ScheduleCsvExporter scheduleCsvExporter) {
        this.employeeRepository = employeeRepository;
        this.assignmentRepository = assignmentRepository;
        this.scheduleCsvExporter = scheduleCsvExporter;
    }

    @GetMapping("/export/employees/csv")
    public ResponseEntity<byte[]> exportEmployeesToCsv() {
        try {
            logger.info("従業員データのCSVエクスポートを開始します");

            List<Employee> employees = employeeRepository.findAll();
            
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (OutputStreamWriter osw = new OutputStreamWriter(baos, StandardCharsets.UTF_8);
                 PrintWriter writer = new PrintWriter(osw)) {
                writer.write('\uFEFF');
                writer.println("ID,名前,役職,作成日");

                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
                for (Employee employee : employees) {
                    writer.printf("%d,%s,%s,%s%n",
                            employee.getId(),
                            safeString(employee.getName()),
                            safeString(employee.getRole()),
                            employee.getCreatedAt() != null ? employee.getCreatedAt().format(formatter) : ""
                    );
                }
                writer.flush();
            }

            byte[] csvData = baos.toByteArray();
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType("text/csv; charset=UTF-8"));
            String filename = "employees.csv";
            String encoded = UriUtils.encode(filename, StandardCharsets.UTF_8);
            headers.add(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"" + filename + "\"; filename*=UTF-8''" + encoded);
            headers.setContentLength(csvData.length);

            logger.info("従業員データのCSVエクスポートが完了しました: {} 件", employees.size());
            return new ResponseEntity<>(csvData, headers, HttpStatus.OK);

        } catch (Exception e) {
            logger.error("従業員データのCSVエクスポートでエラーが発生しました", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/export/schedule/csv")
    public ResponseEntity<byte[]> exportScheduleToCsv(
            @RequestParam(name = "year", required = false) Integer year,
            @RequestParam(name = "month", required = false) Integer month) {
        try {
            logger.info("シフトデータのCSVエクスポートを開始します");

            LocalDate start, end;
            if (year != null && month != null) {
                start = LocalDate.of(year, month, 1);
                end = start.withDayOfMonth(start.lengthOfMonth());
            } else {
                // 現在の月のデータをエクスポート
                LocalDate now = LocalDate.now();
                start = now.withDayOfMonth(1);
                end = now.withDayOfMonth(now.lengthOfMonth());
            }

            List<ShiftAssignment> assignments = assignmentRepository.findByWorkDateBetween(start, end);
            
            YearMonth targetYm = YearMonth.of(start.getYear(), start.getMonthValue());
            ScheduleCsvExporter.CsvFile csvFile = scheduleCsvExporter.export(assignments, targetYm);

            logger.info("シフトデータのCSVエクスポートが完了しました: {} 件", assignments.size());
            return buildCsvResponse(csvFile);

        } catch (Exception e) {
            logger.error("シフトデータのCSVエクスポートでエラーが発生しました", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    private ResponseEntity<byte[]> buildCsvResponse(ScheduleCsvExporter.CsvFile csvFile) {
        String encoded = UriUtils.encode(csvFile.filename(), StandardCharsets.UTF_8);
        String disposition = "attachment; filename=\"" + csvFile.filename() + "\"; filename*=UTF-8''" + encoded;
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(csvFile.data());
    }

    private String safeString(String value) {
        return value == null ? "" : value;
    }

    @GetMapping("/backup/info")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getBackupInfo() {
        try {
            logger.info("バックアップ情報を取得します");

            long employeeCount = employeeRepository.count();
            long assignmentCount = assignmentRepository.count();
            
            // 最新のシフトデータの日付を取得
            LocalDate latestAssignment = assignmentRepository.findTopByOrderByWorkDateDesc()
                .map(ShiftAssignment::getWorkDate)
                .orElse(null);
            
            // 最古のシフトデータの日付を取得
            LocalDate oldestAssignment = assignmentRepository.findTopByOrderByWorkDateAsc()
                .map(ShiftAssignment::getWorkDate)
                .orElse(null);

            Map<String, Object> backupInfo = new HashMap<>();
            backupInfo.put("employeeCount", employeeCount);
            backupInfo.put("assignmentCount", assignmentCount);
            backupInfo.put("latestAssignmentDate", latestAssignment);
            backupInfo.put("oldestAssignmentDate", oldestAssignment);
            backupInfo.put("backupTimestamp", java.time.LocalDateTime.now());

            return ResponseEntity.ok(ApiResponse.success("バックアップ情報を取得しました", backupInfo));

        } catch (Exception e) {
            logger.error("バックアップ情報の取得でエラーが発生しました", e);
            return ResponseEntity.internalServerError().body(ApiResponse.failure("バックアップ情報の取得に失敗しました"));
        }
    }

    @PostMapping("/backup/export")
    public ResponseEntity<byte[]> createBackup() {
        try {
            logger.info("データベースバックアップを作成します");

            List<Employee> employees = employeeRepository.findAll();
            List<ShiftAssignment> assignments = assignmentRepository.findAll();
            
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            PrintWriter writer = new PrintWriter(baos);
            
            // バックアップヘッダー
            writer.println("# Shift Management System Backup");
            writer.printf("# Created: %s%n", java.time.LocalDateTime.now());
            writer.printf("# Employees: %d%n", employees.size());
            writer.printf("# Assignments: %d%n", assignments.size());
            writer.println();
            
            // 従業員データ
            writer.println("# EMPLOYEES");
            writer.println("ID,NAME,ROLE,CREATED_AT");
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            for (Employee employee : employees) {
                writer.printf("%d,%s,%s,%s%n",
                    employee.getId(),
                    employee.getName(),
                    employee.getRole(),
                    employee.getCreatedAt() != null ? employee.getCreatedAt().format(formatter) : ""
                );
            }
            writer.println();
            
            // シフトデータ
            writer.println("# SHIFT_ASSIGNMENTS");
            writer.println("ID,EMPLOYEE_ID,EMPLOYEE_NAME,WORK_DATE,SHIFT_NAME,START_TIME,END_TIME");
            DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
            for (ShiftAssignment assignment : assignments) {
                writer.printf("%d,%d,%s,%s,%s,%s,%s%n",
                    assignment.getId(),
                    assignment.getEmployee().getId(),
                    assignment.getEmployee().getName(),
                    assignment.getWorkDate().format(dateFormatter),
                    assignment.getShiftName(),
                    assignment.getStartTime() != null ? assignment.getStartTime().toString() : "",
                    assignment.getEndTime() != null ? assignment.getEndTime().toString() : ""
                );
            }
            
            writer.flush();
            byte[] backupData = baos.toByteArray();
            writer.close();

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            String filename = String.format("shift_backup_%s.txt", 
                java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")));
            headers.setContentDispositionFormData("attachment", filename);
            headers.setContentLength(backupData.length);

            logger.info("データベースバックアップが完了しました");
            return new ResponseEntity<>(backupData, headers, HttpStatus.OK);

        } catch (Exception e) {
            logger.error("データベースバックアップでエラーが発生しました", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/stats")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getDataStatistics() {
        try {
            logger.info("データ統計情報を取得します");

            long employeeCount = employeeRepository.count();
            long assignmentCount = assignmentRepository.count();
            
            // 月別のシフト数を計算
            Map<String, Long> monthlyStats = new HashMap<>();
            List<ShiftAssignment> allAssignments = assignmentRepository.findAll();
            
            for (ShiftAssignment assignment : allAssignments) {
                String monthKey = assignment.getWorkDate().format(DateTimeFormatter.ofPattern("yyyy-MM"));
                monthlyStats.put(monthKey, monthlyStats.getOrDefault(monthKey, 0L) + 1);
            }

            Map<String, Object> stats = new HashMap<>();
            stats.put("totalEmployees", employeeCount);
            stats.put("totalAssignments", assignmentCount);
            stats.put("monthlyStats", monthlyStats);
            stats.put("lastUpdated", java.time.LocalDateTime.now());

            return ResponseEntity.ok(ApiResponse.success("データ統計情報を取得しました", stats));

        } catch (Exception e) {
            logger.error("データ統計情報の取得でエラーが発生しました", e);
            return ResponseEntity.internalServerError().body(ApiResponse.failure("データ統計情報の取得に失敗しました"));
        }
    }
}






