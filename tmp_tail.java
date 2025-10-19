                        });
                if (limitedMismatch) {
                    limitedMismatchNames.add(e.getName());
                }
            }

            int requiredForShift = computeRequiredForShift(day, config);
            results.add(new ShiftDiagnostics(
                    config.getName(),
                    config.getStartTime(),
                    config.getEndTime(),
                    requiredForShift,
                    assignedForShift.size(),
                    assignedNames,
                    unavailableNames,
                    limitedMismatchNames,
                    alreadyAssignedNames,
                    employees.stream().filter(e -> preferred.contains(e.getId())).map(Employee::getName).toList()
            ));
        }
        return new DiagnosticReport(day, results);
    }

    private List<ShiftAssignment> assignEmployeesForShiftWithReport(LocalDate day,
                                                                    ShiftConfig shiftConfig,
                                                                    List<Employee> employees,
                                                                    Map<Long, Integer> monthlyAssignmentCounts,
                                                                    Map<LocalDate, Set<Long>> dailyAssignments,
                                                                    Map<LocalDate, Map<Long, List<EmployeeConstraint>>> constraintsByDate,
                                                                    List<ShortageInfo> shortages) {
        List<ShiftAssignment> assignments = new ArrayList<>();
        Set<Long> assignedToday = dailyAssignments.computeIfAbsent(day, d -> new HashSet<>());
        Set<Long> preferredEmployees = getPreferredEmployeesForShift(day, shiftConfig, constraintsByDate);

        int requiredForShift = computeRequiredForShift(day, shiftConfig);
        while (assignments.size() < requiredForShift) {
            Employee candidate = selectNextCandidate(employees, assignedToday, preferredEmployees,
                    monthlyAssignmentCounts, day, shiftConfig, constraintsByDate);
            if (candidate == null) {
                break;
            }

            assignments.add(new ShiftAssignment(day,
                    shiftConfig.getName(),
                    shiftConfig.getStartTime(),
                    shiftConfig.getEndTime(),
                    candidate));
            assignedToday.add(candidate.getId());
            preferredEmployees.remove(candidate.getId());
            monthlyAssignmentCounts.merge(candidate.getId(), 1, Integer::sum);
        }

        if (assignments.size() < shiftConfig.getRequiredEmployees()) {
            logger.warn("割当不足: {} の {} は必要:{} 実際:{}",
                    day, shiftConfig.getName(), shiftConfig.getRequiredEmployees(), assignments.size());
            shortages.add(new ShortageInfo(day, shiftConfig.getName(),
                    shiftConfig.getRequiredEmployees(), assignments.size()));
        }

        return assignments;
    }

    @Transactional
    @CacheEvict(value = "monthly-schedules", key = "#year + '-' + #month")
    public void resetMonthlySchedule(int year, int month) {
        YearMonth target = YearMonth.of(year, month);
        LocalDate start = target.atDay(1);
        LocalDate end = target.atEndOfMonth();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            assignmentRepository.deleteByWorkDate(d);
        }
        logger.info("対象月のシフトを初期化: {}-{}", year, month);
    }

    private List<ShiftAssignment> sortAssignments(List<ShiftAssignment> assignments) {
        return assignments.stream()
                .sorted(getAssignmentComparator())
                .toList();
    }

    private Comparator<ShiftAssignment> getAssignmentComparator() {
        return Comparator
                .comparing(ShiftAssignment::getWorkDate)
                .thenComparing(ShiftAssignment::getStartTime)
                .thenComparing(ShiftAssignment::getShiftName)
                .thenComparing(assignment -> assignment.getEmployee().getId());
    }

    private List<ShiftAssignment> assignEmployeesForShift(LocalDate day,
                                                          ShiftConfig shiftConfig,
                                                          List<Employee> employees,
                                                          Map<Long, Integer> monthlyAssignmentCounts,
                                                          Map<LocalDate, Set<Long>> dailyAssignments,
                                                          Map<LocalDate, Map<Long, List<EmployeeConstraint>>> constraintsByDate) {
        List<ShiftAssignment> assignments = new ArrayList<>();
        Set<Long> assignedToday = dailyAssignments.computeIfAbsent(day, d -> new HashSet<>());
        Set<Long> preferredEmployees = getPreferredEmployeesForShift(day, shiftConfig, constraintsByDate);

        int shiftHours = java.time.Duration.between(shiftConfig.getStartTime(), shiftConfig.getEndTime()).toHoursPart();
        if (shiftHours == 0) {
            shiftHours = (int) java.time.Duration.between(shiftConfig.getStartTime(), shiftConfig.getEndTime()).toHours();
        }
        int requiredForShift = computeRequiredForShift(day, shiftConfig);
        while (assignments.size() < requiredForShift) {
            Employee candidate = selectNextCandidate(employees, assignedToday, preferredEmployees,
                    monthlyAssignmentCounts, day, shiftConfig, constraintsByDate);
            if (candidate == null) {
                break;
            }

            assignments.add(new ShiftAssignment(day,
                    shiftConfig.getName(),
                    shiftConfig.getStartTime(),
                    shiftConfig.getEndTime(),
                    candidate));
            assignedToday.add(candidate.getId());
            preferredEmployees.remove(candidate.getId());
            monthlyAssignmentCounts.merge(candidate.getId(), 1, Integer::sum);
            // 累積（日）時間を記録
            dailyAssignedHours.computeIfAbsent(day, d -> new HashMap<>())
                    .merge(candidate.getId(), shiftHours, Integer::sum);
        }

        if (assignments.size() < shiftConfig.getRequiredEmployees()) {
            // 不足があっても例外にせず、警告ログのみ出して続行
            logger.warn("割当不足: {} の {} は必要:{} 実際:{}",
                    day, shiftConfig.getName(), shiftConfig.getRequiredEmployees(), assignments.size());
        }

        return assignments;
    }

    private Employee selectNextCandidate(List<Employee> employees,
                                         Set<Long> assignedToday,
                                         Set<Long> preferredEmployees,
                                         Map<Long, Integer> monthlyAssignmentCounts,
                                         LocalDate day,
                                         ShiftConfig shiftConfig,
                                         Map<LocalDate, Map<Long, List<EmployeeConstraint>>> constraintsByDate) {

        List<Employee> sortedCandidates = new ArrayList<>(employees);
        sortedCandidates.sort(Comparator
                .comparing((Employee e) -> !preferredEmployees.contains(e.getId()))
                .thenComparing(e -> monthlyAssignmentCounts.getOrDefault(e.getId(), 0))
                .thenComparing(Employee::getId));

        for (Employee candidate : sortedCandidates) {
            Long candidateId = candidate.getId();
            // 同日複数シフト可否（ルールで許可されていれば通す）
            EmployeeRule rule = ruleByEmployee.get(candidateId);
            boolean allowMulti = rule != null && Boolean.TRUE.equals(rule.getAllowMultipleShiftsPerDay());
            if (!allowMulti && assignedToday.contains(candidateId)) {
                continue;
            }
            // required skill check
            if (shiftConfig.getRequiredSkill() != null) {
                boolean hasSkill = candidate.getSkills() != null &&
                        candidate.getSkills().stream().anyMatch(s -> s.getId().equals(shiftConfig.getRequiredSkill().getId()));
                if (!hasSkill) {
                    continue;
                }
            }
            if (!isEmployeeAvailable(candidate, day, shiftConfig, constraintsByDate)) {
                continue;
            }
            return candidate;
        }
        return null;
    }

    private boolean isEmployeeAvailable(Employee employee,
                                        LocalDate day,
                                        ShiftConfig shiftConfig,
                                        Map<LocalDate, Map<Long, List<EmployeeConstraint>>> constraintsByDate) {
        // 恒常ルール: 祝日可否
        EmployeeRule rule = ruleByEmployee.get(employee.getId());
        boolean isHoliday = false;
        try { isHoliday = holidayRepository.findDatesBetween(day, day).contains(day);} catch (Exception ignored) {}
        if (rule != null && Boolean.FALSE.equals(rule.getAllowHolidayWork()) && isHoliday) {
            return false;
        }
        // 恒常ルール: 週間可用性（当日曜日に可用スロットが存在し、シフトが完全に内包されるか）
        List<EmployeeAvailability> avs = availabilityByEmployee.getOrDefault(employee.getId(), List.of());
        if (avs != null && !avs.isEmpty()) {
            boolean ok = avs.stream()
                    .filter(a -> a.getDayOfWeek() == day.getDayOfWeek())
                    .anyMatch(a -> !shiftConfig.getStartTime().isBefore(a.getStartTime()) && !shiftConfig.getEndTime().isAfter(a.getEndTime()));
            if (!ok) return false;
        }
        // 恒常ルール: 日上限時間（既割当 + 当該シフト）
        if (rule != null && rule.getDailyMaxHours() != null) {
            int used = dailyAssignedHours.getOrDefault(day, Collections.emptyMap()).getOrDefault(employee.getId(), 0);
            int add = (int) java.time.Duration.between(shiftConfig.getStartTime(), shiftConfig.getEndTime()).toHours();
            if (used + add > rule.getDailyMaxHours()) {
                return false;
            }
        }

        Map<Long, List<EmployeeConstraint>> constraintsForDay = constraintsByDate.getOrDefault(day, Collections.emptyMap());
        List<EmployeeConstraint> employeeConstraints = constraintsForDay.get(employee.getId());
        if (employeeConstraints == null || employeeConstraints.isEmpty()) {
            return true;
        }

        for (EmployeeConstraint constraint : employeeConstraints) {
            if (constraint.getType() == null) {
                continue;
            }
            switch (constraint.getType()) {
                case UNAVAILABLE, VACATION, SICK_LEAVE, PERSONAL -> {
                    logger.debug("従業員{}は{}に{}のため勤務不可", employee.getName(), day, constraint.getType());
                    return false;
                }
                case LIMITED -> {
                    LocalTime availableStart = constraint.getStartTime() != null ? constraint.getStartTime() : LocalTime.MIN;
                    LocalTime availableEnd = constraint.getEndTime() != null ? constraint.getEndTime() : LocalTime.MAX;
                    if (shiftConfig.getStartTime().isBefore(availableStart) || shiftConfig.getEndTime().isAfter(availableEnd)) {
                        logger.debug("従業員{}は{}のシフト{}に時間制限のため割り当て不可", employee.getName(), day, shiftConfig.getName());
                        return false;
                    }
                }
                case PREFERRED -> {
                    // 希望は優先度のみに影響するため可否には影響させない
                }
            }
        }
        return true;
    }

    private Set<Long> getPreferredEmployeesForShift(LocalDate day,
                                                     ShiftConfig shiftConfig,
                                                     Map<LocalDate, Map<Long, List<EmployeeConstraint>>> constraintsByDate) {
        Map<Long, List<EmployeeConstraint>> constraintsForDay = constraintsByDate.get(day);
        if (constraintsForDay == null || constraintsForDay.isEmpty()) {
            return new HashSet<>();
        }

        Set<Long> preferred = new HashSet<>();
        for (Map.Entry<Long, List<EmployeeConstraint>> entry : constraintsForDay.entrySet()) {
            Long employeeId = entry.getKey();
            for (EmployeeConstraint constraint : entry.getValue()) {
                if (constraint.getType() == EmployeeConstraint.ConstraintType.PREFERRED &&
                        matchesPreferredTime(constraint, shiftConfig.getStartTime(), shiftConfig.getEndTime())) {
                    preferred.add(employeeId);
                    break;
                }
            }
        }
        return preferred;
    }

    private boolean matchesPreferredTime(EmployeeConstraint constraint, LocalTime shiftStart, LocalTime shiftEnd) {
        LocalTime preferredStart = constraint.getStartTime() != null ? constraint.getStartTime() : LocalTime.MIN;
        LocalTime preferredEnd = constraint.getEndTime() != null ? constraint.getEndTime() : LocalTime.MAX;
        return !shiftEnd.isBefore(preferredStart) && !shiftStart.isAfter(preferredEnd);
    }

    private List<Employee> rotateEmployees(List<Employee> employees, int offset) {
        if (employees.isEmpty()) {
            return employees;
        }

        int normalizedOffset = offset % employees.size();
        if (normalizedOffset == 0) {
            return employees;
        }

        List<Employee> rotated = new ArrayList<>(employees.size());
        for (int i = 0; i < employees.size(); i++) {
            rotated.add(employees.get((i + normalizedOffset) % employees.size()));
        }
        logger.debug("従業員リストを{}ポジション回転しました", normalizedOffset);
        return rotated;
    }

    private int determineRotationOffset(Optional<ShiftAssignment> lastAssignmentBeforePeriod, List<Employee> employees) {
        if (lastAssignmentBeforePeriod.isEmpty() || employees.isEmpty()) {
            return 0;
        }

        Employee lastEmployee = lastAssignmentBeforePeriod.get().getEmployee();
        if (lastEmployee == null || lastEmployee.getId() == null) {
            return 0;
        }

        for (int i = 0; i < employees.size(); i++) {
            if (lastEmployee.getId().equals(employees.get(i).getId())) {
                int offset = (i + 1) % employees.size();
                logger.debug("前回の割り当て従業員{}に基づきオフセット{}を使用", lastEmployee.getId(), offset);
                return offset;
            }
        }
        return 0;
    }

    private List<ShiftAssignment> loadRecentAssignments(LocalDate startDate) {
        try {
            LocalDate historyEnd = startDate.minusDays(1);
            YearMonth previousMonth = YearMonth.from(startDate).minusMonths(1);
            LocalDate historyStart = previousMonth.atDay(1);

            if (historyEnd.isBefore(historyStart)) {
                return List.of();
            }

            List<ShiftAssignment> assignments = assignmentRepository.findByWorkDateBetween(historyStart, historyEnd);
            logger.debug("過去期間{}から{}の{}件の割り当てを読み込み", historyStart, historyEnd, assignments.size());
            return assignments;
        } catch (DateTimeException e) {
            logger.debug("過去割り当ての取得に失敗しました: {}", e.getMessage());
            return List.of();
        }
    }

    private void preloadAssignmentCounts(Map<Long, Integer> monthlyAssignmentCounts, List<ShiftAssignment> recentAssignments) {
        for (ShiftAssignment assignment : recentAssignments) {
            Employee employee = assignment.getEmployee();
            if (employee != null && employee.getId() != null) {
                monthlyAssignmentCounts.merge(employee.getId(), 1, Integer::sum);
            }
        }
        if (!monthlyAssignmentCounts.isEmpty()) {
            logger.debug("過去の割り当てを{}件分プリロードしました", monthlyAssignmentCounts.size());
        }
    }

    // 祝日・曜日に基づく設定選択（対象=平日/週末は廃止）
    private List<ShiftConfig> selectConfigsForDay(List<ShiftConfig> activeConfigs, LocalDate day, boolean isHoliday) {
        if (isHoliday) {
            List<ShiftConfig> holidayConfigs = activeConfigs.stream()
                    .filter(c -> Boolean.TRUE.equals(c.getHoliday()))
                    .sorted(Comparator.comparing(ShiftConfig::getStartTime))
                    .toList();
            if (!holidayConfigs.isEmpty()) return holidayConfigs;
        }
        // まず複数曜日指定（days）を優先
        List<ShiftConfig> daysConfigs = activeConfigs.stream()
                .filter(c -> c.getDays() != null && !c.getDays().isEmpty() && c.getDays().contains(day.getDayOfWeek()))
                .sorted(Comparator.comparing(ShiftConfig::getStartTime))
                .toList();
        if (!daysConfigs.isEmpty()) return daysConfigs;

        List<ShiftConfig> dowConfigs = activeConfigs.stream()
                .filter(c -> c.getDayOfWeek() != null && c.getDayOfWeek() == day.getDayOfWeek())
                .sorted(Comparator.comparing(ShiftConfig::getStartTime))
                .toList();
        if (!dowConfigs.isEmpty()) return dowConfigs;
        // 特に曜日/祝日指定が無い設定のみ（以前の「対象」には依存しない）
        return activeConfigs.stream()
                .filter(c -> !Boolean.TRUE.equals(c.getHoliday()))
                .filter(c -> c.getDayOfWeek() == null)
                .filter(c -> c.getDays() == null || c.getDays().isEmpty())
                // 既存データの後方互換: 週末フラグが立っている設定は平日では除外
                .sorted(Comparator.comparing(ShiftConfig::getStartTime))
                .toList();
    }

    // Demand integration: compute required seats for a shift based on DemandInterval
    private int computeRequiredForShift(LocalDate day, ShiftConfig shiftConfig) {
        try {
            List<com.example.shiftv1.demand.DemandInterval> intervals = demandRepository.findEffectiveForDate(day, day.getDayOfWeek());
            if (intervals == null || intervals.isEmpty()) {
                return Math.max(1, java.util.Optional.ofNullable(shiftConfig.getRequiredEmployees()).orElse(1));
            }
            java.time.LocalTime s = shiftConfig.getStartTime();
            java.time.LocalTime e = shiftConfig.getEndTime();
            Long requiredSkillId = shiftConfig.getRequiredSkill() != null ? shiftConfig.getRequiredSkill().getId() : null;
            // Build critical points (shift start/end + interval boundaries)
            java.util.TreeSet<java.time.LocalTime> points = new java.util.TreeSet<>();
            points.add(s);
            points.add(e);
            for (com.example.shiftv1.demand.DemandInterval di : intervals) {
                if (Boolean.FALSE.equals(di.getActive())) continue;
                if (di.getEndTime().isAfter(s) && di.getStartTime().isBefore(e)) {
                    points.add(di.getStartTime().isBefore(s) ? s : di.getStartTime());
                    points.add(di.getEndTime().isAfter(e) ? e : di.getEndTime());
                }
            }
            int maxRequired = 0;
            java.time.LocalTime prev = null;
            for (java.time.LocalTime t : points) {
                if (prev != null) {
                    java.time.LocalTime segStart = prev;
                    java.time.LocalTime segEnd = t;
                    if (!segStart.isBefore(segEnd)) continue;
                    // Compute global seats with date-specific overriding weekly definitions
                    java.util.List<com.example.shiftv1.demand.DemandInterval> globalIntervals = intervals.stream()
                            .filter(di -> di.getSkill() == null)
                            .filter(di -> di.getEndTime().isAfter(segStart) && di.getStartTime().isBefore(segEnd))
                            .toList();
                    boolean hasDateSpecificGlobal = globalIntervals.stream().anyMatch(di -> di.getDate() != null);
                    int global = (hasDateSpecificGlobal
                            ? globalIntervals.stream().filter(di -> di.getDate() != null)
                            : globalIntervals.stream())
                            .mapToInt(com.example.shiftv1.demand.DemandInterval::getRequiredSeats)
                            .sum();
                    int skillReq = 0;
                    if (requiredSkillId != null) {
                        java.util.List<com.example.shiftv1.demand.DemandInterval> skillIntervals = intervals.stream()
                                .filter(di -> di.getSkill() != null && requiredSkillId.equals(di.getSkill().getId()))
                                .filter(di -> di.getEndTime().isAfter(segStart) && di.getStartTime().isBefore(segEnd))
                                .toList();
                        boolean hasDateSpecificSkill = skillIntervals.stream().anyMatch(di -> di.getDate() != null);
                        skillReq = (hasDateSpecificSkill
                                ? skillIntervals.stream().filter(di -> di.getDate() != null)
                                : skillIntervals.stream())
                                .mapToInt(com.example.shiftv1.demand.DemandInterval::getRequiredSeats)
                                .sum();
                    }
                    int segmentRequired;
                    if (requiredSkillId != null) {
                        // Skill-specific shift: use skill-specific demand if present, otherwise fallback to global.
                        segmentRequired = (skillReq > 0 ? skillReq : global);
                    } else {
                        // Non-skill shift: use global demand only.
                        segmentRequired = global;
                    }
                    maxRequired = Math.max(maxRequired, segmentRequired);
                }
                prev = t;
            }
            if (maxRequired <= 0) {
                return Math.max(1, java.util.Optional.ofNullable(shiftConfig.getRequiredEmployees()).orElse(1));
            }
            return maxRequired;
        } catch (Exception ex) {
            return Math.max(1, java.util.Optional.ofNullable(shiftConfig.getRequiredEmployees()).orElse(1));
        }
    }
}
