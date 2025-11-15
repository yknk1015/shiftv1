package com.example.shiftv1.schedule;

import com.example.shiftv1.employee.Employee;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.StringJoiner;
import java.util.stream.Collectors;

@Component
public class ScheduleCsvExporter {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy/MM/dd");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
    private static final Locale LOCALE_JP = Locale.JAPANESE;
    private static final String[] HEADERS = {
            "日付", "曜日", "従業員ID", "従業員名", "シフト名",
            "開始", "終了", "稼働時間(分)", "区分", "FREE枠", "休日フラグ", "休暇フラグ", "スキル"
    };

    public CsvFile export(List<ShiftAssignment> assignments, YearMonth target) {
        StringBuilder builder = new StringBuilder();
        builder.append('\uFEFF');
        builder.append(String.join(",", HEADERS)).append('\n');

        assignments.stream()
                .sorted(Comparator
                        .comparing(ShiftAssignment::getWorkDate)
                        .thenComparing(sa -> safeTime(sa.getStartTime()))
                        .thenComparing(sa -> employeeName(sa.getEmployee())))
                .forEach(sa -> appendRow(builder, sa));

        byte[] data = builder.toString().getBytes(StandardCharsets.UTF_8);
        String filename = String.format("schedule-%d-%02d.csv", target.getYear(), target.getMonthValue());
        return new CsvFile(filename, data);
    }

    private void appendRow(StringBuilder builder, ShiftAssignment assignment) {
        boolean placeholderSlot = isPlaceholderSlot(assignment);
        String[] times = resolveTimesForExport(assignment, placeholderSlot);
        long durationMinutes = placeholderSlot ? 0 : calculateDurationMinutes(assignment);

        StringJoiner joiner = new StringJoiner(",");
        joiner.add(escapeCsv(DATE_FORMAT.format(assignment.getWorkDate())));
        joiner.add(escapeCsv(formatDayOfWeek(assignment.getWorkDate())));
        joiner.add(escapeCsv(safeId(assignment.getEmployee())));
        joiner.add(escapeCsv(employeeName(assignment.getEmployee())));
        joiner.add(escapeCsv(defaultString(assignment.getShiftName())));
        joiner.add(escapeCsv(times[0]));
        joiner.add(escapeCsv(times[1]));
        joiner.add(escapeCsv(Long.toString(durationMinutes)));
        joiner.add(escapeCsv(classify(assignment)));
        joiner.add(escapeCsv(formatFlag(assignment.getIsFree())));
        joiner.add(escapeCsv(formatFlag(assignment.getIsOff())));
        joiner.add(escapeCsv(formatFlag(assignment.getIsLeave())));
        joiner.add(escapeCsv(formatSkills(assignment.getEmployee())));

        builder.append(joiner).append('\n');
    }

    private String[] resolveTimesForExport(ShiftAssignment assignment, boolean placeholderSlot) {
        if (placeholderSlot) {
            return new String[] {"00:00", "00:00"};
        }
        return new String[] {
                formatTime(assignment.getStartTime()),
                formatTime(assignment.getEndTime())
        };
    }

    private boolean isPlaceholderSlot(ShiftAssignment assignment) {
        return Boolean.TRUE.equals(assignment.getIsFree()) || Boolean.TRUE.equals(assignment.getIsOff());
    }

    private long calculateDurationMinutes(ShiftAssignment assignment) {
        LocalTime start = assignment.getStartTime();
        LocalTime end = assignment.getEndTime();
        if (start == null || end == null) {
            return 0;
        }
        return Math.max(ChronoUnit.MINUTES.between(start, end), 0);
    }

    private String formatDayOfWeek(LocalDate date) {
        return date.getDayOfWeek().getDisplayName(TextStyle.SHORT, LOCALE_JP);
    }

    private String formatTime(LocalTime time) {
        return time == null ? "" : TIME_FORMAT.format(time);
    }

    private String classify(ShiftAssignment assignment) {
        if (Boolean.TRUE.equals(assignment.getIsLeave())) {
            return "休暇";
        }
        if (Boolean.TRUE.equals(assignment.getIsOff())) {
            return "休日";
        }
        if (Boolean.TRUE.equals(assignment.getIsFree())) {
            return "FREE";
        }
        return "通常";
    }

    private String formatFlag(Boolean value) {
        return Boolean.TRUE.equals(value) ? "○" : "";
    }

    private String formatSkills(Employee employee) {
        if (employee == null || employee.getSkills() == null) {
            return "";
        }
        Set<com.example.shiftv1.skill.Skill> skills = employee.getSkills();
        if (skills.isEmpty()) {
            return "";
        }
        return skills.stream()
                .map(com.example.shiftv1.skill.Skill::getName)
                .sorted()
                .collect(Collectors.joining(" / "));
    }

    private String employeeName(Employee employee) {
        return employee == null ? "" : defaultString(employee.getName());
    }

    private String safeId(Employee employee) {
        if (employee == null || employee.getId() == null) {
            return "";
        }
        return employee.getId().toString();
    }

    private LocalTime safeTime(LocalTime time) {
        return time == null ? LocalTime.MIDNIGHT : time;
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private String escapeCsv(String value) {
        String target = value == null ? "" : value;
        if (target.contains(",") || target.contains("\"") || target.contains("\n")) {
            return "\"" + target.replace("\"", "\"\"") + "\"";
        }
        return target;
    }

    public record CsvFile(String filename, byte[] data) { }
}
