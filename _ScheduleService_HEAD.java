
    // --- Core-time boost: extend tail during a specific window for a skill ---
    @org.springframework.transaction.annotation.Transactional
    public java.util.Map<String,Object> addCoreTime(java.time.LocalDate day,
                                                    java.lang.Long skillId,
                                                    java.lang.String skillCode,
                                                    java.time.LocalTime windowStart,
                                                    java.time.LocalTime windowEnd,
                                                    int seats) {
        java.util.Map<String,Object> resp = new java.util.HashMap<>();
        com.example.shiftv1.skill.Skill skill = null;
        try {
            if (skillId != null) {
                skill = skillRepository.findById(skillId).orElse(null);
            } else if (skillCode != null && !skillCode.isBlank()) {
                skill = skillRepository.findByCode(skillCode).orElse(null);
            }
        } catch (Exception ignored) {}
        if (skill == null || windowStart == null || windowEnd == null || !windowStart.isBefore(windowEnd) || seats <= 0) {
            resp.put("updated", 0);
            resp.put("reason", "invalid-args");
            return resp;
        }
        int required = requiredSeatsForWindow(day, skill, windowStart, windowEnd);
        int current = currentCoverageForWindow(day, skill, windowStart, windowEnd);
        int canAdd = Math.max(0, required - current);
        if (canAdd <= 0) {
            resp.put("updated", 0);
            resp.put("reason", "no-remaining-demand");
            resp.put("required", required);
            resp.put("current", current);
            return resp;
        }
        int target = Math.min(canAdd, seats);

        java.util.List<ShiftAssignment> dayAssignments = assignmentRepository.findByWorkDate(day);
        java.util.List<ShiftAssignment> candidates = new java.util.ArrayList<>();
        for (ShiftAssignment sa : dayAssignments) {
            if (!sa.getEndTime().equals(windowStart)) continue; // tail extension only
            com.example.shiftv1.employee.Employee emp = sa.getEmployee();
            if (emp == null) continue;
            if (!employeeHasSkill(emp, skill)) continue;
            if (!isEligibleForBlock(emp, windowStart, windowEnd)) continue;
            if (!isAvailableForWindow(emp.getId(), day, sa.getStartTime(), windowEnd)) continue;
            if (!withinDailyWithOvertime(emp.getId(), day, sa.getStartTime(), windowEnd)) continue;
            candidates.add(sa);
        }
        java.util.Collections.shuffle(candidates);
        java.util.List<ShiftAssignment> toUpdate = new java.util.ArrayList<>();
        for (ShiftAssignment sa : candidates) {
            if (toUpdate.size() >= target) break;
            sa.setEndTime(windowEnd);
            toUpdate.add(sa);
        }
        assignmentRepository.saveAll(toUpdate);
        java.util.List<java.lang.Long> ids = toUpdate.stream().map(ShiftAssignment::getId).toList();
        resp.put("updated", toUpdate.size());
        resp.put("ids", ids);
        resp.put("required", required);
        resp.put("currentBefore", current);
        resp.put("target", target);
        return resp;
    }

    private boolean employeeHasSkill(com.example.shiftv1.employee.Employee e, com.example.shiftv1.skill.Skill skill) {
        if (e == null || e.getSkills() == null || skill == null || skill.getId() == null) return false;
        for (com.example.shiftv1.skill.Skill s : e.getSkills()) {
            if (s != null && skill.getId().equals(s.getId())) return true;
        }
        return false;
    }

    private int requiredSeatsForWindow(java.time.LocalDate day, com.example.shiftv1.skill.Skill skill,
                                       java.time.LocalTime s, java.time.LocalTime e) {
        try {
            java.util.List<com.example.shiftv1.demand.DemandInterval> intervals = demandRepository.findEffectiveForDate(day, day.getDayOfWeek());
            if (intervals == null || intervals.isEmpty() || skill == null || skill.getId() == null) return 0;
            java.util.List<com.example.shiftv1.demand.DemandInterval> list = intervals.stream()
                    .filter(di -> di.getSkill() != null && skill.getId().equals(di.getSkill().getId()))
                    .filter(di -> di.getEndTime().isAfter(s) && di.getStartTime().isBefore(e))
                    .toList();
            if (list.isEmpty()) return 0;
            boolean hasDate = list.stream().anyMatch(di -> di.getDate() != null);
            java.util.stream.Stream<com.example.shiftv1.demand.DemandInterval> stream = hasDate
                    ? list.stream().filter(di -> di.getDate() != null)
                    : list.stream();
            return stream.mapToInt(com.example.shiftv1.demand.DemandInterval::getRequiredSeats).sum();
        } catch (Exception ex) {
            return 0;
        }
    }

    private int currentCoverageForWindow(java.time.LocalDate day, com.example.shiftv1.skill.Skill skill,
                                         java.time.LocalTime s, java.time.LocalTime e) {
        int cnt = 0;
        try {
            java.util.List<ShiftAssignment> list = assignmentRepository.findByWorkDate(day);
            for (ShiftAssignment sa : list) {
                if (!(sa.getStartTime().isBefore(e) && sa.getEndTime().isAfter(s))) continue;
                String code = extractSkillCode(sa.getShiftName());
                if (code != null && skill.getCode() != null && skill.getCode().equalsIgnoreCase(code)) {
                    cnt++;
                } else if (employeeHasSkill(sa.getEmployee(), skill)) {
                    cnt++;
                }
            }
        } catch (Exception ignored) {}
        return cnt;
    }

    private String extractSkillCode(String shiftName) {
        if (shiftName == null) return null;
        int i = shiftName.indexOf('-');
        if (i < 0 || i+1 >= shiftName.length()) return null;
        return shiftName.substring(i+1).trim();
    }

    private boolean isAvailableForWindow(java.lang.Long empId, java.time.LocalDate day, java.time.LocalTime start, java.time.LocalTime end) {
        try {
            java.util.List<com.example.shiftv1.employee.EmployeeAvailability> avs = availabilityRepository.findByEmployeeId(empId);
            if (avs == null || avs.isEmpty()) return true;
            java.time.DayOfWeek dow = day.getDayOfWeek();
            for (var a : avs) {
                if (a.getDayOfWeek() != dow) continue;
                if (!start.isBefore(a.getStartTime()) && !end.isAfter(a.getEndTime())) return true;
            }
            return false;
        } catch (Exception e) { return true; }
    }

    private boolean withinDailyWithOvertime(java.lang.Long empId, java.time.LocalDate day, java.time.LocalTime newStart, java.time.LocalTime newEnd) {
        int used = 0;
        java.util.List<ShiftAssignment> list = assignmentRepository.findByWorkDate(day);
        for (ShiftAssignment sa : list) {
            if (sa.getEmployee() == null || !java.util.Objects.equals(sa.getEmployee().getId(), empId)) continue;
            used += Math.max(1, (int)java.time.Duration.between(sa.getStartTime(), sa.getEndTime()).toHours());
        }
        int add = Math.max(0, (int)java.time.Duration.between(newStart, newEnd).toHours());
        com.example.shiftv1.employee.EmployeeRule rule = ruleByEmployee.get(empId);
        if (rule == null || rule.getDailyMaxHours() == null) return true;
        int base = rule.getDailyMaxHours();
        if (used + add <= base) return true;
        com.example.shiftv1.employee.Employee emp = employeeRepository.findById(empId).orElse(null);
        if (emp == null) return false;
        boolean allowOver = Boolean.TRUE.equals(emp.getOvertimeAllowed());
        int extra = emp.getOvertimeDailyMaxHours() == null ? 0 : emp.getOvertimeDailyMaxHours();
        return allowOver && (used + add) <= (base + extra);
    }
