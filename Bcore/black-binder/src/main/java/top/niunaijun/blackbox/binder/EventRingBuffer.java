package top.niunaijun.blackbox.binder;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

public final class EventRingBuffer<T> {
    private final int capacity;
    private final ArrayDeque<T> events;

    public EventRingBuffer(int capacity) {
        this.capacity = Math.max(1, capacity);
        this.events = new ArrayDeque<>(this.capacity);
    }

    public synchronized void add(T event) {
        if (event == null) {
            return;
        }
        while (events.size() >= capacity) {
            events.removeFirst();
        }
        events.addLast(event);
    }

    public synchronized List<T> snapshot() {
        return new ArrayList<>(events);
    }

    public synchronized int size() {
        return events.size();
    }

    public synchronized void clear() {
        events.clear();
    }
}
