package com.example.shiftv1.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/config/pairing-settings")
public class PairingSettingsController {
    private static final Logger log = LoggerFactory.getLogger(PairingSettingsController.class);
    private final PairingSettingsRepository repo;

    public PairingSettingsController(PairingSettingsRepository repo) {
        this.repo = repo;
    }

    @GetMapping
    public ResponseEntity<PairingSettings> get() {
        PairingSettings s = repo.findAll().stream().findFirst().orElseGet(() -> {
            PairingSettings def = new PairingSettings();
            return repo.save(def);
        });
        return ResponseEntity.ok(s);
    }

    @PutMapping
    public ResponseEntity<PairingSettings> update(@RequestBody PairingSettings req) {
        try {
            PairingSettings s = repo.findAll().stream().findFirst().orElseGet(PairingSettings::new);
            s.setEnabled(req.getEnabled());
            s.setPreferShorts(req.getPreferShorts());
            s.setPairToleranceMinutes(req.getPairToleranceMinutes());
            s.setFullWindow(req.getFullWindow());
            s.setMorningWindow(req.getMorningWindow());
            s.setAfternoonWindow(req.getAfternoonWindow());
            s.setStandaloneWindows(req.getStandaloneWindows());
            PairingSettings saved = repo.save(s);
            return ResponseEntity.ok(saved);
        } catch (Exception e) {
            log.error("Failed to update pairing settings", e);
            return ResponseEntity.internalServerError().build();
        }
    }
}

