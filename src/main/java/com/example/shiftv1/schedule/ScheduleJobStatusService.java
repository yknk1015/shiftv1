package com.example.shiftv1.schedule;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ScheduleJobStatusService {
    public static class Status {
        public volatile boolean running;
        public volatile boolean done;
        public volatile long createdCount;
        public volatile LocalDateTime startedAt;
        public volatile LocalDateTime finishedAt;
    }

    private final Map<String, Status> jobs = new ConcurrentHashMap<>();

    private String key(int year, int month) {
        return year + "-" + month;
    }

    public void start(int year, int month, long initialCount) {
        String k = key(year, month);
        Status s = jobs.computeIfAbsent(k, kk -> new Status());
        s.running = true;
        s.done = false;
        s.createdCount = Math.max(0, initialCount);
        s.startedAt = LocalDateTime.now();
        s.finishedAt = null;
    }

    public void updateCount(int year, int month, long count) {
        Status s = jobs.computeIfAbsent(key(year, month), kk -> new Status());
        s.createdCount = Math.max(0, count);
    }

    public void finish(int year, int month, long finalCount) {
        Status s = jobs.computeIfAbsent(key(year, month), kk -> new Status());
        s.createdCount = Math.max(0, finalCount);
        s.running = false;
        s.done = true;
        s.finishedAt = LocalDateTime.now();
    }

    public Status get(int year, int month) {
        return jobs.getOrDefault(key(year, month), new Status());
    }
}

