    @Transactional
    public List<ShiftAssignment> generateForDateFromDemand(LocalDate date, boolean resetDay) {
        if (resetDay) {
            try { breakRepository.deleteByAssignment_WorkDateBetween(date, date); } catch (Exception ignored) {}
            assignmentRepository.deleteByWorkDate(date);
        }
        List<com.example.shiftv1.employee.Employee> employees = employeeRepository.findAll().stream()
                .sorted(Comparator.comparing(com.example.shiftv1.employee.Employee::getId))
                .toList();
        List<com.example.shiftv1.demand.DemandInterval> intervals = demandRepository.findEffectiveForDate(date, date.getDayOfWeek());
        if (intervals == null || intervals.isEmpty() || employees.isEmpty()) return List.of();
        intervals = intervals.stream()
                .sorted(Comparator
                        .comparing((com.example.shiftv1.demand.DemandInterval d) -> d.getSortOrder() == null ? 0 : d.getSortOrder())
                        .thenComparing(d -> d.getId() == null ? 0L : d.getId()))
                .toList();
        Map<Long, List<LocalTime[]>> empWindows = new HashMap<>();
        List<ShiftAssignment> created = new ArrayList<>();
        for (var di : intervals) {
            LocalTime s = di.getStartTime();
            LocalTime e = di.getEndTime();
            int seats = di.getRequiredSeats() == null ? 0 : di.getRequiredSeats();
            if (s == null || e == null || !s.isBefore(e) || seats <= 0) continue;
            Long skillId = (di.getSkill() == null ? null : di.getSkill().getId());
            String skillCode = (di.getSkill() == null ? null : di.getSkill().getCode());
            final String shiftName = (skillCode == null || skillCode.isBlank()) ? "Demand" : ("Demand-" + skillCode);
            for (int k = 0; k < seats; k++) {
                com.example.shiftv1.employee.Employee emp = pickEmployeeForWindow(employees, date, s, e, skillId, BlockType.GENERIC, empWindows);
                if (emp == null) break;
                empWindows.computeIfAbsent(emp.getId(), x -> new ArrayList<>()).add(new LocalTime[]{s, e});
                created.add(new ShiftAssignment(date, shiftName, s, e, emp));
            }
        }
        if (created.isEmpty()) return List.of();
        return assignmentRepository.saveAll(created);
    }

    @Async("scheduleExecutor")
    @Transactional
    public void generateMonthlyFromDemandAsync(int year, int month, int granularityMinutes, boolean resetMonth) {
        try {
            List<ShiftAssignment> res = generateMonthlyFromDemand(year, month, granularityMinutes, resetMonth);
            int cnt = (res == null ? 0 : res.size());
            logger.info("generateMonthlyFromDemandAsync finished: {}-{} -> {} assignments", year, month, cnt);
            if (cnt == 0) {
                try {
                    errorLogBuffer.addError("Demand generation produced 0 assignments",
                            new IllegalStateException("No assignments for %d-%02d".formatted(year, month)));
                } catch (Exception ignore) {}
            }
        } catch (Exception e) {
            logger.error("generateMonthlyFromDemandAsync error", e);
            try { errorLogBuffer.addError("generateMonthlyFromDemandAsync error", e); } catch (Exception ignore) {}
        }
    }

    @Transactional
    public void resetMonthlySchedule(int year, int month) {
        var ym = YearMonth.of(year, month);
        LocalDate s = ym.atDay(1);
        LocalDate e = ym.atEndOfMonth();
        try {
            breakRepository.deleteAllByAssignmentWorkDateBetween(s, e);
        } catch (Exception ex) {
            try { breakRepository.deleteByAssignment_WorkDateBetween(s, e); } catch (Exception ignored) {}
        }
        assignmentRepository.deleteByWorkDateBetween(s, e);
    }

    // report/diagnostics removed

    public Map<String, Object> addCoreTime(LocalDate day, Long skillId, String skillCode, java.time.LocalTime windowStart, java.time.LocalTime windowEnd, int seats) {
        Map<String, Object> result = new HashMap<>();
        int updated = 0;
        try {
            Long requiredSkillId = skillId;
            if (requiredSkillId == null && skillCode != null && !skillCode.isBlank()) {
                var s = skillRepository.findAll().stream().filter(x -> skillCode.equals(x.getCode())).findFirst().orElse(null);
                requiredSkillId = (s == null ? null : s.getId());
            }

            // Load current assignments of the day
            List<ShiftAssignment> dayAssignments = assignmentRepository.findByWorkDate(day);

            // Index employee -> assignments that day
            Map<com.example.shiftv1.employee.Employee, List<ShiftAssignment>> byEmp = new HashMap<>();
            for (var a : dayAssignments) {
                byEmp.computeIfAbsent(a.getEmployee(), k -> new ArrayList<>()).add(a);
            }

            // Try to extend existing employees' assignments to cover [windowStart, windowEnd]
            for (var entry : byEmp.entrySet()) {
                if (updated >= seats) break;
                var emp = entry.getKey();
                if (requiredSkillId != null) {
                    boolean hasSkill = false;
                    if (emp.getSkills() != null) {
                        for (var s : emp.getSkills()) { if (s != null && requiredSkillId.equals(s.getId())) { hasSkill = true; break; } }
                    }
                    if (!hasSkill) continue;
                }
                if (!Boolean.TRUE.equals(emp.getOvertimeAllowed())) continue;

                // Check day constraints
                var cons = constraintRepository.findByEmployeeAndDateAndActiveTrue(emp, day);
                boolean blocked = false;
                for (var c : cons) {
                    if (c.getType() == com.example.shiftv1.constraint.EmployeeConstraint.ConstraintType.UNAVAILABLE) { blocked = true; break; }
                    if (c.getType() == com.example.shiftv1.constraint.EmployeeConstraint.ConstraintType.LIMITED) {
                        var cs = c.getStartTime(); var ce = c.getEndTime();
                        if (cs != null && ce != null) {
                            if (windowStart.isBefore(cs) || windowEnd.isAfter(ce)) { blocked = true; break; }
                        }
                    }
                }
                if (blocked) continue;

                // Find the assignment that requires minimal extension to cover window
                ShiftAssignment best = null; long bestExtra = Long.MAX_VALUE;
                for (var a : entry.getValue()) {
                    LocalTime ns = a.getStartTime();
                    LocalTime ne = a.getEndTime();
                    // if already covers window, nothing to extend
                    if (!ns.isAfter(windowStart) && !ne.isBefore(windowEnd)) { best = a; bestExtra = 0; break; }
                    // If touches/overlaps window, compute needed extra minutes
                    if (ne.isBefore(windowStart) || ns.isAfter(windowEnd)) continue; // far apart
                    long extra = 0;
                    if (ns.isAfter(windowStart)) extra += java.time.Duration.between(windowStart, ns).toMinutes();
                    if (ne.isBefore(windowEnd)) extra += java.time.Duration.between(ne, windowEnd).toMinutes();
                    if (extra < bestExtra) { best = a; bestExtra = extra; }
                }
                if (best == null) continue;

                int dailyMax = emp.getOvertimeDailyMaxHours() == null ? 0 : emp.getOvertimeDailyMaxHours();
                long maxExtraMin = dailyMax * 60L;
                if (bestExtra <= maxExtraMin) {
                    // extend best to cover requested window
                    if (best.getStartTime().isAfter(windowStart)) best.setStartTime(windowStart);
                    if (best.getEndTime().isBefore(windowEnd)) best.setEndTime(windowEnd);
                    assignmentRepository.save(best);
                    updated++;
                }
            }

            result.put("updated", updated);
            result.put("reason", updated >= seats ? "ok" : "insufficient-candidates");
        } catch (Exception ex) {
            result.put("updated", updated);
            result.put("reason", "error: " + ex.getMessage());
        }
        return result;
    }

    // report/diagnostics removed
    
    // ---- Private helpers ----
    private List<com.example.shiftv1.config.ShiftConfig> resolveConfigsForDay(
            List<com.example.shiftv1.config.ShiftConfig> active,
            LocalDate day,
            boolean isHoliday) {
        if (active == null || active.isEmpty()) return List.of();
        if (isHoliday) {
            List<com.example.shiftv1.config.ShiftConfig> holiday = active.stream()
                    .filter(c -> Boolean.TRUE.equals(c.getHoliday()))
                    .sorted(Comparator.comparing(com.example.shiftv1.config.ShiftConfig::getStartTime))
                    .toList();
            return holiday;
        }
        DayOfWeek dow = day.getDayOfWeek();
        // Prefer explicit set of days
        List<com.example.shiftv1.config.ShiftConfig> setDays = active.stream()
                .filter(c -> c.getDays() != null && !c.getDays().isEmpty() && c.getDays().contains(dow))
                .sorted(Comparator.comparing(com.example.shiftv1.config.ShiftConfig::getStartTime))
                .toList();
        if (!setDays.isEmpty()) return setDays;

        // Fallback to single dayOfWeek field
        List<com.example.shiftv1.config.ShiftConfig> singleDow = active.stream()
                .filter(c -> c.getDayOfWeek() != null && c.getDayOfWeek() == dow)
                .sorted(Comparator.comparing(com.example.shiftv1.config.ShiftConfig::getStartTime))
                .toList();
        if (!singleDow.isEmpty()) return singleDow;

        // Otherwise, use generic non-holiday configs without specific days
        return active.stream()
                .filter(c -> !Boolean.TRUE.equals(c.getHoliday()))
                .filter(c -> c.getDayOfWeek() == null)
                .filter(c -> c.getDays() == null || c.getDays().isEmpty())
                .sorted(Comparator.comparing(com.example.shiftv1.config.ShiftConfig::getStartTime))
                .toList();
    }

    private com.example.shiftv1.employee.Employee pickEmployeeForShift(
            List<com.example.shiftv1.employee.Employee> employees,
            LocalDate day,
            LocalTime start,
            LocalTime end,
            Set<Long> used) {
        for (var e : employees) {
            Long id = e.getId();
            if (id == null || used.contains(id)) continue;
            // Check constraints for this date
            List<com.example.shiftv1.constraint.EmployeeConstraint> cons =
                    constraintRepository.findByEmployeeAndDateAndActiveTrue(e, day);
            boolean blocked = false;
            for (var c : cons) {
                if (c.getType() == com.example.shiftv1.constraint.EmployeeConstraint.ConstraintType.UNAVAILABLE) {
                    blocked = true; break;
                }
                if (c.getType() == com.example.shiftv1.constraint.EmployeeConstraint.ConstraintType.LIMITED) {
                    LocalTime cs = c.getStartTime();
                    LocalTime ce = c.getEndTime();
                    if (cs != null && ce != null) {
                        // must fit within the limited window
                        if (start.isBefore(cs) || end.isAfter(ce)) { blocked = true; break; }
                    }
                }
            }
            if (blocked) continue;
            return e;
        }
        return null;
    }

    private enum BlockType { FULL, MORNING, AFTERNOON, GENERIC }

    private String buildDemandShiftName(Long skillId, LocalTime start, LocalTime end) {
        String label = "Demand";
        if (skillId != null) {
            try {
                var sk = skillRepository.findById(skillId).orElse(null);
                if (sk != null && sk.getCode() != null && !sk.getCode().isBlank()) {
                    label = "Demand-" + sk.getCode();
                }
            } catch (Exception ignored) {}
        }
        String s = start == null ? "" : start.toString();
        String e = end == null ? "" : end.toString();
        if (s.length() >= 5) s = s.substring(0,5);
        if (e.length() >= 5) e = e.substring(0,5);
        return String.format("%s %s-%s", label, s, e).trim();
    }

    private com.example.shiftv1.employee.Employee pickEmployeeForWindow(
            List<com.example.shiftv1.employee.Employee> employees,
            LocalDate day,
            LocalTime start,
            LocalTime end,
            Long requiredSkillId,
            BlockType type,
            Map<Long, List<LocalTime[]>> empWindows) {
        for (var e : employees) {
            Long id = e.getId();
            if (id == null) continue;
            if (requiredSkillId != null) {
                boolean has = false;
                if (e.getSkills() != null) {
                    for (var s : e.getSkills()) {
                        if (s != null && requiredSkillId.equals(s.getId())) { has = true; break; }
                    }
                }
                if (!has) continue;
            }
            // Eligibility by block type (null treated as true for backward compatibility)
            boolean okFull = e.getEligibleFull() == null ? true : Boolean.TRUE.equals(e.getEligibleFull());
            boolean okMorning = e.getEligibleShortMorning() == null ? true : Boolean.TRUE.equals(e.getEligibleShortMorning());
            boolean okAfternoon = e.getEligibleShortAfternoon() == null ? true : Boolean.TRUE.equals(e.getEligibleShortAfternoon());
            if (type == BlockType.FULL && !okFull) continue;
            if (type == BlockType.MORNING && !okMorning) continue;
            if (type == BlockType.AFTERNOON && !okAfternoon) continue;
            List<com.example.shiftv1.constraint.EmployeeConstraint> cons =
                    constraintRepository.findByEmployeeAndDateAndActiveTrue(e, day);
            boolean blocked = false;
            for (var c : cons) {
                if (c.getType() == com.example.shiftv1.constraint.EmployeeConstraint.ConstraintType.UNAVAILABLE) {
                    blocked = true; break;
                }
                if (c.getType() == com.example.shiftv1.constraint.EmployeeConstraint.ConstraintType.LIMITED) {
                    LocalTime cs = c.getStartTime();
                    LocalTime ce = c.getEndTime();
                    if (cs != null && ce != null) {
                        if (start.isBefore(cs) || end.isAfter(ce)) { blocked = true; break; }
                    }
                }
            }
            if (blocked) continue;
            List<LocalTime[]> windows = empWindows.get(id);
            if (windows != null) {
                boolean overlap = false;
                for (LocalTime[] w : windows) {
                    LocalTime s2 = w[0], e2 = w[1];
                    if (start.isBefore(e2) && end.isAfter(s2)) { overlap = true; break; }
                }
                if (overlap) continue;
            }
            return e;
        }
        return null;
    }
}
