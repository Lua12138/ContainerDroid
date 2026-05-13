package top.niunaijun.blackbox.binder;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

public class EventRingBufferTest {

    @Test
    public void snapshotKeepsNewestEventsInInsertionOrder() {
        EventRingBuffer<String> buffer = new EventRingBuffer<>(2);

        buffer.add("first");
        buffer.add("second");
        buffer.add("third");

        List<String> snapshot = buffer.snapshot();

        assertEquals(2, snapshot.size());
        assertEquals("second", snapshot.get(0));
        assertEquals("third", snapshot.get(1));
    }
}
