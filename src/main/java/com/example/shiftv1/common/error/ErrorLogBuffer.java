package com.example.shiftv1.common.error;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

@Component
public class ErrorLogBuffer {
    private final Deque<Entry> deque = new ConcurrentLinkedDeque<>();
    private final int max = 200;

    public void addError(String message, Throwable t) {
        String m = message == null ? "" : message;
        String detail = t == null ? "" : (t.getClass().getName() + ": " + String.valueOf(t.getMessage()));
        deque.addFirst(new Entry(LocalDateTime.now(), m, detail));
        while (deque.size() > max) deque.removeLast();
    }

    public List<Entry> recent() {
        return new ArrayList<>(deque);
    }

    public void clear() {
        deque.clear();
    }

    public record Entry(LocalDateTime time, String message, String detail) {}
}

