package com.example.shiftv1.config;

import com.example.shiftv1.skill.Skill;
import com.example.shiftv1.skill.SkillRepository;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/config/shift")
public class ShiftConfigController {

    private static final Logger logger = LoggerFactory.getLogger(ShiftConfigController.class);
    
    private final ShiftConfigRepository shiftConfigRepository;
    private final SkillRepository skillRepository;

    public ShiftConfigController(ShiftConfigRepository shiftConfigRepository, SkillRepository skillRepository) {
        this.shiftConfigRepository = shiftConfigRepository;
        this.skillRepository = skillRepository;
    }

    @GetMapping
    public ResponseEntity<List<ShiftConfig>> getAllShiftConfigs() {
        try {
            List<ShiftConfig> configs = shiftConfigRepository.findAll();
            return ResponseEntity.ok(configs);
        } catch (Exception e) {
            logger.error("シフト設定の取得でエラーが発生しました", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/active")
    public ResponseEntity<List<ShiftConfig>> getActiveShiftConfigs() {
        try {
            List<ShiftConfig> configs = shiftConfigRepository.findByActiveTrue();
            return ResponseEntity.ok(configs);
        } catch (Exception e) {
            logger.error("アクティブなシフト設定の取得でエラーが発生しました", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<ShiftConfig> getShiftConfig(@PathVariable Long id) {
        try {
            Optional<ShiftConfig> config = shiftConfigRepository.findById(id);
            return config.map(ResponseEntity::ok)
                    .orElse(ResponseEntity.notFound().build());
        } catch (Exception e) {
            logger.error("シフト設定の取得でエラーが発生しました", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @PostMapping
    public ResponseEntity<ShiftConfig> createShiftConfig(@Valid @RequestBody ShiftConfigRequest request) {
        try {
            logger.info("新しいシフト設定を作成します: {}", request.name());

            // 名前の重複チェック
            if (shiftConfigRepository.existsByName(request.name())) {
                logger.warn("シフト設定名が既に存在します: {}", request.name());
                return ResponseEntity.status(HttpStatus.CONFLICT).build();
            }

            ShiftConfig config = new ShiftConfig(
                request.name(),
                request.startTime(),
                request.endTime(),
                request.requiredEmployees(),
                request.dayOfWeek(),
                request.holiday()
            );

            if (request.days() != null && !request.days().isEmpty()) {
                config.setDays(new java.util.HashSet<>(request.days()));
            } else {
                config.setDays(null);
            }
            if (request.requiredSkillId() != null) {
                Skill skill = skillRepository.findById(request.requiredSkillId())
                        .orElseThrow(() -> new IllegalArgumentException("必須スキルが見つかりません"));
                config.setRequiredSkill(skill);
            } else {
                config.setRequiredSkill(null);
            }
            ShiftConfig savedConfig = shiftConfigRepository.save(config);
            logger.info("シフト設定が作成されました: {}", savedConfig.getName());
            
            return ResponseEntity.status(HttpStatus.CREATED).body(savedConfig);

        } catch (Exception e) {
            logger.error("シフト設定の作成でエラーが発生しました", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<ShiftConfig> updateShiftConfig(@PathVariable Long id, @Valid @RequestBody ShiftConfigRequest request) {
        try {
            logger.info("シフト設定を更新します: {}", id);

            Optional<ShiftConfig> existingConfig = shiftConfigRepository.findById(id);
            if (existingConfig.isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            // 名前の重複チェック（自分以外）
            if (shiftConfigRepository.existsByName(request.name())) {
                Optional<ShiftConfig> configWithSameName = shiftConfigRepository.findByName(request.name());
                if (configWithSameName.isPresent() && !configWithSameName.get().getId().equals(id)) {
                    logger.warn("シフト設定名が既に存在します: {}", request.name());
                    return ResponseEntity.status(HttpStatus.CONFLICT).build();
                }
            }

            ShiftConfig config = existingConfig.get();
            config.setName(request.name());
            config.setStartTime(request.startTime());
            config.setEndTime(request.endTime());
            config.setRequiredEmployees(request.requiredEmployees());
            config.setDayOfWeek(request.dayOfWeek());
            config.setHoliday(request.holiday());
            if (request.requiredSkillId() != null) {
                Skill skill = skillRepository.findById(request.requiredSkillId())
                        .orElseThrow(() -> new IllegalArgumentException("必須スキルが見つかりません"));
                config.setRequiredSkill(skill);
            } else {
                config.setRequiredSkill(null);
            }
            if (request.days() != null && !request.days().isEmpty()) {
                config.setDays(new java.util.HashSet<>(request.days()));
            } else {
                config.setDays(null);
            }

            ShiftConfig savedConfig = shiftConfigRepository.save(config);
            logger.info("シフト設定が更新されました: {}", savedConfig.getName());
            
            return ResponseEntity.ok(savedConfig);

        } catch (Exception e) {
            logger.error("シフト設定の更新でエラーが発生しました", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteShiftConfig(@PathVariable Long id) {
        try {
            logger.info("シフト設定を削除します: {}", id);

            if (!shiftConfigRepository.existsById(id)) {
                return ResponseEntity.notFound().build();
            }

            shiftConfigRepository.deleteById(id);
            logger.info("シフト設定が削除されました: {}", id);
            
            return ResponseEntity.noContent().build();

        } catch (Exception e) {
            logger.error("シフト設定の削除でエラーが発生しました", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @PostMapping("/{id}/toggle")
    public ResponseEntity<ShiftConfig> toggleShiftConfig(@PathVariable Long id) {
        try {
            logger.info("シフト設定のアクティブ状態を切り替えます: {}", id);

            Optional<ShiftConfig> config = shiftConfigRepository.findById(id);
            if (config.isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            ShiftConfig shiftConfig = config.get();
            shiftConfig.setActive(!shiftConfig.getActive());
            
            ShiftConfig savedConfig = shiftConfigRepository.save(shiftConfig);
            logger.info("シフト設定のアクティブ状態が更新されました: {} -> {}", 
                       savedConfig.getName(), savedConfig.getActive());
            
            return ResponseEntity.ok(savedConfig);

        } catch (Exception e) {
            logger.error("シフト設定のアクティブ状態切り替えでエラーが発生しました", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    public record ShiftConfigRequest(
            String name,
            LocalTime startTime,
            LocalTime endTime,
            Integer requiredEmployees,
            java.time.DayOfWeek dayOfWeek,
            Boolean holiday,
            java.util.List<java.time.DayOfWeek> days,
            Long requiredSkillId
    ) {}
}





