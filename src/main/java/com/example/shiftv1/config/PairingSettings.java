package com.example.shiftv1.config;

import jakarta.persistence.*;

@Entity
@Table(name = "pairing_settings")
public class PairingSettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "enabled")
    private Boolean enabled = Boolean.TRUE;

    @Column(name = "prefer_shorts")
    private Boolean preferShorts = Boolean.TRUE;

    @Column(name = "pair_tolerance_minutes")
    private Integer pairToleranceMinutes = 0;

    @Column(name = "full_window", length = 32)
    private String fullWindow = "09:00-18:00";

    @Column(name = "morning_window", length = 32)
    private String morningWindow = "09:00-13:00";

    @Column(name = "afternoon_window", length = 32)
    private String afternoonWindow = "13:00-18:00";

    @Column(name = "standalone_windows", length = 256)
    private String standaloneWindows = "17:00-21:00";

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

    public Boolean getPreferShorts() { return preferShorts; }
    public void setPreferShorts(Boolean preferShorts) { this.preferShorts = preferShorts; }

    public Integer getPairToleranceMinutes() { return pairToleranceMinutes; }
    public void setPairToleranceMinutes(Integer pairToleranceMinutes) { this.pairToleranceMinutes = pairToleranceMinutes; }

    public String getFullWindow() { return fullWindow; }
    public void setFullWindow(String fullWindow) { this.fullWindow = fullWindow; }

    public String getMorningWindow() { return morningWindow; }
    public void setMorningWindow(String morningWindow) { this.morningWindow = morningWindow; }

    public String getAfternoonWindow() { return afternoonWindow; }
    public void setAfternoonWindow(String afternoonWindow) { this.afternoonWindow = afternoonWindow; }

    public String getStandaloneWindows() { return standaloneWindows; }
    public void setStandaloneWindows(String standaloneWindows) { this.standaloneWindows = standaloneWindows; }
}

