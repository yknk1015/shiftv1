package com.example.shiftv1.demand;

import com.example.shiftv1.common.ApiResponse;
import com.example.shiftv1.skill.Skill;
import com.example.shiftv1.skill.SkillRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api/demand")
public class DemandController {

    private final DemandIntervalRepository repository;
    private final SkillRepository skillRepository;

    public DemandController(DemandIntervalRepository repository, SkillRepository skillRepository) {
        this.repository = repository;
        this.skillRepository = skillRepository;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<DemandInterval>>> list(
            @RequestParam(value = "date", required = false) LocalDate date,
            @RequestParam(value = "dayOfWeek", required = false) DayOfWeek dayOfWeek
    ) {
        List<DemandInterval> data;
        if (date != null) {
            data = repository.findByDateOrderBySortOrderAscIdAsc(date);
        } else if (dayOfWeek != null) {
            data = repository.findByDayOfWeekOrderBySortOrderAscIdAsc(dayOfWeek);
        } else {
            data = repository.findAllByOrderBySortOrderAscIdAsc();
        }
        return ResponseEntity.ok(ApiResponse.success("���v�C���^�[�o���ꗗ���擾���܂���", data));
    }

    @GetMapping("/effective")
    public ResponseEntity<ApiResponse<List<DemandInterval>>> effective(@RequestParam("date") LocalDate date) {
        List<DemandInterval> data = repository.findEffectiveForDate(date, date.getDayOfWeek());
        return ResponseEntity.ok(ApiResponse.success("�����̎��v�C���^�[�o�����擾���܂���", data));
    }

    @GetMapping("/aggregate")
    public ResponseEntity<ApiResponse<Map<String, Object>>> aggregate(
            @RequestParam(name = "period", required = false, defaultValue = "month") String period,
            @RequestParam(name = "date", required = false) java.time.LocalDate date,
            @RequestParam(name = "year", required = false) Integer year,
            @RequestParam(name = "month", required = false) Integer month,
            @RequestParam(name = "granularity", required = false, defaultValue = "60") Integer granularity,
            @RequestParam(name = "skillIds", required = false) String skillIdsCsv
    ) {
        java.time.LocalDate today = java.time.LocalDate.now();
        String p = (period == null ? "month" : period.trim().toLowerCase());
        java.time.LocalDate start;
        java.time.LocalDate end;
        int y; int m;
        if ("day".equals(p)) {
            java.time.LocalDate d = (date == null ? today : date);
            start = d; end = d; y = d.getYear(); m = d.getMonthValue();
        } else if ("week".equals(p)) {
            java.time.LocalDate d = (date == null ? today : date);
            java.time.DayOfWeek MON = java.time.DayOfWeek.MONDAY;
            start = d.with(java.time.temporal.TemporalAdjusters.previousOrSame(MON));
            end = start.plusDays(6);
            y = d.getYear(); m = d.getMonthValue();
        } else { // month (default)
            int yy = (year == null ? (date!=null? date.getYear(): today.getYear()) : year);
            int mm = (month == null ? (date!=null? date.getMonthValue(): today.getMonthValue()) : month);
            java.time.YearMonth ym = java.time.YearMonth.of(yy, mm);
            start = ym.atDay(1);
            end = ym.atEndOfMonth();
            y = yy; m = mm;
        }

        final int G = Math.max(1, granularity);
        final int slots = (int) Math.ceil(24 * 60.0 / G);

        java.util.Map<Long, int[]> monthlyBySkill = new java.util.HashMap<>();
        java.util.Set<Long> skillIds = new java.util.HashSet<>();
        java.util.Set<Long> filterSkills = new java.util.HashSet<>();
        if (skillIdsCsv != null && !skillIdsCsv.isBlank()) {
            for (String tok : skillIdsCsv.split(",")) {
                try { filterSkills.add(Long.parseLong(tok.trim())); } catch (Exception ignore) {}
            }
        }

        for (java.time.LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            java.util.List<DemandInterval> intervals = repository.findEffectiveForDate(day, day.getDayOfWeek());
            if (intervals.isEmpty()) continue;

            java.util.Map<Long, int[]> weekly = new java.util.HashMap<>();
            java.util.Map<Long, int[]> dateSpec = new java.util.HashMap<>();

            for (DemandInterval di : intervals) {
                if (di.getSkill() == null) continue; // safety
                Long sid = di.getSkill().getId();
                if (sid == null) continue;
                if (!filterSkills.isEmpty() && !filterSkills.contains(sid)) continue;
                int sMin = di.getStartTime().getHour() * 60 + di.getStartTime().getMinute();
                int eMin = di.getEndTime().getHour() * 60 + di.getEndTime().getMinute();
                if (eMin <= 0 || sMin >= 24*60) continue;
                int sIdx = Math.max(0, (int)Math.floor(sMin / (double)G));
                int eIdx = Math.min(slots, (int)Math.ceil(eMin / (double)G));
                boolean dateSpecific = di.getDate() != null;
                int[] arr = (dateSpecific ? dateSpec : weekly).computeIfAbsent(sid, k -> new int[slots]);
                int add = Math.max(0, di.getRequiredSeats());
                for (int i = sIdx; i < eIdx; i++) arr[i] += add;
                skillIds.add(sid);
            }

            // combine date-specific overrides weekly for the day, then accumulate to monthly
            for (Long sid : skillIds) {
                int[] wArr = weekly.get(sid);
                int[] dArr = dateSpec.get(sid);
                if (wArr == null && dArr == null) continue;
                int[] eff = new int[slots];
                for (int i = 0; i < slots; i++) {
                    int w = (wArr == null ? 0 : wArr[i]);
                    int d = (dArr == null ? 0 : dArr[i]);
                    eff[i] = (d > 0 ? d : w);
                }
                int[] monthArr = monthlyBySkill.computeIfAbsent(sid, k -> new int[slots]);
                for (int i = 0; i < slots; i++) monthArr[i] += eff[i];
            }
        }

        java.util.List<Long> orderedSkillIds = monthlyBySkill.keySet().stream().sorted().toList();
        java.util.List<java.util.Map<String, Object>> skills = new java.util.ArrayList<>();
        for (Long sid : orderedSkillIds) {
            java.util.Map<String, Object> mobj = new java.util.HashMap<>();
            mobj.put("id", sid);
            try {
                var s = skillRepository.findById(sid).orElse(null);
                if (s != null) { mobj.put("code", s.getCode()); mobj.put("name", s.getName()); }
            } catch (Exception ignore) {}
            skills.add(mobj);
        }

        String[] slotsLabels = new String[slots];
        for (int i=0;i<slots;i++) {
            int ms = i * G;
            int me = Math.min(24*60, (i+1)*G);
            java.time.LocalTime ts = java.time.LocalTime.of(ms/60, ms%60);
            java.time.LocalTime te = java.time.LocalTime.of((me==24*60?23:me/60), (me==24*60?59:me%60));
            slotsLabels[i] = String.format("%02d:%02d-%02d:%02d", ts.getHour(), ts.getMinute(), te.getHour(), te.getMinute());
        }

        int[] totalsPerSlot = new int[slots];
        java.util.Map<Long, Integer> totalsPerSkill = new java.util.HashMap<>();
        for (var e : monthlyBySkill.entrySet()) {
            Long sid = e.getKey();
            int sum = 0;
            for (int i=0;i<slots;i++){ totalsPerSlot[i]+=e.getValue()[i]; sum += e.getValue()[i]; }
            totalsPerSkill.put(sid, sum);
        }

        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("period", p);
        body.put("year", y);
        body.put("month", m);
        body.put("startDate", start.toString());
        body.put("endDate", end.toString());
        body.put("granularity", G);
        body.put("slots", slotsLabels);
        body.put("skills", skills);
        body.put("matrix", monthlyBySkill);
        body.put("totalsPerSlot", totalsPerSlot);
        body.put("totalsPerSkill", totalsPerSkill);
        return ResponseEntity.ok(ApiResponse.success("集計しました", body));
    }

    public record ExportRequest(String period,
                                java.time.LocalDate date,
                                Integer year,
                                Integer month,
                                Integer granularity,
                                java.util.List<Long> skillIds,
                                String dir,
                                String filename) {}

    @PostMapping("/aggregate/export")
    public ResponseEntity<ApiResponse<java.util.Map<String, Object>>> exportCsv(@RequestBody ExportRequest req) {
        String p = (req.period()==null?"month":req.period().trim().toLowerCase());
        int G = Math.max(1, java.util.Optional.ofNullable(req.granularity()).orElse(60));
        String dir = (req.dir()==null?"":req.dir().trim());
        String filename = (req.filename()==null?"demand-aggregate.csv":req.filename().trim());
        if (dir.isEmpty()) {
            return ResponseEntity.badRequest().body(ApiResponse.failure("出力先ディレクトリを指定してください"));
        }
        java.util.Set<Long> filterSkills = new java.util.HashSet<>();
        if (req.skillIds()!=null) filterSkills.addAll(req.skillIds());

        // reuse aggregate computation via internal call
        var resp = aggregate(p, req.date(), req.year(), req.month(), G,
                filterSkills.isEmpty()? null : filterSkills.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(",")));
        if (resp.getStatusCode().isError() || resp.getBody()==null || !Boolean.TRUE.equals(resp.getBody().success)) {
            return ResponseEntity.status(resp.getStatusCode()).body(ApiResponse.failure("集計に失敗しました"));
        }
        var data = (java.util.Map<String, Object>) resp.getBody().getData();
        java.util.List<String> slots = (java.util.List<String>) data.get("slots");
        java.util.List<java.util.Map<String,Object>> skills = (java.util.List<java.util.Map<String,Object>>) data.get("skills");
        java.util.Map<?,?> matrix = (java.util.Map<?,?>) data.get("matrix");
        int[] totalsPerSlot = (int[]) data.get("totalsPerSlot");

        java.io.File outDir = new java.io.File(dir);
        if (!outDir.exists() && !outDir.mkdirs()) {
            return ResponseEntity.badRequest().body(ApiResponse.failure("出力先ディレクトリの作成に失敗しました"));
        }
        java.io.File outFile = new java.io.File(outDir, filename);
        try (java.io.PrintWriter pw = new java.io.PrintWriter(new java.io.OutputStreamWriter(new java.io.FileOutputStream(outFile), java.nio.charset.StandardCharsets.UTF_8))) {
            // header
            pw.print("Skill/TimeSlot");
            for (String s : slots) { pw.print(","); pw.print(s); }
            pw.println(",合計");
            // rows
            for (var s : skills) {
                Long sid = (Long) s.get("id");
                String name = (String) (s.get("name")!=null? s.get("name") : s.get("code"));
                pw.print(name==null? ("#"+sid) : name.replace(","," "));
                int sum = 0;
                int[] arr = (int[]) ((java.util.Map<?,?>)matrix).get(sid);
                if (arr == null) { arr = new int[slots.size()]; }
                for (int v : arr) { pw.print(","); pw.print(v); sum += v; }
                pw.print(","); pw.println(sum);
            }
            // totals
            pw.print("合計(全スキル)");
            int totalAll = 0;
            if (totalsPerSlot != null) {
                for (int v : totalsPerSlot) { pw.print(","); pw.print(v); totalAll += v; }
            } else {
                for (int i=0;i<slots.size();i++){ pw.print(",0"); }
            }
            pw.print(","); pw.println(totalAll);
        } catch (Exception ex) {
            return ResponseEntity.internalServerError().body(ApiResponse.failure("CSV出力に失敗しました"));
        }

        java.util.Map<String, Object> meta = new java.util.HashMap<>();
        meta.put("path", outFile.getAbsolutePath());
        return ResponseEntity.ok(ApiResponse.success("CSVを出力しました", meta));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<DemandInterval>> create(@Valid @RequestBody DemandRequest req) {
        // Global demand abolished: enforce skill
        if (req.skillId() == null) {
            return ResponseEntity.badRequest().body(ApiResponse.failure("スキルは必須です"));
        }
        // require either date or dayOfWeek
        if (req.date() == null && req.dayOfWeek() == null) {
            return ResponseEntity.badRequest().body(ApiResponse.failure("日付または曜日のいずれかを指定してください"));
        }
        // time range validation
        if (req.startTime() == null || req.endTime() == null || !req.startTime().isBefore(req.endTime())) {
            return ResponseEntity.badRequest().body(ApiResponse.failure("開始時刻は終了時刻より前である必要があります"));
        }

        DemandInterval d = new DemandInterval();
        d.setDate(req.date());
        d.setDayOfWeek(req.dayOfWeek());
        d.setStartTime(req.startTime());
        d.setEndTime(req.endTime());
        d.setRequiredSeats(req.requiredSeats());
        d.setActive(req.active() != null ? req.active() : true);
        // assign sort order at the end
        Integer maxOrder = repository.findMaxSortOrder();
        d.setSortOrder((maxOrder == null ? 0 : maxOrder) + 1);

        Skill s = skillRepository.findById(req.skillId()).orElseThrow(() -> new IllegalArgumentException("�X�L����������܂���"));
        d.setSkill(s);

        DemandInterval saved = repository.save(d);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("���v�C���^�[�o�����쐬���܂���", saved));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<DemandInterval>> update(@PathVariable Long id, @Valid @RequestBody DemandRequest req) {
        Optional<DemandInterval> od = repository.findById(id);
        if (od.isEmpty()) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.failure("���v�C���^�[�o����������܂���"));
        DemandInterval d = od.get();

        // Global demand abolished: enforce skill
        if (req.skillId() == null) {
            return ResponseEntity.badRequest().body(ApiResponse.failure("スキルは必須です"));
        }
        // require either date or dayOfWeek
        if (req.date() == null && req.dayOfWeek() == null) {
            return ResponseEntity.badRequest().body(ApiResponse.failure("日付または曜日のいずれかを指定してください"));
        }
        // time range validation
        if (req.startTime() == null || req.endTime() == null || !req.startTime().isBefore(req.endTime())) {
            return ResponseEntity.badRequest().body(ApiResponse.failure("開始時刻は終了時刻より前である必要があります"));
        }

        d.setDate(req.date());
        d.setDayOfWeek(req.dayOfWeek());
        d.setStartTime(req.startTime());
        d.setEndTime(req.endTime());
        d.setRequiredSeats(req.requiredSeats());
        d.setActive(req.active() != null ? req.active() : d.getActive());

        Skill s = skillRepository.findById(req.skillId()).orElseThrow(() -> new IllegalArgumentException("�X�L����������܂���"));
        d.setSkill(s);

        DemandInterval saved = repository.save(d);
        return ResponseEntity.ok(ApiResponse.success("���v�C���^�[�o�����X�V���܂���", saved));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        if (!repository.existsById(id)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.failure("���v�C���^�[�o����������܂���"));
        }
        repository.deleteById(id);
        return ResponseEntity.ok(ApiResponse.success("���v�C���^�[�o�����폜���܂���", null));
    }

    @PostMapping("/swap")
    public ResponseEntity<ApiResponse<java.util.Map<String, Object>>> swapSortOrder(@RequestParam("a") Long idA,
                                                                                   @RequestParam("b") Long idB) {
        Optional<DemandInterval> oa = repository.findById(idA);
        Optional<DemandInterval> ob = repository.findById(idB);
        if (oa.isEmpty() || ob.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.failure("����܂��͗����̎��v��������܂���"));
        }
        DemandInterval a = oa.get();
        DemandInterval b = ob.get();
        Integer ao = a.getSortOrder();
        Integer bo = b.getSortOrder();
        if (ao == null || bo == null) {
            Integer max = repository.findMaxSortOrder();
            if (ao == null) { ao = (max == null ? 0 : max + 1); a.setSortOrder(ao); }
            if (bo == null) { bo = (max == null ? 0 : max + 2); b.setSortOrder(bo); }
        }
        a.setSortOrder(bo);
        b.setSortOrder(ao);
        repository.save(a);
        repository.save(b);
        return ResponseEntity.ok(ApiResponse.success("���я������ւ��܂���", java.util.Map.of(
                "a", java.util.Map.of("id", a.getId(), "sortOrder", a.getSortOrder()),
                "b", java.util.Map.of("id", b.getId(), "sortOrder", b.getSortOrder())
        )));
    }

    @PostMapping("/{id}/move")
    public ResponseEntity<ApiResponse<DemandInterval>> move(@PathVariable Long id,
                                                            @RequestParam("direction") String direction) {
        Optional<DemandInterval> od = repository.findById(id);
        if (od.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.failure("���v�C���^�[�o����������܂���"));
        }
        DemandInterval current = od.get();
        Integer order = current.getSortOrder();
        if (order == null) {
            Integer max = repository.findMaxSortOrder();
            current.setSortOrder((max == null ? 0 : max) + 1);
            repository.save(current);
            return ResponseEntity.ok(ApiResponse.success("���������������܂���", current));
        }
        DemandInterval neighbor = null;
        if ("up".equalsIgnoreCase(direction)) {
            neighbor = repository.findFirstBySortOrderLessThanOrderBySortOrderDesc(order);
        } else if ("down".equalsIgnoreCase(direction)) {
            neighbor = repository.findFirstBySortOrderGreaterThanOrderBySortOrderAsc(order);
        } else {
            return ResponseEntity.badRequest().body(ApiResponse.failure("direction �� up �� down ���w�肵�Ă�������"));
        }
        if (neighbor == null) {
            return ResponseEntity.ok(ApiResponse.success("����ȏ�ړ��ł��܂���", current));
        }
        Integer neighborOrder = neighbor.getSortOrder();
        // swap orders
        current.setSortOrder(neighborOrder);
        neighbor.setSortOrder(order);
        repository.save(neighbor);
        DemandInterval saved = repository.save(current);
        return ResponseEntity.ok(ApiResponse.success("�������X�V���܂���", saved));
    }

    public record DemandRequest(
            LocalDate date,
            DayOfWeek dayOfWeek,
            java.time.LocalTime startTime,
            java.time.LocalTime endTime,
            Integer requiredSeats,
            Long skillId,
            Boolean active
    ) {}
}
