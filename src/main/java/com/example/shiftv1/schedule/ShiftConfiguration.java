package com.example.shiftv1.schedule;

import java.time.LocalTime;

/**
 * シフト設定を管理するクラス
 * 学習用プログラムとして、設定を動的に変更可能にする
 */
public class ShiftConfiguration {
    
    // 平日シフト設定
    private final LocalTime weekdayAmStart;
    private final LocalTime weekdayAmEnd;
    private final LocalTime weekdayPmStart;
    private final LocalTime weekdayPmEnd;
    private final int weekdayEmployeesPerShift;
    
    // 休日シフト設定
    private final LocalTime weekendStart;
    private final LocalTime weekendEnd;
    private final int weekendEmployeesPerShift;
    
    // デフォルト設定
    public static final ShiftConfiguration DEFAULT = new ShiftConfiguration(
        LocalTime.of(9, 0),  // 平日午前開始
        LocalTime.of(15, 0), // 平日午前終了
        LocalTime.of(15, 0), // 平日午後開始
        LocalTime.of(21, 0), // 平日午後終了
        4,                   // 平日シフトあたりの従業員数
        LocalTime.of(9, 0),  // 休日開始
        LocalTime.of(18, 0), // 休日終了
        5                    // 休日シフトあたりの従業員数
    );
    
    public ShiftConfiguration(LocalTime weekdayAmStart, LocalTime weekdayAmEnd,
                             LocalTime weekdayPmStart, LocalTime weekdayPmEnd,
                             int weekdayEmployeesPerShift,
                             LocalTime weekendStart, LocalTime weekendEnd,
                             int weekendEmployeesPerShift) {
        this.weekdayAmStart = weekdayAmStart;
        this.weekdayAmEnd = weekdayAmEnd;
        this.weekdayPmStart = weekdayPmStart;
        this.weekdayPmEnd = weekdayPmEnd;
        this.weekdayEmployeesPerShift = weekdayEmployeesPerShift;
        this.weekendStart = weekendStart;
        this.weekendEnd = weekendEnd;
        this.weekendEmployeesPerShift = weekendEmployeesPerShift;
    }
    
    // Getters
    public LocalTime getWeekdayAmStart() { return weekdayAmStart; }
    public LocalTime getWeekdayAmEnd() { return weekdayAmEnd; }
    public LocalTime getWeekdayPmStart() { return weekdayPmStart; }
    public LocalTime getWeekdayPmEnd() { return weekdayPmEnd; }
    public int getWeekdayEmployeesPerShift() { return weekdayEmployeesPerShift; }
    public LocalTime getWeekendStart() { return weekendStart; }
    public LocalTime getWeekendEnd() { return weekendEnd; }
    public int getWeekendEmployeesPerShift() { return weekendEmployeesPerShift; }
    
    /**
     * 新しい設定を作成するビルダーメソッド
     */
    public static Builder builder() {
        return new Builder();
    }
    
    public static class Builder {
        private LocalTime weekdayAmStart = LocalTime.of(9, 0);
        private LocalTime weekdayAmEnd = LocalTime.of(15, 0);
        private LocalTime weekdayPmStart = LocalTime.of(15, 0);
        private LocalTime weekdayPmEnd = LocalTime.of(21, 0);
        private int weekdayEmployeesPerShift = 4;
        private LocalTime weekendStart = LocalTime.of(9, 0);
        private LocalTime weekendEnd = LocalTime.of(18, 0);
        private int weekendEmployeesPerShift = 5;
        
        public Builder weekdayAmStart(LocalTime start) {
            this.weekdayAmStart = start;
            return this;
        }
        
        public Builder weekdayAmEnd(LocalTime end) {
            this.weekdayAmEnd = end;
            return this;
        }
        
        public Builder weekdayPmStart(LocalTime start) {
            this.weekdayPmStart = start;
            return this;
        }
        
        public Builder weekdayPmEnd(LocalTime end) {
            this.weekdayPmEnd = end;
            return this;
        }
        
        public Builder weekdayEmployeesPerShift(int count) {
            this.weekdayEmployeesPerShift = count;
            return this;
        }
        
        public Builder weekendStart(LocalTime start) {
            this.weekendStart = start;
            return this;
        }
        
        public Builder weekendEnd(LocalTime end) {
            this.weekendEnd = end;
            return this;
        }
        
        public Builder weekendEmployeesPerShift(int count) {
            this.weekendEmployeesPerShift = count;
            return this;
        }
        
        public ShiftConfiguration build() {
            return new ShiftConfiguration(
                weekdayAmStart, weekdayAmEnd, weekdayPmStart, weekdayPmEnd,
                weekdayEmployeesPerShift, weekendStart, weekendEnd, weekendEmployeesPerShift
            );
        }
    }
}
