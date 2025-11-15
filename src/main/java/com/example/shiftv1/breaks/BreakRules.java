package com.example.shiftv1.breaks;

import java.time.LocalTime;
import java.time.temporal.ChronoUnit;

/**
 * Centralized break-time heuristics so UI/API/generators can stay consistent.
 */
public final class BreakRules {

    private static final int SIX_HOURS = 6 * 60;
    private static final int EIGHT_HOURS = 8 * 60;

    private BreakRules() {
    }

    /**
     * Returns the recommended break duration for the given shift window.
     */
    public static int recommendMinutes(LocalTime start, LocalTime end) {
        long duration = durationMinutes(start, end);
        if (duration <= 0) {
            return 0;
        }
        if (duration >= EIGHT_HOURS) {
            return 60;
        }
        if (duration >= SIX_HOURS) {
            return 45;
        }
        return 0;
    }

    /**
     * Normalizes a requested break duration so that it fits inside the shift window.
     * When the requested value is null, the statutory recommendation is used.
     */
    public static int normalizeMinutes(Integer requested, LocalTime start, LocalTime end) {
        long duration = durationMinutes(start, end);
        if (duration <= 0) {
            return 0;
        }
        int candidate = requested == null ? recommendMinutes(start, end) : Math.max(0, requested);
        if (candidate <= 0) {
            return 0;
        }
        long maxAllowed = Math.max(1, duration - 1); // keep at least 1 minute of work time
        if (candidate > maxAllowed) {
            candidate = (int) maxAllowed;
        }
        return Math.max(0, candidate);
    }

    /**
     * Plans a single break window for the given shift and order index.
     *
     * @param orderIndex 0-based index of an employee within the same shift window. Used to stagger start times.
     */
    public static BreakWindow planWindow(LocalTime shiftStart, LocalTime shiftEnd, int breakMinutes, int orderIndex) {
        if (shiftStart == null || shiftEnd == null || breakMinutes <= 0) {
            return null;
        }
        long duration = durationMinutes(shiftStart, shiftEnd);
        if (duration <= breakMinutes) {
            return null;
        }
        long slack = duration - breakMinutes;
        long offsetMinutes = preferredOffsetMinutes(orderIndex, slack);
        LocalTime breakStart = shiftStart.plusMinutes(offsetMinutes);
        LocalTime breakEnd = breakStart.plusMinutes(breakMinutes);
        if (breakEnd.isAfter(shiftEnd)) {
            breakEnd = shiftEnd;
            breakStart = breakEnd.minusMinutes(breakMinutes);
        }
        if (breakStart.isBefore(shiftStart)) {
            breakStart = shiftStart;
            breakEnd = breakStart.plusMinutes(breakMinutes);
        }
        return new BreakWindow(breakStart, breakEnd);
    }

    private static long durationMinutes(LocalTime start, LocalTime end) {
        if (start == null || end == null) {
            return 0;
        }
        return ChronoUnit.MINUTES.between(start, end);
    }

    private static long preferredOffsetMinutes(int orderIndex, long slack) {
        if (slack <= 0) {
            return 0;
        }
        int idx = Math.max(0, orderIndex);
        boolean oddPosition = (idx % 2 == 0); // 0-based: 0,2,... => 奇数番目
        long preferred = oddPosition ? 4 * 60L : 3 * 60L;
        if (preferred <= slack) {
            return preferred;
        }
        return Math.max(0, slack / 2); // fallback:中央寄せ
    }

    public record BreakWindow(LocalTime start, LocalTime end) {
    }
}
