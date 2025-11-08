package com.example.shiftv1.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class FreePlaceholderSettings {
    private boolean onlyWeekdays;
    private boolean skipHolidays;

    public FreePlaceholderSettings(
            @Value("${shift.placeholder.free.onlyWeekdays:false}") boolean onlyWeekdays,
            @Value("${shift.placeholder.free.skipHolidays:false}") boolean skipHolidays) {
        this.onlyWeekdays = onlyWeekdays;
        this.skipHolidays = skipHolidays;
    }

    public boolean isOnlyWeekdays() { return onlyWeekdays; }
    public boolean isSkipHolidays() { return skipHolidays; }

    public void setOnlyWeekdays(boolean onlyWeekdays) { this.onlyWeekdays = onlyWeekdays; }
    public void setSkipHolidays(boolean skipHolidays) { this.skipHolidays = skipHolidays; }
}

